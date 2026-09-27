package client

import (
	"client-profile-management-service/src/internal/models"
	orm "client-profile-management-service/src/internal/repository/orm"
	"context"

	"github.com/aarondl/sqlboiler/v4/boil"
)

func (r clientRepository) UpdateClientInformation(ctx context.Context, clientID string, client models.Client) error {
	existingClient, err := orm.FindClient(ctx, r.db, clientID)
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

	_, err = existingClient.Update(ctx, r.db, boil.Infer())
	return err
}