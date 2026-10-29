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
	// UpdateAccountStatus sets an account's status and returns the one it had.
	UpdateAccountStatus(ctx context.Context, accountID, status string) (string, error)
	// ListAccountsByClient returns every account of a client. Admin scope.
	ListAccountsByClient(ctx context.Context, clientID string) (orm.AccountSlice, error)
	// ListAccountsByClientAndOwner returns a client's accounts owned by one
	// agent. Agent scope.
	ListAccountsByClientAndOwner(ctx context.Context, clientID, ownerAgentID string) (orm.AccountSlice, error)
	// FindAccountsByIDs returns the accounts among ids that exist. Service
	// scope: the transaction import links rows with it (ADR-0037).
	FindAccountsByIDs(ctx context.Context, ids []string) (orm.AccountSlice, error)
	// SetOwner moves every account of the clients to a new owner, when an
	// admin reassigns them (ADR-0060). It returns how many it updated.
	SetOwner(ctx context.Context, clientIDs []string, ownerAgentID string) (int64, error)
}

type accountRepository struct {
	db *sql.DB
}

func NewAccountRepository(db *sql.DB) Repository {
	return accountRepository{db}
}
