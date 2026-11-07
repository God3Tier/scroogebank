package main

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
	"time"

	"receiver/src/internal/models"
	"receiver/src/internal/outbox"
	"receiver/src/internal/repository"
	serviceauth "receiver/src/internal/security"
	"receiver/src/serverd/accounts"
	"receiver/src/serverd/audit"
	"receiver/src/serverd/routers"
	"receiver/src/serverd/security"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	awssqs "github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/gin-gonic/gin"
	_ "github.com/lib/pq"
)

// accountLookupTimeout bounds each call to account-service. It runs inside
// the caller's request, so it must stay well under the API Gateway timeout.
const accountLookupTimeout = 2 * time.Second

// relayInterval is how often the relay sends waiting audit events.
const relayInterval = 2 * time.Second

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

	db, err := sql.Open("postgres", databaseDSN())
	if err != nil {
		return fmt.Errorf("open database: %w", err)
	}
	defer db.Close()
	// Bound the pool. Without a limit, a burst opens one connection per request
	// and exhausts the db.t4g.micro's connection slots ("too many clients"):
	// requests fail and audit events are lost (load test, 2026-11-02). The
	// instance allows 79, five of them reserved for administrators, so this
	// pool and the audit pool below together keep six tasks at 60.
	db.SetMaxOpenConns(8)
	db.SetMaxIdleConns(8)
	db.SetConnMaxIdleTime(5 * time.Minute)

	// Read-audit outbox writes get two connections of their own, so they never
	// wait behind a burst's queries for the pool above (ADR-0078). With it, a
	// task uses at most 10.
	auditDB, err := sql.Open("postgres", databaseDSN())
	if err != nil {
		return fmt.Errorf("open audit database pool: %w", err)
	}
	defer auditDB.Close()
	auditDB.SetMaxOpenConns(2)
	auditDB.SetMaxIdleConns(2)
	auditDB.SetConnMaxIdleTime(5 * time.Minute)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := db.PingContext(ctx); err != nil {
		return fmt.Errorf("connect to database: %w", err)
	}

	verifier, err := security.NewCognitoVerifier(os.Getenv("COGNITO_ISSUER_URI"), os.Getenv("COGNITO_APP_CLIENT_ID"))
	if err != nil {
		return fmt.Errorf("configure Cognito authentication: %w", err)
	}

	saveAudit, err := auditSaver(db, auditDB)
	if err != nil {
		return err
	}

	router := gin.New()
	router.Use(gin.Logger(), gin.Recovery())
	transactionRepository := repository.New(db)
	issuer := strings.TrimSpace(os.Getenv("COGNITO_ISSUER_URI"))
	allowedClientIDs := strings.FieldsFunc(os.Getenv("COGNITO_HEALTH_CLIENT_IDS"), func(r rune) bool { return r == ',' || r == ' ' })
	production := strings.EqualFold(strings.TrimSpace(os.Getenv("APP_ENV")), "production")
	if issuer != "" || len(allowedClientIDs) > 0 {
		if issuer == "" || len(allowedClientIDs) == 0 {
			return fmt.Errorf("COGNITO_ISSUER_URI and COGNITO_HEALTH_CLIENT_IDS must be configured together")
		}
		serviceTokens, err := serviceauth.NewServiceTokenVerifier(issuer, allowedClientIDs, "crm-transactions/read")
		if err != nil {
			return fmt.Errorf("configure transaction read authentication: %w", err)
		}
		routers.RegisterClientHealthRoutes(router, transactionRepository, serviceTokens.Middleware())
	}
	if production && len(allowedClientIDs) == 0 {
		return fmt.Errorf("production requires COGNITO_HEALTH_CLIENT_IDS")
	}
	if issuer == "" {
		return fmt.Errorf("COGNITO_ISSUER_URI is required")
	}
	routers.RegisterRoutes(router, transactionRepository, repository.NewTransactionStats(db),
		accounts.NewHTTPDirectory(accountServiceURL, accountLookupTimeout), verifier.Middleware(), saveAudit)

	server := &http.Server{
		Addr:              envOrDefault("APP_ADDR", ":8080"),
		Handler:           router,
		ReadHeaderTimeout: 5 * time.Second,
	}
	if production {
		return server.ListenAndServeTLS(os.Getenv("APP_TLS_CERT_FILE"), os.Getenv("APP_TLS_KEY_FILE"))
	}
	return server.ListenAndServe()
}

// auditSaver starts the relay that sends read events from read_audit_outbox
// to the Auditor's queue, and returns the function that saves them there,
// through auditDB, or else sends them straight to the queue (ADR-0078); the
// import's events are the Lambda's to send (ADR-0064 decision 5).
// Production needs the queue. Locally, as for the poller, an empty
// AUDIT_QUEUE_URL turns read auditing off.
func auditSaver(db, auditDB *sql.DB) (audit.Save, error) {
	queueURL := os.Getenv("AUDIT_QUEUE_URL")
	if queueURL == "" {
		if envOrDefault("APP_ENV", "production") == "production" {
			return nil, fmt.Errorf("AUDIT_QUEUE_URL is required")
		}
		log.Printf("transaction read audit events disabled: AUDIT_QUEUE_URL is empty")
		return func(context.Context, models.AuditEvent) error { return nil }, nil
	}
	region := os.Getenv("AWS_REGION")
	if region == "" {
		return nil, fmt.Errorf("AWS_REGION is required")
	}
	awsCfg, err := awsconfig.LoadDefaultConfig(context.Background(), awsconfig.WithRegion(region))
	if err != nil {
		return nil, fmt.Errorf("load AWS configuration for Auditor SQS: %w", err)
	}
	sqsClient := awssqs.NewFromConfig(awsCfg, func(options *awssqs.Options) {
		if endpoint := os.Getenv("AWS_ENDPOINT_URL"); endpoint != "" {
			options.BaseEndpoint = aws.String(endpoint)
		}
	})
	relay := outbox.NewRelay(db, sqsClient, queueURL, outbox.ReadTable)
	go relay.Run(context.Background(), relayInterval)
	return audit.WithFallback(func(ctx context.Context, event models.AuditEvent) error {
		return outbox.Save(ctx, auditDB, event)
	}, relay.Send), nil
}

func databaseDSN() string {
	dsn := fmt.Sprintf("host=%s port=%s user=%s password=%s dbname=%s sslmode=%s",
		envOrDefault("DB_HOST", "db"),
		envOrDefault("DB_PORT", "5432"),
		envOrDefault("DB_USER", "crm"),
		envOrDefault("DB_PASSWORD", "crm"),
		envOrDefault("DB_NAME", "crm"),
		envOrDefault("DB_SSLMODE", "disable"),
	)
	if rootCert := os.Getenv("DB_SSLROOTCERT"); rootCert != "" {
		dsn += fmt.Sprintf(" sslrootcert='%s'", strings.ReplaceAll(rootCert, "'", "\\'"))
	}
	return dsn
}

// validateProductionSecurityConfig refuses to start in production without
// encryption on every hop: the service receives callers' tokens and forwards
// them to account-service (ADR-0064 decision 6).
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
