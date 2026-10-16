package account

import (
	"account-service/src/internal/clients"
	"account-service/src/internal/models"
	"account-service/src/internal/repository/account"
	orm "account-service/src/internal/repository/orm"
	"context"
)

type AccountService interface {
	// CreateAccount asks client-profile-management-service for the client's
	// owner, using the caller's Authorization header, and stores that owner on
	// the account.
	CreateAccount(ctx context.Context, account models.CreateAccountInput, authorization string) error
	GetAccount(ctx context.Context, accountID string) (*orm.Account, error)
	DeleteAccount(ctx context.Context, accountID string) error
	// ListAccounts returns a client's accounts that the caller may see.
	ListAccounts(ctx context.Context, clientID string, caller models.Caller) (orm.AccountSlice, error)
	// LookupAccounts returns the accounts among ids that exist and are not
	// deleted, for the transaction import (ADR-0037).
	LookupAccounts(ctx context.Context, ids []string) (orm.AccountSlice, error)
}

type accountService struct {
	repo    account.Repository
	clients clients.Directory
}

func NewAccountService(repo account.Repository, clients clients.Directory) AccountService {
	return &accountService{repo: repo, clients: clients}
}
