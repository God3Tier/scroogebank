package main

import (
	"context"
	"database/sql"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"

	"receiver/src/cronjob/accounts"
	"receiver/src/cronjob/poller"
	auditsqs "receiver/src/cronjob/sqs"
	"receiver/src/internal/repository"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	awssqs "github.com/aws/aws-sdk-go-v2/service/sqs"
	_ "github.com/lib/pq"
)

func main() {
	once := flag.Bool("once", false, "poll the SFTP server once and exit")
	flag.Parse()
	if err := run(*once); err != nil {
		log.Fatal(err)
	}
}

func run(once bool) error {
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
	var auditWriter poller.AuditWriter
	if queueURL := os.Getenv("AUDIT_QUEUE_URL"); queueURL != "" {
		region := os.Getenv("AWS_REGION")
		if region == "" {
			return fmt.Errorf("AWS_REGION is required when AUDIT_QUEUE_URL is set")
		}
		awsCfg, err := awsconfig.LoadDefaultConfig(ctx, awsconfig.WithRegion(region))
		if err != nil {
			return fmt.Errorf("load AWS configuration: %w", err)
		}
		client := awssqs.NewFromConfig(awsCfg, func(options *awssqs.Options) {
			if endpoint := os.Getenv("AWS_ENDPOINT_URL"); endpoint != "" {
				options.BaseEndpoint = aws.String(endpoint)
			}
		})
		auditWriter = auditsqs.NewWriter(client, queueURL)
		log.Printf("transaction audit events enabled for queue %s", queueURL)
	} else {
		log.Printf("transaction audit events disabled: AUDIT_QUEUE_URL is empty")
	}
	accountConfig, err := accounts.LoadConfig()
	if err != nil {
		return err
	}
	consumer := poller.New(config, repository.New(db), accounts.New(accountConfig), auditWriter)
	if once {
		return consumer.Poll(ctx)
	}
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
