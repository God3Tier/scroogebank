// Package anchor keeps the audit chain's anchors in the S3 Object Lock bucket
// (ADR-0022 decision 3). Each run of chain-verify writes the chain head as a
// new object, locked in governance mode, so nobody with database access can
// change the history an anchor stands for without the next check noticing.
package anchor

import (
	"context"
	"time"

	"auditor/src/internal/models"

	"github.com/aws/aws-sdk-go-v2/service/s3"
)

// Retention is how long each anchor stays locked. A week outlasts several
// missed daily runs, and is short enough to tear the project down.
const Retention = 7 * 24 * time.Hour

// keyPrefix holds every anchor. A key ends in the time its anchor was written,
// in a fixed-width UTC form, so keys sort in the order they were written.
const (
	keyPrefix     = "chain-head/"
	keyTimeLayout = "20060102T150405.000000Z"
)

type Repository interface {
	// Latest returns the anchor written most recently, or nil when there is
	// none yet.
	Latest(ctx context.Context) (*models.ChainAnchor, error)
	// Write stores anchor as a new object, locked until its AnchoredAt plus
	// Retention.
	Write(ctx context.Context, anchor models.ChainAnchor) error
}

// S3API is the part of the S3 client the anchors need, so tests can stand in
// for it.
type S3API interface {
	PutObject(ctx context.Context, params *s3.PutObjectInput, optFns ...func(*s3.Options)) (*s3.PutObjectOutput, error)
	GetObject(ctx context.Context, params *s3.GetObjectInput, optFns ...func(*s3.Options)) (*s3.GetObjectOutput, error)
	ListObjectsV2(ctx context.Context, params *s3.ListObjectsV2Input, optFns ...func(*s3.Options)) (*s3.ListObjectsV2Output, error)
}

type impl struct {
	s3     S3API
	bucket string
}

func New(client S3API, bucket string) Repository {
	return impl{
		s3:     client,
		bucket: bucket,
	}
}

func key(anchoredAt time.Time) string {
	return keyPrefix + anchoredAt.UTC().Format(keyTimeLayout) + ".json"
}
