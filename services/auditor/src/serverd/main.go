// This binary is the auditor's gRPC/HTTP server entrypoint. It is a
// separate program from src/cronjob; anything both need (currently just
// InitialiseDB) lives in src/internal/bootstrap instead of here, since a
// main package cannot be imported by another package.
package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"sync"
	"syscall"
	"time"

	auditv1 "auditor/src/gen/audit/v1"
	"auditor/src/internal/bootstrap"
	"auditor/src/internal/controller/audit"
	corruptedLogController "auditor/src/internal/controller/corrupted_log"
	"auditor/src/internal/repository"
	"auditor/src/internal/security"
	audtiSvc "auditor/src/internal/service/audit"
	corruptedLogService "auditor/src/internal/service/corrupted_logs"
	"auditor/src/serverd/queue"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/keepalive"
)

const defaultPort = 8080

func main() {
	ctx := context.Background()
	if err := run(ctx); err != nil {
		log.Fatal(err)
	}
}

func run(ctx context.Context) error {
	// Setup db and dependencies
	db, err := bootstrap.InitialiseDB()
	if err != nil {
		return err
	}

	// Every call must carry a CRM user's Cognito access token, forwarded by the
	// calling service (ADR-0021).
	verifier, err := security.NewCognitoVerifier(os.Getenv("COGNITO_ISSUER_URI"), os.Getenv("COGNITO_APP_CLIENT_ID"))
	if err != nil {
		return fmt.Errorf("configure Cognito authentication: %w", err)
	}
	tlsCert, tlsKey, err := tlsFiles()
	if err != nil {
		return err
	}

	repository := repository.New(db)
	service := audtiSvc.New(repository.GetAudit())
	controller := audit.New(service)
	corruptedLogsService := corruptedLogService.New(repository.GetCorruptedLog())
	corruptedLogsController := corruptedLogController.New(corruptedLogsService)

	// Set up wait group for goroutines
	var wg sync.WaitGroup

	wg.Add(3)

	// Set up grpc server
	grpcPort := os.Getenv("GRPC_PORT")
	lis, err := net.Listen("tcp", grpcPort)
	if err != nil {
		return fmt.Errorf("failed to listen: %v", err)
	}

	grpcOptions := []grpc.ServerOption{
		grpc.UnaryInterceptor(security.UnaryInterceptor(verifier)),
		// Callers keep one long-lived connection. Ending it every few minutes
		// makes them reconnect, spreading calls over the auditor's tasks.
		grpc.KeepaliveParams(keepalive.ServerParameters{MaxConnectionAge: 5 * time.Minute}),
	}
	if tlsCert != "" {
		creds, err := credentials.NewServerTLSFromFile(tlsCert, tlsKey)
		if err != nil {
			return fmt.Errorf("load TLS certificate: %w", err)
		}
		grpcOptions = append(grpcOptions, grpc.Creds(creds))
	}
	grpcServer := grpc.NewServer(grpcOptions...)
	auditv1.RegisterAuditServiceServer(grpcServer, controller)

	go func() {
		defer wg.Done()
		if err := grpcServer.Serve(lis); err != nil {
			log.Fatalf("failed to serve: %v", err)
		}
	}()

	// Initialise the SQS consumer for audit events
	sqsClient, err := newSQSClient(ctx)
	if err != nil {
		return fmt.Errorf("failed to initialise SQS client: %v", err)
	}

	rejecter := queue.NewRejecter(sqsClient, os.Getenv("AUDIT_DLQ_URL"))
	consumer := queue.NewConsumer(sqsClient, os.Getenv("AUDIT_QUEUE_URL"), repository.GetAudit(), rejecter)

	consumerCtx, stopConsumer := context.WithCancel(ctx)

	go func() {
		defer wg.Done()
		consumer.Run(consumerCtx)
	}()

	// Setup HTTP health server
	port := getHTTPPort()
	server := &http.Server{
		Addr:              ":" + strconv.Itoa(port),
		Handler:           newHandler(corruptedLogsController, verifier),
		ReadHeaderTimeout: 5 * time.Second,
	}

	shutdownContext, stop := signal.NotifyContext(ctx, os.Interrupt, syscall.SIGTERM)
	defer stop()

	go func() {
		defer wg.Done()
		<-shutdownContext.Done()
		stopConsumer() // interrupt an in-flight long poll instead of waiting up to 20s
		shutdownDeadline, cancel := context.WithTimeout(ctx, 5*time.Second)
		defer cancel()

		if err := server.Shutdown(shutdownDeadline); err != nil {
			log.Printf("server shutdown failed: %v", err)
		}
	}()

	var serveErr error
	if tlsCert != "" {
		log.Printf("auditor listening on https://localhost:%d", port)
		serveErr = server.ListenAndServeTLS(tlsCert, tlsKey)
	} else {
		log.Printf("auditor listening on http://localhost:%d", port)
		serveErr = server.ListenAndServe()
	}
	if serveErr != nil && !errors.Is(serveErr, http.ErrServerClosed) {
		return fmt.Errorf("server failed: %v", serveErr)
	}

	wg.Wait()
	return nil
}

// newSQSClient builds an SQS client using the AWS SDK's default credential
// chain (an IAM role in AWS, or environment variables locally) — no
// credentials are ever hardcoded. When AWS_ENDPOINT_URL is set, requests are
// pointed at that endpoint instead, so the same code path runs against
// LocalStack in local development.
func newSQSClient(ctx context.Context) (*sqs.Client, error) {
	cfg, err := awsconfig.LoadDefaultConfig(ctx, awsconfig.WithRegion(os.Getenv("AWS_REGION")))
	if err != nil {
		return nil, fmt.Errorf("load AWS config: %w", err)
	}

	return sqs.NewFromConfig(cfg, func(o *sqs.Options) {
		if endpoint := os.Getenv("AWS_ENDPOINT_URL"); endpoint != "" {
			o.BaseEndpoint = aws.String(endpoint)
		}
	}), nil
}

// tlsFiles returns the certificate and key both ports serve TLS with
// (AUDITOR_TLS_CERT_FILE, AUDITOR_TLS_KEY_FILE), from the project CA in the
// target design (ADR-0021). Both unset means plaintext, for local
// development only; setting just one is a mistake.
func tlsFiles() (string, string, error) {
	cert, key := os.Getenv("AUDITOR_TLS_CERT_FILE"), os.Getenv("AUDITOR_TLS_KEY_FILE")
	if (cert == "") != (key == "") {
		return "", "", errors.New("set both AUDITOR_TLS_CERT_FILE and AUDITOR_TLS_KEY_FILE, or neither")
	}
	return cert, key, nil
}

/*
 * Sure
 */
func getHTTPPort() int {
	port, err := strconv.Atoi(os.Getenv("HTTP_PORT"))
	if err != nil || port < 1 || port > 65535 {
		return defaultPort
	}
	return port
}
