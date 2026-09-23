package repository

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"

	clienterrors "client-profile-management-service/src/internal/errors"
)

type clientKeysRepository struct {
	db *sql.DB
}

// NewClientKeysRepository creates a key provider backed by the key database.
func NewClientKeysRepository(db *sql.DB) ClientKeyProvider {
	return &clientKeysRepository{db: db}
}

// GetClientKey retrieves the AES key belonging to clientID from the key database.
func (r *clientKeysRepository) GetClientKey(ctx context.Context, clientID string) ([]byte, error) {
	if r == nil || r.db == nil {
		return nil, fmt.Errorf("client key database is required")
	}
	if strings.TrimSpace(clientID) == "" {
		return nil, fmt.Errorf("%w: client_id is required", clienterrors.ErrInvalidClientID)
	}

	var keyValue string
	err := r.db.QueryRowContext(
		ctx,
		`SELECT key_value FROM client_keys WHERE client_id = $1`,
		clientID,
	).Scan(&keyValue)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, clienterrors.ErrNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("retrieve client encryption key: %w", err)
	}
	if strings.TrimSpace(keyValue) == "" {
		return nil, fmt.Errorf("client encryption key is empty")
	}

	return []byte(keyValue), nil
}
