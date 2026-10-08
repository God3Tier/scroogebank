package main

import (
	"client-profile-management-service/src/internal/accounts"
	"client-profile-management-service/src/internal/controller/client"
	"client-profile-management-service/src/internal/repository"
	"client-profile-management-service/src/internal/security"
	clientservice "client-profile-management-service/src/internal/service/client"
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
	awssqs "github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/gin-gonic/gin"
	_ "github.com/lib/pq"
)

// accountLookupTimeout bounds the call to account-service made before a client
// is deleted. It runs inside the caller's request, so it must stay well under
// the API Gateway timeout.
const accountLookupTimeout = 2 * time.Second

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

	registry := repository.New(db)
	clientSvc := clientservice.NewClientService(
		registry.GetClientRepository(),
		accounts.NewHTTPDirectory(accountServiceURL, accountLookupTimeout),
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
	auditLogger := auditqueue.AuditLogger(sqsClient, queueURL)
	routers.RegisterRoutes(router, client.New(clientSvc), db, verifier.Middleware(), auditLogger)

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
	return nil
}

func envOrDefault(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}
