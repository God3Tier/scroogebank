package repository

import (
	errors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	orm "client-profile-management-service/src/internal/repository/orm"
	"context"
	"time"

	"github.com/aarondl/sqlboiler/v4/boil"
	"github.com/aarondl/sqlboiler/v4/queries/qm"
)

type clientRepository struct {
}

func NewClientRepository() ClientRepository {
	return &clientRepository{}
}

func (r *clientRepository) UpdateClientInformation(ctx context.Context, exec boil.ContextExecutor, clientID string, client *models.Client) error {
	existingClient, err := orm.FindClient(ctx, exec, clientID)
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

	_, err = existingClient.Update(ctx, exec, boil.Infer())
	return err
}

// This function assumes the verification prerequisites is fulfilled & only serves to reflect this status in the DB.
func (r *clientRepository) VerifyClientIdentity(ctx context.Context, exec boil.ContextExecutor, clientID string) error {
	client, err := orm.FindClient(ctx, exec, clientID)
	if err != nil {
		return err
	}

	client.VerificationStatus = "verified"
	_, err = client.Update(ctx, exec, boil.Infer())
	return err
}

func (r *clientRepository) DeleteClient(ctx context.Context, exec boil.ContextExecutor, clientID string) error {
	client, err := orm.FindClient(ctx, exec, clientID)
	if err != nil {
		return err
	}

	_, err = client.Delete(ctx, exec)
	return err
}

func (r *clientRepository) GetClientProfile(ctx context.Context, exec boil.ContextExecutor, clientID string) (*models.Client, error) {
	client, err := orm.FindClient(ctx, exec, clientID)
	if err != nil {
		return nil, errors.ErrClientNotFound
	}

	return &models.Client{
		ClientID:           client.ClientID,
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

func (r *clientRepository) CreateClient(ctx context.Context, exec boil.ContextExecutor, client *models.Client) error {
	if r.isClientDuplicate(ctx, exec, client.Email, client.PhoneNumber) {
		return errors.ErrDuplicateClient
	}

	newClient := &orm.Client{
		ClientID:           client.ClientID,
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

	err := newClient.Insert(ctx, exec, boil.Infer())
	return err
}

func (r *clientRepository) isClientDuplicate(ctx context.Context, exec boil.ContextExecutor, clientEmail string, clientPhone string) bool {
	exists, err := orm.Clients(
		qm.Select(orm.ClientColumns.ClientID),
		qm.Where("email = ?", clientEmail),
		qm.Or2(qm.Where("phone_number = ?", clientPhone)),
	).Exists(ctx, exec)
	if err != nil {
		return false
	}

	return exists
}
