package repository

import (
	"context"

	"client-profile-management-service/src/internal/models"

	"github.com/aarondl/sqlboiler/v4/boil"
)

type ClientRepository interface {
	CreateClient(ctx context.Context, exec boil.ContextExecutor, client *models.Client) error
	VerifyClientIdentity(ctx context.Context, exec boil.ContextExecutor, clientID string) error
	UpdateClientInformation(ctx context.Context, exec boil.ContextExecutor, clientID string, client *models.Client) error
	GetClientProfile(ctx context.Context, exec boil.ContextExecutor, clientID string) (*models.Client, error)
	DeleteClient(ctx context.Context, exec boil.ContextExecutor, clientID string) error
}
