package encryption

import (
	"context"
	"encoding/hex"
	"errors"
	"fmt"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	dynamotypes "github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	"github.com/aws/aws-sdk-go-v2/service/kms"
	kmstypes "github.com/aws/aws-sdk-go-v2/service/kms/types"
)

// KMSAPI is the part of the KMS client the protector uses.
type KMSAPI interface {
	GenerateDataKey(ctx context.Context, params *kms.GenerateDataKeyInput, optFns ...func(*kms.Options)) (*kms.GenerateDataKeyOutput, error)
	Decrypt(ctx context.Context, params *kms.DecryptInput, optFns ...func(*kms.Options)) (*kms.DecryptOutput, error)
	GenerateMac(ctx context.Context, params *kms.GenerateMacInput, optFns ...func(*kms.Options)) (*kms.GenerateMacOutput, error)
}

// DynamoDBAPI is the part of the DynamoDB client the protector uses.
type DynamoDBAPI interface {
	PutItem(ctx context.Context, params *dynamodb.PutItemInput, optFns ...func(*dynamodb.Options)) (*dynamodb.PutItemOutput, error)
	GetItem(ctx context.Context, params *dynamodb.GetItemInput, optFns ...func(*dynamodb.Options)) (*dynamodb.GetItemOutput, error)
	DeleteItem(ctx context.Context, params *dynamodb.DeleteItemInput, optFns ...func(*dynamodb.Options)) (*dynamodb.DeleteItemOutput, error)
}

const (
	// The client-keys table is keyed by client UUID and holds only the
	// KMS-wrapped data key.
	clientIDAttribute   = "client_id"
	wrappedKeyAttribute = "wrapped_key"
	// encryptionContextKey binds each wrapped key to its client, so KMS
	// refuses to unwrap it for any other client.
	encryptionContextKey = "client_id"
)

type awsProtector struct {
	kms        KMSAPI
	dynamo     DynamoDBAPI
	table      string
	dataKeyID  string
	indexKeyID string
	cache      *keyCache
}

// NewAWS returns the Protector backed by KMS and the DynamoDB client-keys
// table. dataKeyID is the symmetric KMS key that wraps data keys; indexKeyID
// is the shared HMAC KMS key for blind indexes. Either may be a key ID, key
// ARN or alias.
func NewAWS(kmsClient KMSAPI, dynamoClient DynamoDBAPI, table, dataKeyID, indexKeyID string) Protector {
	return newAWSProtector(kmsClient, dynamoClient, table, dataKeyID, indexKeyID, time.Now)
}

func newAWSProtector(kmsClient KMSAPI, dynamoClient DynamoDBAPI, table, dataKeyID, indexKeyID string, now func() time.Time) *awsProtector {
	return &awsProtector{
		kms:        kmsClient,
		dynamo:     dynamoClient,
		table:      table,
		dataKeyID:  dataKeyID,
		indexKeyID: indexKeyID,
		cache:      newKeyCache(keyCacheTTL, keyCacheSize, now),
	}
}

// NewKey generates a data key with KMS and stores only its wrapped form. The
// put refuses to overwrite an existing key, which would make that client's
// data unreadable.
func (p *awsProtector) NewKey(ctx context.Context, clientID string) (DataKey, error) {
	generated, err := p.kms.GenerateDataKey(ctx, &kms.GenerateDataKeyInput{
		KeyId:             aws.String(p.dataKeyID),
		KeySpec:           kmstypes.DataKeySpecAes256,
		EncryptionContext: encryptionContext(clientID),
	})
	if err != nil {
		return DataKey{}, fmt.Errorf("generate data key: %w", err)
	}
	_, err = p.dynamo.PutItem(ctx, &dynamodb.PutItemInput{
		TableName: aws.String(p.table),
		Item: map[string]dynamotypes.AttributeValue{
			clientIDAttribute:   &dynamotypes.AttributeValueMemberS{Value: clientID},
			wrappedKeyAttribute: &dynamotypes.AttributeValueMemberB{Value: generated.CiphertextBlob},
		},
		ConditionExpression: aws.String("attribute_not_exists(" + clientIDAttribute + ")"),
	})
	var exists *dynamotypes.ConditionalCheckFailedException
	if errors.As(err, &exists) {
		return DataKey{}, ErrKeyExists
	}
	if err != nil {
		return DataKey{}, fmt.Errorf("store data key: %w", err)
	}
	key, err := NewDataKey(clientID, generated.Plaintext)
	if err != nil {
		return DataKey{}, err
	}
	p.cache.put(clientID, key)
	return key, nil
}

// Key returns the client's data key from the cache, or unwraps it with KMS.
func (p *awsProtector) Key(ctx context.Context, clientID string) (DataKey, error) {
	if key, ok := p.cache.get(clientID); ok {
		return key, nil
	}
	item, err := p.dynamo.GetItem(ctx, &dynamodb.GetItemInput{
		TableName:      aws.String(p.table),
		Key:            itemKey(clientID),
		ConsistentRead: aws.Bool(true),
	})
	if err != nil {
		return DataKey{}, fmt.Errorf("read data key: %w", err)
	}
	wrapped, ok := item.Item[wrappedKeyAttribute].(*dynamotypes.AttributeValueMemberB)
	if !ok {
		return DataKey{}, ErrKeyNotFound
	}
	unwrapped, err := p.kms.Decrypt(ctx, &kms.DecryptInput{
		CiphertextBlob:    wrapped.Value,
		KeyId:             aws.String(p.dataKeyID),
		EncryptionContext: encryptionContext(clientID),
	})
	if err != nil {
		return DataKey{}, fmt.Errorf("unwrap data key: %w", err)
	}
	key, err := NewDataKey(clientID, unwrapped.Plaintext)
	if err != nil {
		return DataKey{}, err
	}
	p.cache.put(clientID, key)
	return key, nil
}

func (p *awsProtector) DeleteKey(ctx context.Context, clientID string) error {
	p.cache.remove(clientID)
	if _, err := p.dynamo.DeleteItem(ctx, &dynamodb.DeleteItemInput{
		TableName: aws.String(p.table),
		Key:       itemKey(clientID),
	}); err != nil {
		return fmt.Errorf("delete data key: %w", err)
	}
	return nil
}

// BlindIndex returns HMAC-SHA-256 of the value under the shared HMAC key, hex
// encoded. KMS computes it, so the key never leaves KMS.
func (p *awsProtector) BlindIndex(ctx context.Context, normalized string) (string, error) {
	if normalized == "" {
		return "", errors.New("blind index of an empty value")
	}
	mac, err := p.kms.GenerateMac(ctx, &kms.GenerateMacInput{
		KeyId:        aws.String(p.indexKeyID),
		MacAlgorithm: kmstypes.MacAlgorithmSpecHmacSha256,
		Message:      []byte(normalized),
	})
	if err != nil {
		return "", fmt.Errorf("compute blind index: %w", err)
	}
	return hex.EncodeToString(mac.Mac), nil
}

func encryptionContext(clientID string) map[string]string {
	return map[string]string{encryptionContextKey: clientID}
}

func itemKey(clientID string) map[string]dynamotypes.AttributeValue {
	return map[string]dynamotypes.AttributeValue{
		clientIDAttribute: &dynamotypes.AttributeValueMemberS{Value: clientID},
	}
}
