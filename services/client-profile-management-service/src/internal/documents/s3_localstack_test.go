package documents

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"os"
	"testing"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// TestS3StoreAgainstLocalStack runs the real S3 calls against LocalStack. It
// creates its own bucket, and is skipped unless DOCUMENTS_TEST_ENDPOINT is set
// (docker-compose-test.yaml sets it).
func TestS3StoreAgainstLocalStack(t *testing.T) {
	endpoint := os.Getenv("DOCUMENTS_TEST_ENDPOINT")
	if endpoint == "" {
		t.Skip("DOCUMENTS_TEST_ENDPOINT is not set")
	}
	ctx := context.Background()
	cfg, err := awsconfig.LoadDefaultConfig(ctx,
		awsconfig.WithRegion("us-east-1"),
		awsconfig.WithCredentialsProvider(credentials.NewStaticCredentialsProvider("test", "test", "")),
	)
	require.NoError(t, err)
	client := s3.NewFromConfig(cfg, func(o *s3.Options) {
		o.BaseEndpoint = aws.String(endpoint)
		o.UsePathStyle = true
	})
	bucket := "client-documents-test-" + randomSuffix(t)
	_, err = client.CreateBucket(ctx, &s3.CreateBucketInput{Bucket: aws.String(bucket)})
	require.NoError(t, err)
	t.Cleanup(func() {
		_, _ = client.DeleteObject(ctx, &s3.DeleteObjectInput{Bucket: aws.String(bucket), Key: aws.String(ObjectKey("client-1", "doc-1"))})
		_, _ = client.DeleteBucket(ctx, &s3.DeleteBucketInput{Bucket: aws.String(bucket)})
	})
	store := NewS3(client, bucket)
	sealed := []byte("sealed synthetic document bytes")

	require.NoError(t, store.Put(ctx, ObjectKey("client-1", "doc-1"), sealed))
	got, err := store.Get(ctx, ObjectKey("client-1", "doc-1"))
	require.NoError(t, err)
	assert.Equal(t, sealed, got)

	_, err = store.Get(ctx, ObjectKey("client-1", "doc-2"))
	assert.ErrorIs(t, err, ErrObjectNotFound)
}

func randomSuffix(t *testing.T) string {
	t.Helper()
	b := make([]byte, 4)
	_, err := rand.Read(b)
	require.NoError(t, err)
	return hex.EncodeToString(b)
}
