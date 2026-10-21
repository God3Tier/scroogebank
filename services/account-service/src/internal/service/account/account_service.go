package account

import (
	"account-service/src/internal/clients"
	"account-service/src/internal/models"
	"account-service/src/internal/repository/account"
	orm "account-service/src/internal/repository/orm"
	"context"
)

type AccountService interface {
	// CreateAccount asks client-profile-management-service for the client,
	// using the caller's Authorization header, and stores the client's owner
	// on the account. For an unverified client an agent may open only a
	// pending account (ADR-0050).
	CreateAccount(ctx context.Context, account models.CreateAccountInput, caller models.Caller, authorization string) error
	// UpdateAccountStatus changes an account's status for the agent who owns
	// it, once its client is verified, and returns the status before and
	// after (ADR-0050). Admins do not manage client accounts.
	UpdateAccountStatus(ctx context.Context, accountID string, status models.AccountStatus, caller models.Caller, authorization string) (before, after models.AccountStatus, err error)
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
