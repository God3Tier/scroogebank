package repository

import (
	"client-profile-management-service/src/internal/models"
	"context"

	"github.com/aarondl/sqlboiler/v4/boil"
)

type accountRepository struct {
}

func NewAccountRepository() AccountRepository {
	return &accountRepository{}
}

func (r *accountRepository) CreateAccount(ctx context.Context, exec boil.ContextExecutor, account *models.Account) error {

	return nil
}

func (r *accountRepository) DeleteAccount(ctx context.Context, exec boil.ContextExecutor, accountID string) error {
	return nil
}
