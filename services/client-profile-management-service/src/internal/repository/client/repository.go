package client

import (
	"context"
	"database/sql"

	"client-profile-management-service/src/internal/encryption"
	"client-profile-management-service/src/internal/models"
)

type Repository interface {
	CreateClient(ctx context.Context, client models.Client) error
	VerifyClientIdentity(ctx context.Context, clientID string) error
	UpdateClientInformation(ctx context.Context, clientID string, client models.Client) error
	GetClientProfile(ctx context.Context, clientID string) (models.Client, error)
	DeleteClient(ctx context.Context, clientID string) error
}

type clientRepository struct {
	db        *sql.DB
	protector encryption.Protector
}

// NewClientRepository stores clients' personal fields encrypted with each
// client's data key, and email and phone as blind indexes (ADR-0030).
func NewClientRepository(db *sql.DB, protector encryption.Protector) Repository {
	return clientRepository{db: db, protector: protector}
}
