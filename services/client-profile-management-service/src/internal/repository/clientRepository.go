package repository

import (
	errors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	"client-profile-management-service/src/internal/pkg"
	orm "client-profile-management-service/src/internal/repository/orm"
	"context"
	"fmt"
	"time"
	"uuid"

	"github.com/aarondl/sqlboiler/v4/boil"
	"github.com/aarondl/sqlboiler/v4/queries/qm"
)

type clientRepository struct {
	keyProvider ClientKeyProvider
}

func (r *clientRepository) clientEncryptionKey(ctx context.Context, clientID string) ([]byte, error) {
	if r.keyProvider == nil {
		return nil, fmt.Errorf("client key provider is required")
	}
	key, err := r.keyProvider.GetClientKey(ctx, clientID)
	if err != nil {
		return nil, fmt.Errorf("get client encryption key: %w", err)
	}
	if len(key) != 16 && len(key) != 24 && len(key) != 32 {
		return nil, fmt.Errorf("client encryption key must be 16, 24, or 32 bytes")
	}
	return key, nil
}

func NewClientRepository(keyProviders ...ClientKeyProvider) ClientRepository {
	var keyProvider ClientKeyProvider
	if len(keyProviders) > 0 {
		keyProvider = keyProviders[0]
	}
	return &clientRepository{keyProvider: keyProvider}
}

func (r *clientRepository) encryptedClientFields(ctx context.Context, clientID string, client *models.Client) (encryptedClientFields, error) {
	key, err := r.clientEncryptionKey(ctx, clientID)
	if err != nil {
		return encryptedClientFields{}, err
	}
	return encryptClientFields(client, key)
}

func (r *clientRepository) UpdateClientInformation(ctx context.Context, exec boil.ContextExecutor, clientID string, client *models.Client) error {
	existingClient, err := orm.FindClient(ctx, exec, clientID)
	if err != nil {
		return err
	}

	fields, err := r.encryptedClientFields(ctx, clientID, client)
	if err != nil {
		return err
	}
	existingClient.FirstName = fields.firstName
	existingClient.LastName = fields.lastName
	existingClient.DateOfBirth = fields.dateOfBirth
	existingClient.Gender = fields.gender
	existingClient.Email = fields.email
	existingClient.EmailHash = pkg.Hash(client.Email)
	existingClient.PhoneNumber = fields.phoneNumber
	existingClient.PhoneNumberHash = pkg.Hash(client.PhoneNumber)
	existingClient.Address = fields.address
	existingClient.City = fields.city
	existingClient.State = fields.state
	existingClient.Country = fields.country
	existingClient.PostalCode = fields.postalCode

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

	// Convert string clientID to uuid
	c, err := uuid.Parse(client.ClientID)
	if err != nil {
		return nil, err
	}
	key, err := r.clientEncryptionKey(ctx, client.ClientID)
	if err != nil {
		return nil, err
	}
	decrypt := func(value string) (string, error) {
		return pkg.Decrypt(value, key)
	}
	firstName, err := decrypt(client.FirstName)
	if err != nil {
		return nil, err
	}
	lastName, err := decrypt(client.LastName)
	if err != nil {
		return nil, err
	}
	dateOfBirth, err := decrypt(client.DateOfBirth)
	if err != nil {
		return nil, err
	}
	gender, err := decrypt(client.Gender)
	if err != nil {
		return nil, err
	}
	email, err := decrypt(client.Email)
	if err != nil {
		return nil, err
	}
	phoneNumber, err := decrypt(client.PhoneNumber)
	if err != nil {
		return nil, err
	}
	address, err := decrypt(client.Address)
	if err != nil {
		return nil, err
	}
	city, err := decrypt(client.City)
	if err != nil {
		return nil, err
	}
	state, err := decrypt(client.State)
	if err != nil {
		return nil, err
	}
	country, err := decrypt(client.Country)
	if err != nil {
		return nil, err
	}
	postalCode, err := decrypt(client.PostalCode)
	if err != nil {
		return nil, err
	}
	parsedDateOfBirth, err := time.Parse(time.RFC3339Nano, dateOfBirth)
	if err != nil {
		return nil, err
	}
	return &models.Client{
		ClientID:           c,
		FirstName:          firstName,
		LastName:           lastName,
		DateOfBirth:        parsedDateOfBirth,
		Gender:             gender,
		Email:              email,
		PhoneNumber:        phoneNumber,
		Address:            address,
		City:               city,
		State:              state,
		Country:            country,
		PostalCode:         postalCode,
		VerificationStatus: client.VerificationStatus,
		CreatedByAgentID:   client.CreatedByAgentID,
	}, nil
}

func (r *clientRepository) CreateClient(ctx context.Context, exec boil.ContextExecutor, client *models.Client) error {
	if r.isClientDuplicate(ctx, exec, client.Email, client.PhoneNumber) {
		return errors.ErrDuplicateClient
	}
	fields, err := r.encryptedClientFields(ctx, client.ClientID.String(), client)
	if err != nil {
		return err
	}

	newClient := &orm.Client{
		ClientID:           client.ClientID.String(),
		FirstName:          fields.firstName,
		LastName:           fields.lastName,
		DateOfBirth:        fields.dateOfBirth,
		Gender:             fields.gender,
		Email:              fields.email,
		EmailHash:          pkg.Hash(client.Email),
		PhoneNumber:        fields.phoneNumber,
		PhoneNumberHash:    pkg.Hash(client.PhoneNumber),
		Address:            fields.address,
		City:               fields.city,
		State:              fields.state,
		Country:            fields.country,
		PostalCode:         fields.postalCode,
		VerificationStatus: client.VerificationStatus,
		CreatedByAgentID:   client.CreatedByAgentID,
		CreatedAt:          time.Now(),
		UpdatedAt:          time.Now(),
	}

	err = newClient.Insert(ctx, exec, boil.Infer())
	return err
}

func (r *clientRepository) isClientDuplicate(ctx context.Context, exec boil.ContextExecutor, clientEmail string, clientPhone string) bool {
	exists, err := orm.Clients(
		qm.Select(orm.ClientColumns.ClientID),
		qm.Where("email_hash = ?", pkg.Hash(clientEmail)),
		qm.Or2(qm.Where("phone_number_hash = ?", pkg.Hash(clientPhone))),
	).Exists(ctx, exec)
	if err != nil {
		return false
	}

	return exists
}

type encryptedClientFields struct {
	firstName, lastName, dateOfBirth, gender string
	email, phoneNumber, address, city        string
	state, country, postalCode               string
}

func encryptClientFields(client *models.Client, key []byte) (encryptedClientFields, error) {
	values := []string{
		client.FirstName,
		client.LastName,
		client.DateOfBirth.Format(time.RFC3339Nano),
		client.Gender,
		client.Email,
		client.PhoneNumber,
		client.Address,
		client.City,
		client.State,
		client.Country,
		client.PostalCode,
	}
	encrypted := make([]string, len(values))
	for i, value := range values {
		var err error
		encrypted[i], err = pkg.Encrypt(value, key)
		if err != nil {
			return encryptedClientFields{}, err
		}
	}
	return encryptedClientFields{
		firstName: encrypted[0], lastName: encrypted[1], dateOfBirth: encrypted[2],
		gender: encrypted[3], email: encrypted[4], phoneNumber: encrypted[5],
		address: encrypted[6], city: encrypted[7], state: encrypted[8],
		country: encrypted[9], postalCode: encrypted[10],
	}, nil
}
