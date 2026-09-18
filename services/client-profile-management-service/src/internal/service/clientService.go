package service

// Consider putting this on a seperate file
import (
	"client-profile-management-service/src/internal/models"
	"client-profile-management-service/src/internal/repository"
	"context"
	"database/sql"
	"errors"
)

var (
	ErrBadInput = errors.New("bad input")
	ErrDuplicateClient = errors.New("duplicate client")
	ErrClientNotFound = errors.New("client not found")
)

type ClientService interface {
	CreateClientProfile(ctx context.Context, client models.Client) error
	VerifyClientIdentity(ctx context.Context, clientID string) error
	UpdateClientInformation(ctx context.Context, client models.Client) error
	GetClientProfile(ctx context.Context, clientID string) (*models.Client, error)
	DeleteClientProfile(ctx context.Context, clientID string) error
}

type clientService struct {
	db *sql.DB
	repo repository.ClientRepository
}

func NewClientService(db *sql.DB, repo repository.ClientRepository) ClientService {
	return &clientService{db: db, repo: repo}
}

func (s *clientService) CreateClientProfile(ctx context.Context, client models.Client) error {
	err := s.repo.CreateClient(ctx, s.db, &client)
	if err != nil {
		return err
	}

	return nil;
}

func (s *clientService) VerifyClientIdentity(ctx context.Context, clientID string) error {
	// Some implementation to verify NRIC
	err := s.repo.VerifyClientIdentity(ctx, s.db, clientID)
	if err != nil {
		return err
	}

	return nil
}

func (s *clientService) UpdateClientInformation(ctx context.Context, client models.Client) error {
	err := s.repo.UpdateClientInformation(ctx, s.db, client.ClientID, &client)
	if err != nil {
		return err
	}

	return nil
}

func (s *clientService) GetClientProfile(ctx context.Context, clientID string) (*models.Client, error) {
	client, err := s.repo.GetClientProfile(ctx, s.db, clientID)
	if err != nil {
		return nil, err
	}

	return client, nil
}

func (s *clientService) DeleteClientProfile(ctx context.Context, clientID string) error {
	err := s.repo.DeleteClient(ctx, s.db, clientID)
	if err != nil {
		return err
	}

	return nil
}
