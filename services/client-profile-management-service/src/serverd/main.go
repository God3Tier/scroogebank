package main

import (
	"client-profile-management-service/src/internal/accounts"
	aggregatecontroller "client-profile-management-service/src/internal/controller/aggregate"
	"client-profile-management-service/src/internal/controller/client"
	"client-profile-management-service/src/internal/documents"
	"client-profile-management-service/src/internal/encryption"
	"client-profile-management-service/src/internal/repository"
	aggregaterepository "client-profile-management-service/src/internal/repository/aggregate"
	"client-profile-management-service/src/internal/security"
	aggregateservice "client-profile-management-service/src/internal/service/aggregate"
	clientservice "client-profile-management-service/src/internal/service/client"
	"client-profile-management-service/src/internal/users"
	"client-profile-management-service/src/serverd/routers"
	auditqueue "client-profile-management-service/src/serverd/sqs"
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
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/kms"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	awssqs "github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/gin-gonic/gin"
	_ "github.com/lib/pq"
)

// accountLookupTimeout bounds the call to account-service made before a client
// is deleted, or while clients are reassigned. It runs inside the caller's
// request, so it must stay well under the API Gateway timeout.
const accountLookupTimeout = 2 * time.Second

// userLookupTimeout bounds each call to user-service made before clients are
// reassigned (ADR-0060), for the same reason.
const userLookupTimeout = 2 * time.Second

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}

func run() error {
	if err := validateProductionSecurityConfig(); err != nil {
		return err
	}
	accountServiceURL := strings.TrimSpace(os.Getenv("ACCOUNT_SERVICE_BASE_URL"))
	if accountServiceURL == "" {
		return fmt.Errorf("ACCOUNT_SERVICE_BASE_URL is required")
	}
	userServiceURL := strings.TrimSpace(os.Getenv("USER_SERVICE_BASE_URL"))
	if userServiceURL == "" {
		return fmt.Errorf("USER_SERVICE_BASE_URL is required")
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

	region := os.Getenv("AWS_REGION")
	if region == "" {
		return fmt.Errorf("AWS_REGION is required")
	}
	awsCfg, err := awsconfig.LoadDefaultConfig(context.Background(), awsconfig.WithRegion(region))
	if err != nil {
		return fmt.Errorf("load AWS configuration: %w", err)
	}
	protector, err := newProtector(awsCfg)
	if err != nil {
		return err
	}
	documentStore, err := newDocumentStore(awsCfg)
	if err != nil {
		return err
	}

	registry := repository.New(db, protector, documentStore)
	clientSvc := clientservice.NewClientService(
		registry.GetClientRepository(),
		accounts.NewHTTPDirectory(accountServiceURL, accountLookupTimeout),
		users.NewHTTPDirectory(userServiceURL, userLookupTimeout),
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
	assignmentVerifier, err := security.NewServiceCognitoVerifier(issuer,
		os.Getenv("CLIENT_HEALTH_ASSIGNMENT_CLIENT_ID"),
		os.Getenv("CLIENT_HEALTH_ASSIGNMENT_SCOPE"))
	if err != nil {
		return fmt.Errorf("configure assignment authentication: %w", err)
	}

	queueURL := os.Getenv("AUDIT_QUEUE_URL")
	if queueURL == "" {
		return fmt.Errorf("AUDIT_QUEUE_URL is required")
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
	aggregates := aggregatecontroller.New(aggregateservice.New(aggregaterepository.New(db), protector))
	routers.RegisterRoutes(router, client.New(clientSvc), aggregates, db, verifier.Middleware(), auditLogger)
	routers.RegisterAssignmentRoute(router, assignmentVerifier.Middleware(), db)

	server := &http.Server{
		Addr:              envOrDefault("APP_ADDR", ":8080"),
		Handler:           router,
		ReadHeaderTimeout: 5 * time.Second,
	}
	if envOrDefault("APP_ENV", "production") == "production" {
		return server.ListenAndServeTLS(os.Getenv("APP_TLS_CERT_FILE"), os.Getenv("APP_TLS_KEY_FILE"))
	}
	return server.ListenAndServe()
}

// newProtector encrypts client personal data with per-client data keys from
// KMS, stored wrapped in DynamoDB, and computes email and phone blind indexes
// with the shared KMS HMAC key (ADR-0030). It is required in every mode, so
// no client is ever stored unencrypted. Locally, AWS_ENDPOINT_URL_KMS and
// AWS_ENDPOINT_URL_DYNAMODB point the SDK at LocalStack.
func newProtector(awsCfg aws.Config) (encryption.Protector, error) {
	settings := map[string]string{}
	for _, name := range []string{"CLIENT_KEYS_TABLE", "CLIENT_DATA_KEY_ID", "CLIENT_INDEX_KEY_ID"} {
		value := strings.TrimSpace(os.Getenv(name))
		if value == "" {
			return nil, fmt.Errorf("%s is required for client data encryption", name)
		}
		settings[name] = value
	}
	return encryption.NewAWS(kms.NewFromConfig(awsCfg), dynamodb.NewFromConfig(awsCfg),
		settings["CLIENT_KEYS_TABLE"], settings["CLIENT_DATA_KEY_ID"], settings["CLIENT_INDEX_KEY_ID"]), nil
}

// newDocumentStore keeps identity documents, already sealed with each client's
// data key, in the private bucket CLIENT_DOCUMENTS_BUCKET (ADR-0041). Locally,
// AWS_ENDPOINT_URL_S3 points the SDK at LocalStack, which needs path-style
// addressing.
func newDocumentStore(awsCfg aws.Config) (documents.Store, error) {
	bucket := strings.TrimSpace(os.Getenv("CLIENT_DOCUMENTS_BUCKET"))
	if bucket == "" {
		return nil, fmt.Errorf("CLIENT_DOCUMENTS_BUCKET is required for identity documents")
	}
	client := s3.NewFromConfig(awsCfg, func(options *s3.Options) {
		options.UsePathStyle = os.Getenv("AWS_ENDPOINT_URL_S3") != ""
	})
	return documents.NewS3(client, bucket), nil
}

func databaseURL() string {
	sslmode := envOrDefault("DB_SSLMODE", "disable")
	dsn := fmt.Sprintf("host=%s port=%s user=%s password=%s dbname=%s sslmode=%s",
		envOrDefault("DB_HOST", "localhost"),
		envOrDefault("DB_PORT", "5432"),
		envOrDefault("DB_USER", "postgres"),
		envOrDefault("DB_PASSWORD", "postgres"),
		envOrDefault("DB_NAME", "myapp"),
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
	// Client deletion forwards the caller's access token to account-service,
	// so that hop must be encrypted too.
	if !strings.HasPrefix(strings.TrimSpace(os.Getenv("ACCOUNT_SERVICE_BASE_URL")), "https://") {
		return fmt.Errorf("production requires an https ACCOUNT_SERVICE_BASE_URL")
	}
	if !strings.HasPrefix(strings.TrimSpace(os.Getenv("USER_SERVICE_BASE_URL")), "https://") {
		return fmt.Errorf("production requires an https USER_SERVICE_BASE_URL")
	}
	return nil
}

func envOrDefault(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}
