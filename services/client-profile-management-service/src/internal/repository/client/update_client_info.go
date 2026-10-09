package client

import (
	"client-profile-management-service/src/internal/models"
	"context"
)

func (r clientRepository) UpdateClientInformation(ctx context.Context, clientID string, client models.Client) error {
	existingClient, err := r.findClient(ctx, clientID)
	if err != nil {
		return err
	}

	existingClient.FirstName = client.FirstName
	existingClient.LastName = client.LastName
	existingClient.DateOfBirth = client.DateOfBirth
	existingClient.Gender = client.Gender
	existingClient.Email = client.Email
	existingClient.PhoneNumber = client.PhoneNumber
	existingClient.Address = client.Address
	existingClient.City = client.City
	existingClient.State = client.State
	existingClient.Country = client.Country
	existingClient.PostalCode = client.PostalCode

	_, err = existingClient.Update(ctx, r.db, keepDeletedAt)
	return err
}