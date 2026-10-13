package client

import (
	errors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	orm "client-profile-management-service/src/internal/repository/orm"
	"context"
	"uuid"
)

func (r clientRepository) GetClientProfile(ctx context.Context, clientID string) (models.Client, error) {
	client, err := orm.FindClient(ctx, r.db, clientID)
	if err != nil {
		return models.Client{}, errors.ErrClientNotFound
	}

	// Convert string clientID to uuid
	c, err := uuid.Parse(client.ClientID)
	if err != nil {
		return models.Client{}, err
	}
	key, err := r.protector.Key(ctx, client.ClientID)
	if err != nil {
		return models.Client{}, err
	}
	profile, err := decryptFrom(client, key)
	if err != nil {
		return models.Client{}, err
	}
	profile.ClientID = c
	profile.VerificationStatus = client.VerificationStatus
	profile.VerifiedByAgentID = client.VerifiedByAgentID.String
	if client.VerifiedAt.Valid {
		verifiedAt := client.VerifiedAt.Time
		profile.VerifiedAt = &verifiedAt
	}
	if client.VerifiedWithDocuments.Valid {
		withDocuments := client.VerifiedWithDocuments.Bool
		profile.VerifiedWithDocuments = &withDocuments
	}
	profile.CreatedByAgentID = client.CreatedByAgentID
	profile.CreatedAt = client.CreatedAt
	profile.UpdatedAt = client.UpdatedAt
	return profile, nil
}
