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
	return models.Client{
		ClientID:           c,
		FirstName:          client.FirstName,
		LastName:           client.LastName,
		DateOfBirth:        client.DateOfBirth,
		Gender:             client.Gender,
		Email:              client.Email,
		PhoneNumber:        client.PhoneNumber,
		Address:            client.Address,
		City:               client.City,
		State:              client.State,
		Country:            client.Country,
		PostalCode:         client.PostalCode,
		VerificationStatus: client.VerificationStatus,
		CreatedByAgentID:   client.CreatedByAgentID,
	}, nil
}
