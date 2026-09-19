package repository

import (
	"client-profile-management-service/src/internal/models"
	"context"

	"github.com/aarondl/sqlboiler/v4/boil"
)

type AccountRepository interface {
	CreateAccount(ctx context.Context, exec boil.ContextExecutor, account *models.Account) error
	// GetAccount(ctx context.Context, exec boil.ContextExecutor, accountID string) (*models.Account, error)
	// UpdateAccount(ctx context.Context, exec boil.ContextExecutor, account *models.Account) error
	DeleteAccount(ctx context.Context, exec boil.ContextExecutor, accountID string) error
}
