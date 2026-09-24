package serverd

import (
	"context"
	"database/sql"
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

	"auditor/src/gen"
	"auditor/src/internal/controller"
	"auditor/src/internal/repository"
	"auditor/src/internal/service"
	"auditor/src/serverd/queue"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"google.golang.org/grpc"
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
	db, err := IntialiseDB()
	if err != nil {
		return err
	}

	repository := repository.New(db)
	service := service.New(repository.GetAudit())
	controller := controller.New(service)

	// Set up wait group for goroutines
	var wg sync.WaitGroup

	wg.Add(3)

	// Set up grpc server
	grpcPort := os.Getenv("GRPC_PORT")
	lis, err := net.Listen("tcp", grpcPort)
	if err != nil {
		return fmt.Errorf("failed to listen: %v", err)
	}

	grpcServer := grpc.NewServer()
	gen.RegisterAuditServiceServer(grpcServer, controller)

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
		Handler:           newHandler(),
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

	log.Printf("auditor listening on http://localhost:%d", port)
	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return fmt.Errorf("server failed: %v", err)
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

func IntialiseDB() (*sql.DB, error) {
	dbname := os.Getenv("DB_NAME")
	host := os.Getenv("HOST")
	user := os.Getenv("USER")
	password := os.Getenv("PASSWORD")
	sslmode := os.Getenv("SSLMODE")

	return sql.Open("postgres", fmt.Sprintf("dbname=%s host=%s user=%s password=%s sslmode=%s", dbname, host, user, password, sslmode))
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
