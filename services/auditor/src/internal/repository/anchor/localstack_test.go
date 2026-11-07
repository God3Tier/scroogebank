package anchor

import (
	"context"
	"fmt"
	"os"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/aws-sdk-go-v2/service/s3/types"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// TestAgainstLocalStack writes anchors to a real S3 API with Object Lock.
// make test points CHAIN_ANCHOR_TEST_ENDPOINT at the LocalStack container in
// docker-compose.test.yml; without it the test is skipped. LocalStack checks
// no credentials, so the key is a placeholder, not a secret. Unlike S3, it
// also accepts a lock on a bucket without Object Lock, so that refusal is
// not tested here.
func TestAgainstLocalStack(t *testing.T) {
	endpoint := os.Getenv("CHAIN_ANCHOR_TEST_ENDPOINT")
	if endpoint == "" {
		t.Skip("CHAIN_ANCHOR_TEST_ENDPOINT is not set")
	}
	ctx := context.Background()
	client := s3.New(s3.Options{
		Region:       "us-east-1",
		BaseEndpoint: aws.String(endpoint),
		UsePathStyle: true,
		Credentials: aws.CredentialsProviderFunc(func(context.Context) (aws.Credentials, error) {
			return aws.Credentials{AccessKeyID: "test", SecretAccessKey: "test"}, nil
		}),
	})
	bucket := fmt.Sprintf("chain-anchors-%d", time.Now().UnixNano())
	_, err := client.CreateBucket(ctx, &s3.CreateBucketInput{
		Bucket:                     aws.String(bucket),
		ObjectLockEnabledForBucket: aws.Bool(true),
	})
	require.NoError(t, err)

	repository := New(client, bucket)
	latest, err := repository.Latest(ctx)
	require.NoError(t, err)
	assert.Nil(t, latest)

	now := time.Now().UTC().Truncate(time.Microsecond)
	older, newer := anchorAt(4, now.Add(-time.Hour)), anchorAt(5, now)
	require.NoError(t, repository.Write(ctx, older))
	require.NoError(t, repository.Write(ctx, newer))

	latest, err = repository.Latest(ctx)
	require.NoError(t, err)
	require.NotNil(t, latest)
	assert.Equal(t, newer.SequenceNo, latest.SequenceNo)
	assert.Equal(t, newer.Hash, latest.Hash)

	retention, err := client.GetObjectRetention(ctx, &s3.GetObjectRetentionInput{
		Bucket: aws.String(bucket),
		Key:    aws.String(key(newer.AnchoredAt)),
	})
	require.NoError(t, err)
	assert.Equal(t, types.ObjectLockRetentionModeGovernance, retention.Retention.Mode)
	assert.WithinDuration(t, newer.AnchoredAt.Add(Retention), aws.ToTime(retention.Retention.RetainUntilDate), time.Second)

	// Deleting the locked version is refused.
	head, err := client.HeadObject(ctx, &s3.HeadObjectInput{
		Bucket: aws.String(bucket),
		Key:    aws.String(key(newer.AnchoredAt)),
	})
	require.NoError(t, err)
	_, err = client.DeleteObject(ctx, &s3.DeleteObjectInput{
		Bucket:    aws.String(bucket),
		Key:       aws.String(key(newer.AnchoredAt)),
		VersionId: head.VersionId,
	})
	assert.Error(t, err, "delete a locked anchor")
}
