package client

import (
	"context"

	"client-profile-management-service/src/internal/accounts"
	"client-profile-management-service/src/internal/models"
	"client-profile-management-service/src/internal/repository/client"
)

type ClientService interface {
	CreateClientProfile(ctx context.Context, client models.Client) error
	VerifyClientIdentity(ctx context.Context, clientID string) error
	UpdateClientInformation(ctx context.Context, client models.Client) error
	GetClientProfile(ctx context.Context, clientID string) (models.Client, error)
	// DeleteClientProfile refuses while account-service still holds accounts
	// for the client. It forwards the caller's Authorization header there.
	DeleteClientProfile(ctx context.Context, clientID, authorization string) error
}

type clientService struct {
	repo     client.Repository
	accounts accounts.Directory
}

func NewClientService(repo client.Repository, accounts accounts.Directory) ClientService {
	return clientService{repo: repo, accounts: accounts}
}
