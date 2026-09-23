package pkg

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
)

func Hash(payload string) string {
	raw := sha256.Sum256([]byte(payload))
	return fmt.Sprintf("%x", raw)
}

func Encrypt(payload string, key []byte) (string, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", fmt.Errorf("failed to create aes cipher: %w", err)
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", fmt.Errorf("failed to create gcm: %w", err)
	}

	nonce := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return "", fmt.Errorf("failed to generate nonce: %w", err)
	}

	ciphertext := gcm.Seal(nil, nonce, []byte(payload), nil)
	return hex.EncodeToString(append(nonce, ciphertext...)), nil
}

func Decrypt(payload string, key []byte) (string, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", fmt.Errorf("failed to create aes cipher: %w", err)
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", fmt.Errorf("failed to create gcm: %w", err)
	}

	encoded, err := hex.DecodeString(payload)
	if err != nil {
		return "", fmt.Errorf("failed to decode ciphertext: %w", err)
	}

	nonceSize := gcm.NonceSize()
	if len(encoded) < nonceSize {
		return "", fmt.Errorf("ciphertext is shorter than nonce")
	}

	nonce, ciphertext := encoded[:nonceSize], encoded[nonceSize:]
	plaintext, err := gcm.Open(nil, nonce, ciphertext, nil)
	if err != nil {
		return "", fmt.Errorf("failed to decrypt ciphertext: %w", err)
	}

	return string(plaintext), nil
}
