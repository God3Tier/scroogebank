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
	routers.RegisterRoutes(router, repository.New(db), repository.NewTransactionStats(db))
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
