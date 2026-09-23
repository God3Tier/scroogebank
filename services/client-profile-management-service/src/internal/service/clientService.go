package service

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
	"uuid"

	clientErrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	"client-profile-management-service/src/internal/repository"
)

const (
	defaultVerificationStatus = "UNVERIFIED"
	maxFirstNameLength        = 50
	maxLastNameLength         = 50
	maxGenderLength           = 20
	maxEmailLength            = 255
	maxPhoneLength            = 20
	maxAddressLength          = 100
	maxCityLength             = 50
	maxStateLength            = 50
	maxCountryLength          = 50
	maxPostalCodeLength       = 10
	maxVerificationLength     = 20
)

type clientService struct {
	db   *sql.DB
	repo repository.ClientRepository
}

/* Public Functions */
func NewClientService(db *sql.DB, repo repository.ClientRepository) ClientService {
	return &clientService{db: db, repo: repo}
}

func (s *clientService) CreateClientProfile(ctx context.Context, client models.Client) error {
	if err := validateClientProfile(&client, true); err != nil {
		return err
	}

	client.ClientID = uuid.New()
	err := s.repo.CreateClient(ctx, s.db, &client)
	if err != nil {
		return err
	}

	return nil
}

func (s *clientService) VerifyClientIdentity(ctx context.Context, clientID string) error {
	if strings.TrimSpace(clientID) == "" {
		return fmt.Errorf("%w: client_id is required; %w", clientErrors.ErrBadInput, clientErrors.ErrInvalidClientID)
	}

	// Some implementation to verify NRIC
	err := s.repo.VerifyClientIdentity(ctx, s.db, clientID)
	if err != nil {
		return err
	}

	return nil
}

func (s *clientService) UpdateClientInformation(ctx context.Context, client models.Client) error {
	if err := validateClientProfile(&client, false); err != nil {
		return err
	}

	err := s.repo.UpdateClientInformation(ctx, s.db, client.ClientID.String(), &client)
	if err != nil {
		return err
	}

	return nil
}

func (s *clientService) GetClientProfile(ctx context.Context, clientID string) (*models.Client, error) {
	if strings.TrimSpace(clientID) == "" {
		return nil, fmt.Errorf("%w: client_id is required; %w", clientErrors.ErrBadInput, clientErrors.ErrInvalidClientID)
	}

	client, err := s.repo.GetClientProfile(ctx, s.db, clientID)
	if err != nil {
		return nil, err
	}

	return client, nil
}

func (s *clientService) DeleteClientProfile(ctx context.Context, clientID string) error {
	if strings.TrimSpace(clientID) == "" {
		return fmt.Errorf("%w: client_id is required; %w", clientErrors.ErrBadInput, clientErrors.ErrInvalidClientID)
	}

	err := s.repo.DeleteClient(ctx, s.db, clientID)
	if err != nil {
		return err
	}

	return nil
}

/* Private Functions */
func validateClientProfile(client *models.Client, requireVerificationStatus bool) error {
	// The field 'max: 0' represents no enforced maximum length
	trimmedValues := []struct {
		name  string
		value string
		max   int
	}{
		{name: "client_id", value: strings.TrimSpace(client.ClientID.String()), max: 0},
		{name: "first_name", value: strings.TrimSpace(client.FirstName), max: maxFirstNameLength},
		{name: "last_name", value: strings.TrimSpace(client.LastName), max: maxLastNameLength},
		{name: "gender", value: strings.TrimSpace(client.Gender), max: maxGenderLength},
		{name: "email", value: strings.TrimSpace(client.Email), max: maxEmailLength},
		{name: "phone_number", value: strings.TrimSpace(client.PhoneNumber), max: maxPhoneLength},
		{name: "address", value: strings.TrimSpace(client.Address), max: maxAddressLength},
		{name: "city", value: strings.TrimSpace(client.City), max: maxCityLength},
		{name: "state", value: strings.TrimSpace(client.State), max: maxStateLength},
		{name: "country", value: strings.TrimSpace(client.Country), max: maxCountryLength},
		{name: "postal_code", value: strings.TrimSpace(client.PostalCode), max: maxPostalCodeLength},
		{name: "created_by_agent_id", value: strings.TrimSpace(client.CreatedByAgentID), max: 0},
	}

	for _, field := range trimmedValues {
		if field.name == "created_by_agent_id" && !requireVerificationStatus {
			continue
		}
		if field.value == "" {
			return fmt.Errorf("%w: %s is required; %w", clientErrors.ErrBadInput, field.name, clientErrors.ErrMissingRequiredField)
		}
		if field.max > 0 && len(field.value) > field.max {
			return fmt.Errorf("%w: %s exceeds max length %d", clientErrors.ErrBadInput, field.name, field.max)
		}
	}

	if client.DateOfBirth.IsZero() {
		return fmt.Errorf("%w: date_of_birth is required; %w", clientErrors.ErrBadInput, clientErrors.ErrMissingRequiredField)
	}

	if requireVerificationStatus {
		verificationStatus := strings.TrimSpace(client.VerificationStatus)
		if verificationStatus == "" {
			client.VerificationStatus = defaultVerificationStatus
		} else if len(verificationStatus) > maxVerificationLength {
			return fmt.Errorf("%w: verification_status exceeds max length %d", clientErrors.ErrBadInput, maxVerificationLength)
		}
	}

	return nil
}
