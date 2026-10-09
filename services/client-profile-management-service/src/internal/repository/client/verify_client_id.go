package client

import (
	"context"
)

// This function assumes the verification prerequisites is fulfilled & only serves to reflect this status in the DB.
func (r clientRepository) VerifyClientIdentity(ctx context.Context, clientID string) error {
	client, err := r.findClient(ctx, clientID)
	if err != nil {
		return err
	}

	client.VerificationStatus = "verified"
	_, err = client.Update(ctx, r.db, keepDeletedAt)
	return err
}

// DeleteClient soft-deletes: it sets deleted_at and keeps the row (ADR-0036).
func (r clientRepository) DeleteClient(ctx context.Context, clientID string) error {
	client, err := r.findClient(ctx, clientID)
	if err != nil {
		return err
	}

	_, err = client.Delete(ctx, r.db, false) // false: soft delete
	return err
}
