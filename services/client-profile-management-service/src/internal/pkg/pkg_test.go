package pkg

import "testing"

func TestEncryptDecrypt(t *testing.T) {
	key := []byte("01234567890123456789012345678901")
	const plaintext = "synthetic client data"

	ciphertext, err := Encrypt(plaintext, key)
	if err != nil {
		t.Fatalf("Encrypt() returned an error: %v", err)
	}

	decrypted, err := Decrypt(ciphertext, key)
	if err != nil {
		t.Fatalf("Decrypt() returned an error: %v", err)
	}
	if decrypted != plaintext {
		t.Fatalf("Decrypt() = %q, want %q", decrypted, plaintext)
	}
}

func TestDecryptRejectsWrongKey(t *testing.T) {
	key := []byte("01234567890123456789012345678901")
	wrongKey := []byte("12345678901234567890123456789012")

	ciphertext, err := Encrypt("synthetic client data", key)
	if err != nil {
		t.Fatalf("Encrypt() returned an error: %v", err)
	}
	if _, err := Decrypt(ciphertext, wrongKey); err == nil {
		t.Fatal("Decrypt() accepted an incorrect key")
	}
}
