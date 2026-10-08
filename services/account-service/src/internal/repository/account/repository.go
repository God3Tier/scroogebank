package account

import (
	"account-service/src/internal/models"
	orm "account-service/src/internal/repository/orm"
	"context"
	"database/sql"
)

type Repository interface {
	CreateAccount(ctx context.Context, account models.CreateAccountInput) error
	GetAccount(ctx context.Context, accountID string) (*orm.Account, error)
	DeleteAccount(ctx context.Context, accountID string) error
	// ListAccountsByClient returns every account of a client. Admin scope.
	ListAccountsByClient(ctx context.Context, clientID string) (orm.AccountSlice, error)
	// ListAccountsByClientAndOwner returns a client's accounts owned by one
	// agent. Agent scope.
	ListAccountsByClientAndOwner(ctx context.Context, clientID, ownerAgentID string) (orm.AccountSlice, error)
}

type accountRepository struct {
	db *sql.DB
}

func NewAccountRepository(db *sql.DB) Repository {
	return accountRepository{db}
}
