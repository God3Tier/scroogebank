// chain-verify checks the whole audit chain once and exits (ADR-0022
// decision 3). EventBridge Scheduler starts it daily as its own ECS task, from
// the auditor's image. It reads audit-db directly, never writes to audit_log,
// and records each problem it finds in corrupted_audit_log. It checks the
// chain against the latest anchor in the S3 Object Lock bucket, then anchors
// the current head there.
//
// It exits with status 0 when it recorded no new finding, 1 when the check
// could not finish, and 2 when it recorded a new finding.
//
// Like the server in src/serverd, it reads the database settings from DB_NAME,
// HOST, USER, PASSWORD and SSLMODE (src/internal/bootstrap). In AWS it logs in
// as its own user, which holds only the auditor_chain_verify role. The bucket
// is CHAIN_ANCHOR_BUCKET; AWS_REGION and, for LocalStack only,
// AWS_ENDPOINT_URL set up the S3 client.
package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"

	"auditor/src/internal/bootstrap"
	"auditor/src/internal/repository"
	"auditor/src/internal/repository/anchor"
	auditSvc "auditor/src/internal/service/audit"
	corruptedLogSvc "auditor/src/internal/service/corrupted_logs"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/s3"
)

const (
	exitOK          = 0
	exitFailed      = 1
	exitNewFindings = 2
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	code := run(ctx)
	stop()
	os.Exit(code)
}

func run(ctx context.Context) int {
	// Without the anchor a rewrite of the whole chain goes unnoticed, so
	// chain-verify does not run without one.
	bucket := os.Getenv("CHAIN_ANCHOR_BUCKET")
	if bucket == "" {
		log.Printf("chain-verify: CHAIN_ANCHOR_BUCKET is not set")
		return exitFailed
	}
	s3Client, err := newS3Client(ctx)
	if err != nil {
		log.Printf("chain-verify: %v", err)
		return exitFailed
	}

	db, err := bootstrap.InitialiseDB()
	if err != nil {
		log.Printf("chain-verify: open audit-db: %v", err)
		return exitFailed
	}
	defer db.Close()

	registry := repository.New(db)
	return verify(ctx,
		auditSvc.New(registry.GetAudit()),
		corruptedLogSvc.New(registry.GetCorruptedLog()),
		anchor.New(s3Client, bucket))
}

// newS3Client builds an S3 client from the AWS SDK's default credential
// chain: the task's IAM role in AWS, or environment variables locally. When
// AWS_ENDPOINT_URL is set, requests go there instead, with the bucket in the
// path, as LocalStack serves it.
func newS3Client(ctx context.Context) (*s3.Client, error) {
	cfg, err := awsconfig.LoadDefaultConfig(ctx, awsconfig.WithRegion(os.Getenv("AWS_REGION")))
	if err != nil {
		return nil, fmt.Errorf("load AWS config: %w", err)
	}

	return s3.NewFromConfig(cfg, func(o *s3.Options) {
		if endpoint := os.Getenv("AWS_ENDPOINT_URL"); endpoint != "" {
			o.BaseEndpoint = aws.String(endpoint)
			o.UsePathStyle = true
		}
	}), nil
}
