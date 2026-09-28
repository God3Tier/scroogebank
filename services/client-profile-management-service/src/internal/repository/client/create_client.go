package client

import (
	errors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	orm "client-profile-management-service/src/internal/repository/orm"
	"context"
	"time"

	"github.com/aarondl/sqlboiler/v4/boil"
)

func (r clientRepository) CreateClient(ctx context.Context, client models.Client) error {
	if r.isClientDuplicate(ctx, client.Email, client.PhoneNumber) {
		return errors.ErrDuplicateClient
	}

	newClient := &orm.Client{
		ClientID:           client.ClientID.String(),
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
		CreatedAt:          time.Now(),
		UpdatedAt:          time.Now(),
	}

	err := newClient.Insert(ctx, r.db, boil.Infer())
	return err
}
