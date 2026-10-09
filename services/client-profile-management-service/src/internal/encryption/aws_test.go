package encryption

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	dynamotypes "github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	"github.com/aws/aws-sdk-go-v2/service/kms"
	kmstypes "github.com/aws/aws-sdk-go-v2/service/kms/types"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// fakeKMS wraps a data key by prefixing it with its encryption context, and
// refuses to unwrap it under any other context, as KMS does.
type fakeKMS struct {
	generated []*kms.GenerateDataKeyInput
	decrypts  int
	err       error
}

func (f *fakeKMS) GenerateDataKey(_ context.Context, in *kms.GenerateDataKeyInput, _ ...func(*kms.Options)) (*kms.GenerateDataKeyOutput, error) {
	if f.err != nil {
		return nil, f.err
	}
	f.generated = append(f.generated, in)
	plaintext := make([]byte, 32)
	_, _ = rand.Read(plaintext)
	wrapped := append([]byte(in.EncryptionContext[encryptionContextKey]+":"), plaintext...)
	return &kms.GenerateDataKeyOutput{Plaintext: plaintext, CiphertextBlob: wrapped}, nil
}

func (f *fakeKMS) Decrypt(_ context.Context, in *kms.DecryptInput, _ ...func(*kms.Options)) (*kms.DecryptOutput, error) {
	f.decrypts++
	prefix := []byte(in.EncryptionContext[encryptionContextKey] + ":")
	if !bytes.HasPrefix(in.CiphertextBlob, prefix) {
		return nil, errors.New("InvalidCiphertextException")
	}
	return &kms.DecryptOutput{Plaintext: in.CiphertextBlob[len(prefix):]}, nil
}

func (f *fakeKMS) GenerateMac(_ context.Context, in *kms.GenerateMacInput, _ ...func(*kms.Options)) (*kms.GenerateMacOutput, error) {
	if in.MacAlgorithm != kmstypes.MacAlgorithmSpecHmacSha256 {
		return nil, errors.New("unexpected MAC algorithm")
	}
	mac := hmac.New(sha256.New, []byte(aws.ToString(in.KeyId)))
	mac.Write(in.Message)
	return &kms.GenerateMacOutput{Mac: mac.Sum(nil)}, nil
}

type fakeDynamo struct {
	items map[string][]byte
	puts  []*dynamodb.PutItemInput
}

func newFakeDynamo() *fakeDynamo { return &fakeDynamo{items: map[string][]byte{}} }

func (f *fakeDynamo) PutItem(_ context.Context, in *dynamodb.PutItemInput, _ ...func(*dynamodb.Options)) (*dynamodb.PutItemOutput, error) {
	f.puts = append(f.puts, in)
	id := in.Item[clientIDAttribute].(*dynamotypes.AttributeValueMemberS).Value
	if _, ok := f.items[id]; ok && aws.ToString(in.ConditionExpression) == "attribute_not_exists(client_id)" {
		return nil, &dynamotypes.ConditionalCheckFailedException{}
	}
	f.items[id] = in.Item[wrappedKeyAttribute].(*dynamotypes.AttributeValueMemberB).Value
	return &dynamodb.PutItemOutput{}, nil
}

func (f *fakeDynamo) GetItem(_ context.Context, in *dynamodb.GetItemInput, _ ...func(*dynamodb.Options)) (*dynamodb.GetItemOutput, error) {
	id := in.Key[clientIDAttribute].(*dynamotypes.AttributeValueMemberS).Value
	wrapped, ok := f.items[id]
	if !ok {
		return &dynamodb.GetItemOutput{}, nil
	}
	return &dynamodb.GetItemOutput{Item: map[string]dynamotypes.AttributeValue{
		clientIDAttribute:   &dynamotypes.AttributeValueMemberS{Value: id},
		wrappedKeyAttribute: &dynamotypes.AttributeValueMemberB{Value: wrapped},
	}}, nil
}

func (f *fakeDynamo) DeleteItem(_ context.Context, in *dynamodb.DeleteItemInput, _ ...func(*dynamodb.Options)) (*dynamodb.DeleteItemOutput, error) {
	delete(f.items, in.Key[clientIDAttribute].(*dynamotypes.AttributeValueMemberS).Value)
	return &dynamodb.DeleteItemOutput{}, nil
}

func newTestProtector(kmsClient *fakeKMS, dynamo *fakeDynamo, now func() time.Time) *awsProtector {
	return newAWSProtector(kmsClient, dynamo, "client-keys", "alias/client-data", "alias/client-blind-index", now)
}

func TestNewKeyStoresOnlyTheWrappedKey(t *testing.T) {
	kmsClient, dynamo := &fakeKMS{}, newFakeDynamo()
	protector := newTestProtector(kmsClient, dynamo, time.Now)

	key, err := protector.NewKey(context.Background(), "client-1")
	require.NoError(t, err)

	require.Len(t, kmsClient.generated, 1)
	assert.Equal(t, kmstypes.DataKeySpecAes256, kmsClient.generated[0].KeySpec)
	assert.Equal(t, "alias/client-data", aws.ToString(kmsClient.generated[0].KeyId))
	assert.Equal(t, map[string]string{"client_id": "client-1"}, kmsClient.generated[0].EncryptionContext)

	require.Len(t, dynamo.puts, 1)
	assert.Equal(t, "client-keys", aws.ToString(dynamo.puts[0].TableName))
	assert.Equal(t, "attribute_not_exists(client_id)", aws.ToString(dynamo.puts[0].ConditionExpression))
	assert.True(t, bytes.HasPrefix(dynamo.items["client-1"], []byte("client-1:")), "the wrapped key, not the plaintext, is stored")

	ciphertext, err := key.Encrypt("email", "a@example.test")
	require.NoError(t, err)
	// A protector with an empty cache unwraps the stored key and reads the value.
	stored, err := newTestProtector(kmsClient, dynamo, time.Now).Key(context.Background(), "client-1")
	require.NoError(t, err)
	plaintext, err := stored.Decrypt("email", ciphertext)
	require.NoError(t, err)
	assert.Equal(t, "a@example.test", plaintext)
}

func TestNewKeyRefusesToReplaceAStoredKey(t *testing.T) {
	protector := newTestProtector(&fakeKMS{}, newFakeDynamo(), time.Now)
	_, err := protector.NewKey(context.Background(), "client-1")
	require.NoError(t, err)

	_, err = protector.NewKey(context.Background(), "client-1")
	assert.ErrorIs(t, err, ErrKeyExists)
}

func TestNewKeyReportsAKMSFailure(t *testing.T) {
	dynamo := newFakeDynamo()
	protector := newTestProtector(&fakeKMS{err: errors.New("kms unavailable")}, dynamo, time.Now)

	_, err := protector.NewKey(context.Background(), "client-1")
	assert.Error(t, err)
	assert.Empty(t, dynamo.puts)
}

func TestKeyIsCachedUntilItExpires(t *testing.T) {
	now := time.Date(2026, 10, 9, 0, 0, 0, 0, time.UTC)
	kmsClient, dynamo := &fakeKMS{}, newFakeDynamo()
	_, err := newTestProtector(kmsClient, dynamo, time.Now).NewKey(context.Background(), "client-1")
	require.NoError(t, err)
	protector := newTestProtector(kmsClient, dynamo, func() time.Time { return now })

	for range 3 {
		_, err := protector.Key(context.Background(), "client-1")
		require.NoError(t, err)
	}
	assert.Equal(t, 1, kmsClient.decrypts, "later reads use the cached key")

	now = now.Add(keyCacheTTL)
	_, err = protector.Key(context.Background(), "client-1")
	require.NoError(t, err)
	assert.Equal(t, 2, kmsClient.decrypts, "an expired key is unwrapped again")
}

func TestKeyForAClientWithNoKey(t *testing.T) {
	_, err := newTestProtector(&fakeKMS{}, newFakeDynamo(), time.Now).Key(context.Background(), "client-1")
	assert.ErrorIs(t, err, ErrKeyNotFound)
}

func TestKeyIsUnwrappedOnlyForItsClient(t *testing.T) {
	kmsClient, dynamo := &fakeKMS{}, newFakeDynamo()
	_, err := newTestProtector(kmsClient, dynamo, time.Now).NewKey(context.Background(), "client-1")
	require.NoError(t, err)
	// Someone with table access copies client-1's wrapped key to client-2.
	dynamo.items["client-2"] = dynamo.items["client-1"]

	_, err = newTestProtector(kmsClient, dynamo, time.Now).Key(context.Background(), "client-2")
	assert.Error(t, err)
}

func TestDeleteKeyRemovesTheStoredAndCachedKey(t *testing.T) {
	protector := newTestProtector(&fakeKMS{}, newFakeDynamo(), time.Now)
	_, err := protector.NewKey(context.Background(), "client-1")
	require.NoError(t, err)

	require.NoError(t, protector.DeleteKey(context.Background(), "client-1"))
	_, err = protector.Key(context.Background(), "client-1")
	assert.ErrorIs(t, err, ErrKeyNotFound)
}

func TestBlindIndex(t *testing.T) {
	protector := newTestProtector(&fakeKMS{}, newFakeDynamo(), time.Now)

	first, err := protector.BlindIndex(context.Background(), "alice@example.test")
	require.NoError(t, err)
	second, err := protector.BlindIndex(context.Background(), "alice@example.test")
	require.NoError(t, err)
	assert.Equal(t, first, second, "the same value always gives the same index")
	assert.Len(t, first, 64)
	_, err = hex.DecodeString(first)
	assert.NoError(t, err)

	other, err := protector.BlindIndex(context.Background(), "bob@example.test")
	require.NoError(t, err)
	assert.NotEqual(t, first, other)

	_, err = protector.BlindIndex(context.Background(), "")
	assert.Error(t, err)
}
