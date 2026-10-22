package client

import (
	clienterrors "client-profile-management-service/src/internal/errors"
	orm "client-profile-management-service/src/internal/repository/orm"
	"context"
	"database/sql"
	"errors"

	"github.com/aarondl/sqlboiler/v4/queries/qm"
	"github.com/lib/pq"
)

// contactUniqueIndexes keep the email and phone blind indexes unique among
// clients that are not deleted (data/V1_init.sql).
var contactUniqueIndexes = map[string]bool{"uk_clients_email": true, "uk_clients_phone": true}

// isContactTaken reports whether err is Postgres refusing a row because
// another client that is not deleted already has its email or phone. The
// indexes are the real check: they also catch two requests racing for the
// same contact, which a lookup before the write cannot.
func isContactTaken(err error) bool {
	var pqErr *pq.Error
	return errors.As(err, &pqErr) && pqErr.Code == "23505" && contactUniqueIndexes[pqErr.Constraint]
}

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
