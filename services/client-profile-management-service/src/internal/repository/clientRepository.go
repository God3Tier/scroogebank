package repository

import (
	"client-profile-management-service/src/internal/models"
	orm "client-profile-management-service/src/internal/repository/orm"
	"context"
	"time"

	"github.com/aarondl/sqlboiler/v4/boil"
)
type ClientRepository interface {
	CreateClient(ctx context.Context, exec boil.ContextExecutor, client *models.Client) error
	VerifyClientIdentity(ctx context.Context, exec boil.ContextExecutor, clientID string) error
	UpdateClientInformation(ctx context.Context, exec boil.ContextExecutor, clientID string, client *models.Client) error
	GetClientProfile(ctx context.Context, exec boil.ContextExecutor, clientID string) (*models.Client, error)
	DeleteClient(ctx context.Context, exec boil.ContextExecutor, clientID string) error
}

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
		return nil, err
	}

	return &models.Client{
		ClientID: client.ClientID,
		FirstName: client.FirstName,
		LastName: client.LastName,
		DateOfBirth: client.DateOfBirth,
		Gender: client.Gender,
		Email: client.Email,
		PhoneNumber: client.PhoneNumber,
		Address: client.Address,
		City: client.City,
		State: client.State,
		Country: client.Country,
		PostalCode: client.PostalCode,
		VerificationStatus: client.VerificationStatus,
		CreatedByAgentID: client.CreatedByAgentID,
	}, nil
}

func (r *clientRepository) CreateClient(ctx context.Context, exec boil.ContextExecutor, client *models.Client) error {
	newClient := &orm.Client{
		ClientID: client.ClientID,
		FirstName: client.FirstName,
		LastName: client.LastName,
		DateOfBirth: client.DateOfBirth,
		Gender: client.Gender,
		Email: client.Email,
		PhoneNumber: client.PhoneNumber,
		Address: client.Address,
		City: client.City,
		State: client.State,
		Country: client.Country,
		PostalCode: client.PostalCode,
		VerificationStatus: client.VerificationStatus,
		CreatedByAgentID: client.CreatedByAgentID,
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}

	err := newClient.Insert(ctx, exec, boil.Infer())
	return err
}
