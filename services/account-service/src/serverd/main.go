package main

import (
	"account-service/src/internal/clients"
	"account-service/src/internal/controller/account"
	aggregatecontroller "account-service/src/internal/controller/aggregate"
	accountrepository "account-service/src/internal/repository/account"
	aggregaterepository "account-service/src/internal/repository/aggregate"
	"account-service/src/internal/security"
	accountService "account-service/src/internal/service/account"
	aggregateservice "account-service/src/internal/service/aggregate"
	"account-service/src/serverd/routers"
	auditqueue "account-service/src/serverd/sqs"
	"context"
	"database/sql"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	awssqs "github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/gin-gonic/gin"
	_ "github.com/lib/pq"
)

// clientLookupTimeout bounds the call to client-profile-management-service
// made while creating an account. It runs inside the caller's request, so it
// must stay well under the API Gateway timeout.
const clientLookupTimeout = 2 * time.Second

// lookupScope is the Cognito scope a service token needs for
// POST /internal/accounts/lookup: resource server crm-accounts, scope lookup.
const lookupScope = "crm-accounts/lookup"

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}

func run() error {
	if err := validateProductionSecurityConfig(); err != nil {
		return err
	}
	clientServiceURL := strings.TrimSpace(os.Getenv("CLIENT_SERVICE_BASE_URL"))
	if clientServiceURL == "" {
		return fmt.Errorf("CLIENT_SERVICE_BASE_URL is required")
	}

	db, err := sql.Open("postgres", databaseURL())
	if err != nil {
		return fmt.Errorf("open database: %w", err)
	}
	defer db.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := db.PingContext(ctx); err != nil {
		return fmt.Errorf("connect to database: %w", err)
	}

	accountSvc := accountService.NewAccountService(
		accountrepository.NewAccountRepository(db),
		clients.NewHTTPDirectory(clientServiceURL, clientLookupTimeout),
	)

	router := gin.New()
	router.Use(gin.Logger(), gin.Recovery())
	router.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "ok"})
	})
	issuer := os.Getenv("COGNITO_ISSUER_URI")
	appClientID := os.Getenv("COGNITO_APP_CLIENT_ID")
	verifier, err := security.NewCognitoVerifier(issuer, appClientID)
	if err != nil {
		return fmt.Errorf("configure Cognito authentication: %w", err)
	}

	queueURL := os.Getenv("AUDIT_QUEUE_URL")
	if queueURL == "" {
		return fmt.Errorf("AUDIT_QUEUE_URL is required")
	}
	region := os.Getenv("AWS_REGION")
	if region == "" {
		return fmt.Errorf("AWS_REGION is required")
	}
	awsCfg, err := awsconfig.LoadDefaultConfig(context.Background(), awsconfig.WithRegion(region))
	if err != nil {
		return fmt.Errorf("load AWS configuration for Auditor SQS: %w", err)
	}
	sqsClient := awssqs.NewFromConfig(awsCfg, func(options *awssqs.Options) {
		if endpoint := os.Getenv("AWS_ENDPOINT_URL"); endpoint != "" {
			options.BaseEndpoint = aws.String(endpoint)
		}
	})
	// Audit events are saved to the outbox after each request and sent to the
	// Auditor's queue by the relay (ADR-0020).
	auditLogger := auditqueue.AuditLogger(auditqueue.NewPostgresOutbox(db))
	go auditqueue.NewRelay(db, sqsClient, queueURL).Run(context.Background())
	// App clients allowed to call /internal with a client-credentials token,
	// such as the transaction import. Empty means no service may call.
	serviceClientIDs := strings.FieldsFunc(os.Getenv("COGNITO_SERVICE_CLIENT_IDS"), func(r rune) bool { return r == ',' || r == ' ' })
	aggregates := aggregatecontroller.New(aggregateservice.New(aggregaterepository.New(db)))
	routers.RegisterRoutes(router, account.NewAccountController(accountSvc), aggregates, db,
		verifier.Middleware(), verifier.ServiceMiddleware(serviceClientIDs, lookupScope), auditLogger)

	server := &http.Server{
		Addr:              envOrDefault("APP_ADDR", ":8083"),
		Handler:           router,
		ReadHeaderTimeout: 5 * time.Second,
	}
	if envOrDefault("APP_ENV", "production") == "production" {
		return server.ListenAndServeTLS(os.Getenv("APP_TLS_CERT_FILE"), os.Getenv("APP_TLS_KEY_FILE"))
	}
	return server.ListenAndServe()
}

func databaseURL() string {
	sslmode := envOrDefault("DB_SSLMODE", "disable")
	dsn := fmt.Sprintf("host=%s port=%s user=%s password=%s dbname=%s sslmode=%s",
		envOrDefault("DB_HOST", "localhost"),
		envOrDefault("DB_PORT", "5436"),
		envOrDefault("DB_USER", "postgres"),
		envOrDefault("DB_PASSWORD", "postgres"),
		envOrDefault("DB_NAME", "accounts"),
		sslmode,
	)
	if rootCert := os.Getenv("DB_SSLROOTCERT"); rootCert != "" {
		dsn += fmt.Sprintf(" sslrootcert='%s'", strings.ReplaceAll(rootCert, "'", "\\'"))
	}
	return dsn
}

func validateProductionSecurityConfig() error {
	appEnv := envOrDefault("APP_ENV", "production")
	if appEnv == "local" {
		return nil
	}
	if appEnv != "production" {
		return fmt.Errorf("APP_ENV must be local or production")
	}
	if os.Getenv("DB_SSLMODE") != "verify-full" || os.Getenv("DB_SSLROOTCERT") == "" {
		return fmt.Errorf("production requires DB_SSLMODE=verify-full and DB_SSLROOTCERT")
	}
	if os.Getenv("APP_TLS_CERT_FILE") == "" || os.Getenv("APP_TLS_KEY_FILE") == "" {
		return fmt.Errorf("production requires APP_TLS_CERT_FILE and APP_TLS_KEY_FILE")
	}
	// Account creation forwards the caller's access token to the client
	// service, so that hop must be encrypted too.
	if !strings.HasPrefix(strings.TrimSpace(os.Getenv("CLIENT_SERVICE_BASE_URL")), "https://") {
		return fmt.Errorf("production requires an https CLIENT_SERVICE_BASE_URL")
	}
	return nil
}

func envOrDefault(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}
