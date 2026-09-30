package account

import (
	"client-profile-management-service/src/internal/models"
	orm "client-profile-management-service/src/internal/repository/orm"
	"context"
	"database/sql"
)

type Repository interface {
	CreateAccount(ctx context.Context, account models.CreateAccountInput) error
	GetAccount(ctx context.Context, accountID string) (*orm.Account, error)
	DeleteAccount(ctx context.Context, accountID string) error
}

type accountRepository struct {
	db *sql.DB
}

func NewAccountRepository(db *sql.DB) Repository {
	return accountRepository{db}
}
