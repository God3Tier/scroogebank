package encryption

import (
	"bytes"
	"encoding/base64"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

var testKey = bytes.Repeat([]byte{7}, 32)

func mustDataKey(t *testing.T, clientID string) DataKey {
	t.Helper()
	key, err := NewDataKey(clientID, testKey)
	require.NoError(t, err)
	return key
}

func TestDataKeyRoundTrip(t *testing.T) {
	key := mustDataKey(t, "client-1")

	ciphertext, err := key.Encrypt("first_name", "synthetic name")
	require.NoError(t, err)
	assert.NotContains(t, ciphertext, "synthetic")

	plaintext, err := key.Decrypt("first_name", ciphertext)
	require.NoError(t, err)
	assert.Equal(t, "synthetic name", plaintext)
}

func TestDataKeyUsesAFreshNonceEachTime(t *testing.T) {
	key := mustDataKey(t, "client-1")

	first, err := key.Encrypt("email", "a@example.test")
	require.NoError(t, err)
	second, err := key.Encrypt("email", "a@example.test")
	require.NoError(t, err)
	assert.NotEqual(t, first, second)
}

func TestDataKeyRejectsValuesMovedOrChanged(t *testing.T) {
	key := mustDataKey(t, "client-1")
	ciphertext, err := key.Encrypt("first_name", "synthetic name")
	require.NoError(t, err)

	_, err = key.Decrypt("last_name", ciphertext)
	assert.Error(t, err, "a value moved to another field")

	_, err = mustDataKey(t, "client-2").Decrypt("first_name", ciphertext)
	assert.Error(t, err, "a value moved to another client with the same key bytes")

	sealed, err := base64.StdEncoding.DecodeString(ciphertext)
	require.NoError(t, err)
	sealed[len(sealed)-1] ^= 1
	_, err = key.Decrypt("first_name", base64.StdEncoding.EncodeToString(sealed))
	assert.Error(t, err, "a changed value")

	_, err = key.Decrypt("first_name", "AAAA")
	assert.Error(t, err, "a value shorter than its nonce")
}

func TestDataKeySealsBytesForOneClientAndField(t *testing.T) {
	key := mustDataKey(t, "client-1")
	document := []byte("\x89PNG\r\n\x1a\nsynthetic document")

	sealed, err := key.SealBytes("document/doc-1", document)
	require.NoError(t, err)
	assert.False(t, bytes.Contains(sealed, []byte("synthetic")), "the sealed bytes do not contain the document")

	opened, err := key.OpenBytes("document/doc-1", sealed)
	require.NoError(t, err)
	assert.Equal(t, document, opened)

	_, err = key.OpenBytes("document/doc-2", sealed)
	assert.Error(t, err, "bytes moved to another document")
	_, err = mustDataKey(t, "client-2").OpenBytes("document/doc-1", sealed)
	assert.Error(t, err, "bytes moved to another client with the same key bytes")
	_, err = key.OpenBytes("document/doc-1", sealed[:4])
	assert.Error(t, err, "bytes shorter than their nonce")
}

func TestNewDataKeyRequires32Bytes(t *testing.T) {
	_, err := NewDataKey("client-1", make([]byte, 16))
	assert.Error(t, err)
}

func TestNormalize(t *testing.T) {
	emails := map[string]string{
		"alice@example.test":      "alice@example.test",
		"  Alice@Example.TEST \t": "alice@example.test",
	}
	for in, want := range emails {
		assert.Equal(t, want, NormalizeEmail(in), in)
	}

	phones := map[string]string{
		"+6560000101":     "+6560000101",
		" +65 6000 0101 ": "+6560000101",
		"+65-6000-0101":   "+6560000101",
		"(+65) 6000.0101": "+6560000101",
		"60000101":        "60000101",
	}
	for in, want := range phones {
		assert.Equal(t, want, NormalizePhone(in), in)
	}
}

func TestKeyCacheExpiresAndEvicts(t *testing.T) {
	now := time.Date(2026, 10, 9, 0, 0, 0, 0, time.UTC)
	cache := newKeyCache(time.Minute, 2, func() time.Time { return now })
	key := mustDataKey(t, "client-1")

	cache.put("client-1", key)
	_, ok := cache.get("client-1")
	assert.True(t, ok)

	now = now.Add(time.Minute)
	_, ok = cache.get("client-1")
	assert.False(t, ok, "expired after the TTL")

	cache.put("a", key)
	cache.put("b", key)
	cache.put("c", key)
	assert.Len(t, cache.entries, 2, "never holds more than its size")

	cache.remove("c")
	_, ok = cache.get("c")
	assert.False(t, ok)
}
