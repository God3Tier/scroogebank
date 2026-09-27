package client

import (
	"context"

	"client-profile-management-service/src/internal/models"
	"client-profile-management-service/src/internal/repository/client"
)

type ClientService interface {
	CreateClientProfile(ctx context.Context, client models.Client) error
	VerifyClientIdentity(ctx context.Context, clientID string) error
	UpdateClientInformation(ctx context.Context, client models.Client) error
	GetClientProfile(ctx context.Context, clientID string) (models.Client, error)
	DeleteClientProfile(ctx context.Context, clientID string) error
}

type clientService struct {
	repo client.Repository
}

func NewClientService(repo client.Repository) ClientService {
	return clientService{repo: repo}
}
