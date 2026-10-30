package main

import (
	"context"
	"database/sql"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"

	"receiver/src/cronjob/accounts"
	"receiver/src/cronjob/poller"
	auditsqs "receiver/src/cronjob/sqs"
	"receiver/src/internal/outbox"
	"receiver/src/internal/repository"

	"github.com/aws/aws-lambda-go/lambda"
	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/secretsmanager"
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

	awsConfig := sync.OnceValues(func() (aws.Config, error) {
		region := os.Getenv("AWS_REGION")
		if region == "" {
			return aws.Config{}, fmt.Errorf("AWS_REGION is required to use Secrets Manager or the audit queue")
		}
		cfg, err := awsconfig.LoadDefaultConfig(ctx, awsconfig.WithRegion(region))
		if err != nil {
			return aws.Config{}, fmt.Errorf("load AWS configuration: %w", err)
		}
		return cfg, nil
	})

	secrets, err := loadSecrets(ctx, func() (SecretReader, error) {
		cfg, err := awsConfig()
		if err != nil {
			return nil, err
		}
		return secretsmanager.NewFromConfig(cfg, func(options *secretsmanager.Options) {
			if endpoint := os.Getenv("AWS_ENDPOINT_URL"); endpoint != "" {
				options.BaseEndpoint = aws.String(endpoint)
			}
		}), nil
	})
	if err != nil {
		return err
	}

	db, err := sql.Open("postgres", databaseDSN(secrets))
	if err != nil {
		return fmt.Errorf("open database: %w", err)
	}
	defer db.Close()

	if err := db.PingContext(ctx); err != nil {
		return fmt.Errorf("connect to database: %w", err)
	}

	config, err := poller.LoadConfig(secrets.sftpPrivateKey)
	if err != nil {
		return err
	}
	// Audit events are saved to audit_outbox with each import and sent to the
	// Auditor's queue at the end of every poll (ADR-0020 decision 3).
	var audit []poller.Audit
	if queueURL := os.Getenv("AUDIT_QUEUE_URL"); queueURL != "" {
		awsCfg, err := awsConfig()
		if err != nil {
			return err
		}
		client := awssqs.NewFromConfig(awsCfg, func(options *awssqs.Options) {
			if endpoint := os.Getenv("AWS_ENDPOINT_URL"); endpoint != "" {
				options.BaseEndpoint = aws.String(endpoint)
			}
		})
		audit = append(audit, poller.Audit{Events: auditsqs.Events{}, Relay: outbox.NewRelay(db, client, queueURL, outbox.IngestTable)})
		log.Printf("transaction audit events enabled for queue %s", queueURL)
	} else {
		log.Printf("transaction audit events disabled: AUDIT_QUEUE_URL is empty")
	}
	accountConfig, err := accounts.LoadConfig(secrets.ingestClientID, secrets.ingestClientSecret)
	if err != nil {
		return err
	}
	consumer := poller.New(config, repository.NewImport(db), accounts.New(accountConfig), audit...)

	// Lambda sets AWS_LAMBDA_RUNTIME_API. Each invocation is one poll, with
	// the invocation's deadline (ADR-0024 decision 7). The poller is built
	// once per execution environment, so its circuit breaker and failure
	// episodes carry over between invocations (decisions 3 and 5). A failed
	// poll fails the invocation, which the ingest error alarm counts.
	if os.Getenv("AWS_LAMBDA_RUNTIME_API") != "" {
		lambda.StartWithOptions(consumer.Poll, lambda.WithContext(ctx))
		return nil
	}
	if once {
		return consumer.Poll(ctx)
	}
	return consumer.Run(ctx, config.Interval)
}

func databaseDSN(s secrets) string {
	user, password := s.dbUser, s.dbPassword
	if user == "" {
		user, password = envOrDefault("DB_USER", "crm"), envOrDefault("DB_PASSWORD", "crm")
	}
	dsn := fmt.Sprintf(
		"host=%s port=%s user=%s password=%s dbname=%s sslmode=%s",
		envOrDefault("DB_HOST", "db"),
		envOrDefault("DB_PORT", "5432"),
		quoteDSNValue(user),
		quoteDSNValue(password),
		envOrDefault("DB_NAME", "crm"),
		envOrDefault("DB_SSLMODE", "disable"),
	)
	if rootCert := os.Getenv("DB_SSLROOTCERT"); rootCert != "" {
		dsn += " sslrootcert=" + quoteDSNValue(rootCert)
	}
	return dsn
}

// quoteDSNValue quotes a connection string value, so one with spaces or
// quotes, such as an RDS-generated password, reaches the server unchanged.
func quoteDSNValue(value string) string {
	return "'" + strings.NewReplacer(`\`, `\\`, `'`, `\'`).Replace(value) + "'"
}

func envOrDefault(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}
