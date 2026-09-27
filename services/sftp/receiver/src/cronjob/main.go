package main

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"

	"receiver/src/cronjob/poller"
	"receiver/src/internal/repository"

	_ "github.com/lib/pq"
)

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}

func run() error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	db, err := sql.Open("postgres", databaseDSN())
	if err != nil {
		return fmt.Errorf("open database: %w", err)
	}
	defer db.Close()

	if err := db.PingContext(ctx); err != nil {
		return fmt.Errorf("connect to database: %w", err)
	}

	config, err := poller.LoadConfig()
	if err != nil {
		return err
	}
	consumer := poller.New(config, repository.New(db))
	return consumer.Run(ctx, config.Interval)
}

func databaseDSN() string {
	return fmt.Sprintf(
		"host=%s port=%s user=%s password=%s dbname=%s sslmode=disable",
		envOrDefault("DB_HOST", "db"),
		envOrDefault("DB_PORT", "5432"),
		envOrDefault("DB_USER", "crm"),
		envOrDefault("DB_PASSWORD", "crm"),
		envOrDefault("DB_NAME", "crm"),
	)
}

func envOrDefault(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}
