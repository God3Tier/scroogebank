package client

import (
	"context"

	"client-profile-management-service/src/internal/accounts"
	"client-profile-management-service/src/internal/models"
	"client-profile-management-service/src/internal/repository/client"
)

type ClientService interface {
	CreateClientProfile(ctx context.Context, client models.Client) error
	// VerifyClientIdentity records that agentID verified the client, with the
	// documents they checked, if any (ADR-0041 decision 5).
	VerifyClientIdentity(ctx context.Context, clientID, agentID string, documentIDs []string) error
	// UploadDocument stores an identity document; it never changes the
	// client's verification status (ADR-0041 decision 2).
	UploadDocument(ctx context.Context, clientID, agentID string, content []byte) (models.ClientDocument, error)
	ListDocuments(ctx context.Context, clientID string) ([]models.ClientDocument, error)
	GetDocument(ctx context.Context, clientID, documentID string) (models.ClientDocument, []byte, error)
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
