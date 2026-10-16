package encryption

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"os"
	"testing"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	dynamotypes "github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	"github.com/aws/aws-sdk-go-v2/service/kms"
	kmstypes "github.com/aws/aws-sdk-go-v2/service/kms/types"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// TestAWSProtectorAgainstLocalStack runs the real KMS and DynamoDB calls
// against LocalStack. It creates its own keys and table, and is skipped unless
// ENCRYPTION_TEST_ENDPOINT is set (docker-compose-test.yaml sets it).
func TestAWSProtectorAgainstLocalStack(t *testing.T) {
	endpoint := os.Getenv("ENCRYPTION_TEST_ENDPOINT")
	if endpoint == "" {
		t.Skip("ENCRYPTION_TEST_ENDPOINT is not set")
	}
	ctx := context.Background()
	cfg, err := awsconfig.LoadDefaultConfig(ctx,
		awsconfig.WithRegion("us-east-1"),
		awsconfig.WithCredentialsProvider(credentials.NewStaticCredentialsProvider("test", "test", "")),
	)
	require.NoError(t, err)
	kmsClient := kms.NewFromConfig(cfg, func(o *kms.Options) { o.BaseEndpoint = aws.String(endpoint) })
	dynamoClient := dynamodb.NewFromConfig(cfg, func(o *dynamodb.Options) { o.BaseEndpoint = aws.String(endpoint) })

	dataKey, err := kmsClient.CreateKey(ctx, &kms.CreateKeyInput{})
	require.NoError(t, err)
	indexKey, err := kmsClient.CreateKey(ctx, &kms.CreateKeyInput{
		KeySpec:  kmstypes.KeySpecHmac256,
		KeyUsage: kmstypes.KeyUsageTypeGenerateVerifyMac,
	})
	require.NoError(t, err)
	table := "client-keys-test-" + randomSuffix(t)
	_, err = dynamoClient.CreateTable(ctx, &dynamodb.CreateTableInput{
		TableName:            aws.String(table),
		AttributeDefinitions: []dynamotypes.AttributeDefinition{{AttributeName: aws.String("client_id"), AttributeType: dynamotypes.ScalarAttributeTypeS}},
		KeySchema:            []dynamotypes.KeySchemaElement{{AttributeName: aws.String("client_id"), KeyType: dynamotypes.KeyTypeHash}},
		BillingMode:          dynamotypes.BillingModePayPerRequest,
	})
	require.NoError(t, err)
	t.Cleanup(func() { _, _ = dynamoClient.DeleteTable(ctx, &dynamodb.DeleteTableInput{TableName: aws.String(table)}) })

	newProtector := func() Protector {
		return NewAWS(kmsClient, dynamoClient, table, aws.ToString(dataKey.KeyMetadata.KeyId), aws.ToString(indexKey.KeyMetadata.KeyId))
	}
	clientID := "client-" + randomSuffix(t)

	created, err := newProtector().NewKey(ctx, clientID)
	require.NoError(t, err)
	ciphertext, err := created.Encrypt("email", "a@example.test")
	require.NoError(t, err)

	_, err = newProtector().NewKey(ctx, clientID)
	assert.ErrorIs(t, err, ErrKeyExists)

	stored, err := newProtector().Key(ctx, clientID)
	require.NoError(t, err)
	plaintext, err := stored.Decrypt("email", ciphertext)
	require.NoError(t, err)
	assert.Equal(t, "a@example.test", plaintext)

	first, err := newProtector().BlindIndex(ctx, "a@example.test")
	require.NoError(t, err)
	second, err := newProtector().BlindIndex(ctx, "a@example.test")
	require.NoError(t, err)
	assert.Equal(t, first, second)
	assert.Len(t, first, 64)

	protector := newProtector()
	require.NoError(t, protector.DeleteKey(ctx, clientID))
	_, err = protector.Key(ctx, clientID)
	assert.ErrorIs(t, err, ErrKeyNotFound)
}

func randomSuffix(t *testing.T) string {
	t.Helper()
	b := make([]byte, 4)
	_, err := rand.Read(b)
	require.NoError(t, err)
	return hex.EncodeToString(b)
}
