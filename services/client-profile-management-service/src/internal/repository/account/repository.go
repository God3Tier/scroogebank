package account

import (
	"client-profile-management-service/src/internal/models"
	"context"
	"database/sql"
)

type Repository interface {
	CreateAccount(ctx context.Context, account models.CreateAccountInput) error
	// GetAccount(ctx context.Context, exec boil.ContextExecutor, accountID string) (*models.Account, error)
	// UpdateAccount(ctx context.Context, exec boil.ContextExecutor, account *models.Account) error
	DeleteAccount(ctx context.Context, accountID string) error
}

type accountRepository struct {
	db *sql.DB
}

func NewAccountRepository(db *sql.DB) Repository {
	return accountRepository{db}
}
