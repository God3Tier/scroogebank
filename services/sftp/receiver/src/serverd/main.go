package main

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"os"
	"strings"
	"time"

	"receiver/src/internal/repository"
	"receiver/src/internal/security"
	"receiver/src/serverd/routers"

	"github.com/gin-gonic/gin"
	_ "github.com/lib/pq"
)

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}

func run() error {
	db, err := sql.Open("postgres", databaseDSN())
	if err != nil {
		return fmt.Errorf("open database: %w", err)
	}
	defer db.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := db.PingContext(ctx); err != nil {
		return fmt.Errorf("connect to database: %w", err)
	}
	router := gin.New()
	router.Use(gin.Logger(), gin.Recovery())
	transactionRepository := repository.New(db)
	routers.RegisterRoutes(router, transactionRepository, repository.NewTransactionStats(db))
	issuer := strings.TrimSpace(os.Getenv("COGNITO_ISSUER_URI"))
	allowedClientIDs := strings.FieldsFunc(os.Getenv("COGNITO_HEALTH_CLIENT_IDS"), func(r rune) bool { return r == ',' || r == ' ' })
	production := strings.EqualFold(strings.TrimSpace(os.Getenv("APP_ENV")), "production")
	if production && (issuer == "" || len(allowedClientIDs) == 0) {
		return fmt.Errorf("production requires COGNITO_ISSUER_URI and COGNITO_HEALTH_CLIENT_IDS")
	}
	if issuer != "" || len(allowedClientIDs) > 0 {
		if issuer == "" || len(allowedClientIDs) == 0 {
			return fmt.Errorf("COGNITO_ISSUER_URI and COGNITO_HEALTH_CLIENT_IDS must be configured together")
		}
		serviceTokens, err := security.NewServiceTokenVerifier(issuer, allowedClientIDs, "crm-transactions/read")
		if err != nil {
			return fmt.Errorf("configure transaction read authentication: %w", err)
		}
		routers.RegisterClientHealthRoutes(router, transactionRepository, serviceTokens.Middleware())
	}
	return router.Run(envOrDefault("APP_ADDR", ":8080"))
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

func envOrDefault(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}
