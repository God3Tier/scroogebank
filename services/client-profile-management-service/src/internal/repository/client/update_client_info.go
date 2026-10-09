package client

import (
	"client-profile-management-service/src/internal/models"
	"context"
)

// UpdateClientInformation re-encrypts the personal fields with the client's
// existing data key and recomputes the email and phone blind indexes.
func (r clientRepository) UpdateClientInformation(ctx context.Context, clientID string, client models.Client) error {
	existingClient, err := r.findClient(ctx, clientID)
	if err != nil {
		return err
	}
	key, err := r.protector.Key(ctx, existingClient.ClientID)
	if err != nil {
		return err
	}
	indexes, err := r.contactIndexesFor(ctx, client.Email, client.PhoneNumber)
	if err != nil {
		return err
	}
	if err := encryptInto(existingClient, key, client); err != nil {
		return err
	}
	existingClient.EmailHash = indexes.email
	existingClient.PhoneNumberHash = indexes.phone

	_, err = existingClient.Update(ctx, r.db, keepDeletedAt)
	return err
}
