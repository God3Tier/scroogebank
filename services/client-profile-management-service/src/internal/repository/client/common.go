package client

import (
	clienterrors "client-profile-management-service/src/internal/errors"
	orm "client-profile-management-service/src/internal/repository/orm"
	"context"
	"database/sql"
	"errors"

	"github.com/aarondl/sqlboiler/v4/queries/qm"
)

// findClient treats a deleted client as not found (ADR-0036).
func (r clientRepository) findClient(ctx context.Context, clientID string) (*orm.Client, error) {
	client, err := orm.FindClient(ctx, r.db, clientID)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, clienterrors.ErrClientNotFound
	}
	return client, err
}

// isClientDuplicate reports whether a client that is not deleted already has
// this email or phone, compared by blind index (ADR-0030).
func (r clientRepository) isClientDuplicate(ctx context.Context, indexes contactIndexes) bool {
	// One clause: the generated "AND deleted_at IS NULL" would otherwise bind
	// to the phone check only.
	exists, err := orm.Clients(
		qm.Select(orm.ClientColumns.ClientID),
		qm.Where("email_hash = ? OR phone_number_hash = ?", indexes.email, indexes.phone),
	).Exists(ctx, r.db)
	if err != nil {
		return false
	}

	return exists
}
