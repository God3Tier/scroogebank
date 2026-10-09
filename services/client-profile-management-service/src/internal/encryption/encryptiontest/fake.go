// Package encryptiontest provides an in-memory encryption.Protector for
// tests in other packages. It uses real AES-GCM data keys and a fixed HMAC
// key, so blind indexes are the same in every test run.
package encryptiontest

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"sync"

	"client-profile-management-service/src/internal/encryption"
)

var indexKey = []byte("encryptiontest blind-index key")

// Protector keeps data keys in memory. When Err is set, every method returns
// it.
type Protector struct {
	mu   sync.Mutex
	keys map[string][]byte
	Err  error
}

func New() *Protector {
	return &Protector{keys: map[string][]byte{}}
}

func (p *Protector) NewKey(_ context.Context, clientID string) (encryption.DataKey, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.Err != nil {
		return encryption.DataKey{}, p.Err
	}
	if _, ok := p.keys[clientID]; ok {
		return encryption.DataKey{}, encryption.ErrKeyExists
	}
	key := make([]byte, 32)
	if _, err := rand.Read(key); err != nil {
		return encryption.DataKey{}, err
	}
	p.keys[clientID] = key
	return encryption.NewDataKey(clientID, key)
}

func (p *Protector) Key(_ context.Context, clientID string) (encryption.DataKey, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.Err != nil {
		return encryption.DataKey{}, p.Err
	}
	key, ok := p.keys[clientID]
	if !ok {
		return encryption.DataKey{}, encryption.ErrKeyNotFound
	}
	return encryption.NewDataKey(clientID, key)
}

func (p *Protector) DeleteKey(_ context.Context, clientID string) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.Err != nil {
		return p.Err
	}
	delete(p.keys, clientID)
	return nil
}

func (p *Protector) BlindIndex(_ context.Context, normalized string) (string, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.Err != nil {
		return "", p.Err
	}
	if normalized == "" {
		return "", errors.New("blind index of an empty value")
	}
	return Index(normalized), nil
}

// HasKey reports whether a data key is stored for the client.
func (p *Protector) HasKey(clientID string) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	_, ok := p.keys[clientID]
	return ok
}

// Index is the blind index this fake computes for a normalised value, for
// assertions on stored rows.
func Index(normalized string) string {
	mac := hmac.New(sha256.New, indexKey)
	mac.Write([]byte(normalized))
	return hex.EncodeToString(mac.Sum(nil))
}
