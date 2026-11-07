// Package documents keeps clients' identity documents in S3 (ADR-0041).
// The bytes it stores are already encrypted with the client's data key; the
// bucket's default SSE-KMS encryption adds a second layer.
package documents

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	s3types "github.com/aws/aws-sdk-go-v2/service/s3/types"
)

// MaxSize is the largest document accepted, in bytes (ADR-0041 decision 2).
const MaxSize = 5 << 20

// sealOverhead allows for the nonce and tag the data key adds when sealing.
const sealOverhead = 1 << 10

// ErrObjectNotFound means no object is stored under the key.
var ErrObjectNotFound = errors.New("document object not found")

// Store holds the sealed documents.
type Store interface {
	Put(ctx context.Context, key string, sealed []byte) error
	Get(ctx context.Context, key string) ([]byte, error)
}

// ObjectKey is where a client's document is stored. It holds IDs only, never
// personal data.
func ObjectKey(clientID, documentID string) string {
	return "clients/" + clientID + "/" + documentID
}

// S3API is the part of the S3 client the store uses.
type S3API interface {
	PutObject(ctx context.Context, params *s3.PutObjectInput, optFns ...func(*s3.Options)) (*s3.PutObjectOutput, error)
	GetObject(ctx context.Context, params *s3.GetObjectInput, optFns ...func(*s3.Options)) (*s3.GetObjectOutput, error)
}

type s3Store struct {
	client S3API
	bucket string
}

// NewS3 stores documents in bucket. It sets no encryption headers, so the
// bucket's default SSE-KMS key applies.
func NewS3(client S3API, bucket string) Store {
	return s3Store{client: client, bucket: bucket}
}

func (s s3Store) Put(ctx context.Context, key string, sealed []byte) error {
	_, err := s.client.PutObject(ctx, &s3.PutObjectInput{
		Bucket:        aws.String(s.bucket),
		Key:           aws.String(key),
		Body:          bytes.NewReader(sealed),
		ContentLength: aws.Int64(int64(len(sealed))),
		ContentType:   aws.String("application/octet-stream"),
	})
	if err != nil {
		return fmt.Errorf("put document object: %w", err)
	}
	return nil
}

func (s s3Store) Get(ctx context.Context, key string) ([]byte, error) {
	out, err := s.client.GetObject(ctx, &s3.GetObjectInput{
		Bucket: aws.String(s.bucket),
		Key:    aws.String(key),
	})
	var noSuchKey *s3types.NoSuchKey
	if errors.As(err, &noSuchKey) {
		return nil, ErrObjectNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("get document object: %w", err)
	}
	defer out.Body.Close()
	sealed, err := io.ReadAll(io.LimitReader(out.Body, MaxSize+sealOverhead+1))
	if err != nil {
		return nil, fmt.Errorf("read document object: %w", err)
	}
	if len(sealed) > MaxSize+sealOverhead {
		return nil, fmt.Errorf("document object %s is larger than any accepted document", key)
	}
	return sealed, nil
}
