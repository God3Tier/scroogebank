// Package encryption encrypts client personal data with per-client data keys
// and computes blind indexes for the fields that must stay unique (ADR-0030).
package encryption

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"fmt"
)

// DataKey encrypts and decrypts one client's fields with AES-256-GCM.
//
// Each value is bound to its client and field through GCM's additional data,
// so a ciphertext copied to another client or another column fails to
// decrypt. Adapted from Encrypt and Decrypt in ofwirawan's 6193bf3 on
// feat/client-profile-management-go.
type DataKey struct {
	clientID string
	aead     cipher.AEAD
}

// NewDataKey makes the DataKey for clientID from a 32-byte plaintext key.
func NewDataKey(clientID string, key []byte) (DataKey, error) {
	if len(key) != 32 {
		return DataKey{}, fmt.Errorf("data key must be 32 bytes, got %d", len(key))
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return DataKey{}, fmt.Errorf("create aes cipher: %w", err)
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return DataKey{}, fmt.Errorf("create gcm: %w", err)
	}
	return DataKey{clientID: clientID, aead: aead}, nil
}

// Encrypt returns base64(nonce || ciphertext) for one field's value.
func (k DataKey) Encrypt(field, plaintext string) (string, error) {
	nonce := make([]byte, k.aead.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return "", fmt.Errorf("generate nonce: %w", err)
	}
	sealed := k.aead.Seal(nonce, nonce, []byte(plaintext), k.additionalData(field))
	return base64.StdEncoding.EncodeToString(sealed), nil
}

// Decrypt reverses Encrypt. It fails if the value was encrypted for another
// client or field, or has been changed.
func (k DataKey) Decrypt(field, ciphertext string) (string, error) {
	sealed, err := base64.StdEncoding.DecodeString(ciphertext)
	if err != nil {
		return "", fmt.Errorf("decode %s: %w", field, err)
	}
	nonceSize := k.aead.NonceSize()
	if len(sealed) < nonceSize {
		return "", fmt.Errorf("decrypt %s: ciphertext is shorter than its nonce", field)
	}
	plaintext, err := k.aead.Open(nil, sealed[:nonceSize], sealed[nonceSize:], k.additionalData(field))
	if err != nil {
		return "", fmt.Errorf("decrypt %s: %w", field, err)
	}
	return string(plaintext), nil
}

func (k DataKey) additionalData(field string) []byte {
	return []byte(k.clientID + "|" + field)
}
