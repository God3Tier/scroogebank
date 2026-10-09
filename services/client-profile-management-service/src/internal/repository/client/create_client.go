package client

import (
	errors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	orm "client-profile-management-service/src/internal/repository/orm"
	"context"
	"log"
	"time"

	"github.com/aarondl/sqlboiler/v4/boil"
)

// CreateClient checks for a duplicate by blind index, creates the client's
// data key, then stores the row encrypted with it. The key is stored first, so
// a row never exists without its key (ADR-0030 decision 2).
func (r clientRepository) CreateClient(ctx context.Context, client models.Client) error {
	indexes, err := r.contactIndexesFor(ctx, client.Email, client.PhoneNumber)
	if err != nil {
		return err
	}
	if r.isClientDuplicate(ctx, indexes) {
		return errors.ErrDuplicateClient
	}

	clientID := client.ClientID.String()
	key, err := r.protector.NewKey(ctx, clientID)
	if err != nil {
		return err
	}
	newClient := &orm.Client{
		ClientID:           clientID,
		EmailHash:          indexes.email,
		PhoneNumberHash:    indexes.phone,
		VerificationStatus: client.VerificationStatus,
		CreatedByAgentID:   client.CreatedByAgentID,
		CreatedAt:          time.Now(),
		UpdatedAt:          time.Now(),
	}
	err = encryptInto(newClient, key, client)
	if err == nil {
		err = newClient.Insert(ctx, r.db, boil.Infer())
	}
	if err != nil {
		// The row was not stored, so its new key is not needed.
		if deleteErr := r.protector.DeleteKey(ctx, clientID); deleteErr != nil {
			log.Printf("remove unused data key for client %s: %v", clientID, deleteErr)
		}
		return err
	}
	return nil
}
