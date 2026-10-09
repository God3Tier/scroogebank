package encryption

import (
	"context"
	"errors"
)

var (
	// ErrKeyNotFound means the client has no stored data key.
	ErrKeyNotFound = errors.New("client data key not found")
	// ErrKeyExists means a data key is already stored for the client.
	ErrKeyExists = errors.New("client data key already exists")
)

// Protector holds clients' data keys and computes blind indexes (ADR-0030).
type Protector interface {
	// NewKey creates and stores the data key for a client that has none.
	NewKey(ctx context.Context, clientID string) (DataKey, error)
	// Key returns an existing client's data key.
	Key(ctx context.Context, clientID string) (DataKey, error)
	// DeleteKey removes a client's data key. Creating a client uses it to
	// clean up when the insert fails after the key was stored.
	DeleteKey(ctx context.Context, clientID string) error
	// BlindIndex returns the keyed hash of a value that is already
	// normalised (NormalizeEmail, NormalizePhone).
	BlindIndex(ctx context.Context, normalized string) (string, error)
}
