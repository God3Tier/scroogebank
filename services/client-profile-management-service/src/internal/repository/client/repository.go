package client

import (
	"context"
	"database/sql"

	"client-profile-management-service/src/internal/documents"
	"client-profile-management-service/src/internal/encryption"
	"client-profile-management-service/src/internal/models"
)

type Repository interface {
	CreateClient(ctx context.Context, client models.Client) error
	VerifyClientIdentity(ctx context.Context, clientID, agentID string, documentIDs []string) error
	UpdateClientInformation(ctx context.Context, clientID string, client models.Client) error
	GetClientProfile(ctx context.Context, clientID string) (models.Client, error)
	ListClients(ctx context.Context, ownerID string, page, size int) ([]models.Client, int64, error)
	DeleteClient(ctx context.Context, clientID string) error
	CreateDocument(ctx context.Context, document models.ClientDocument, content []byte) error
	ListDocuments(ctx context.Context, clientID string) ([]models.ClientDocument, error)
	GetDocument(ctx context.Context, clientID, documentID string) (models.ClientDocument, []byte, error)
}

type clientRepository struct {
	db        *sql.DB
	protector encryption.Protector
	documents documents.Store
}

// NewClientRepository stores clients' personal fields encrypted with each
// client's data key, and email and phone as blind indexes (ADR-0030). Identity
// documents are sealed with the same key and kept in store (ADR-0041).
func NewClientRepository(db *sql.DB, protector encryption.Protector, store documents.Store) Repository {
	return clientRepository{db: db, protector: protector, documents: store}
}
