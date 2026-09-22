package service

import (
	"context"

	"client-profile-management-service/src/internal/models"
)

type ClientService interface {
	CreateClientProfile(ctx context.Context, client models.Client) error
	VerifyClientIdentity(ctx context.Context, clientID string) error
	UpdateClientInformation(ctx context.Context, client models.Client) error
	GetClientProfile(ctx context.Context, clientID string) (*models.Client, error)
	DeleteClientProfile(ctx context.Context, clientID string) error
}
