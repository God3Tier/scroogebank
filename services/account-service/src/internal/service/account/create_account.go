package account

import (
	accounterrors "account-service/src/internal/errors"
	"account-service/src/internal/models"
	orm "account-service/src/internal/repository/orm"
	"context"
)

// CreateAccount replaces the old accounts-to-clients foreign key with an API
// check.
func (s *accountService) CreateAccount(ctx context.Context, account models.CreateAccountInput, caller models.Caller, authorization string) error {
	// Admins do not manage client accounts (requirements Q&A UM18, ADR-0059).
	if caller.Admin {
		return accounterrors.ErrForbidden
	}
	client, err := s.clients.Lookup(ctx, account.ClientID.String(), authorization)
	if err != nil {
		return err
	}
	// An unverified client's new accounts are pending until the agent
	// verifies the client and activates them (ADR-0050).
	if !client.Verified && account.Status != models.AccountStatusPending {
		return accounterrors.ErrClientNotVerified
	}
	// The client's owner, not the caller, which the lookup has shown to be the
	// same agent.
	account.OwnerAgentID = client.OwnerAgentID
	return s.repo.CreateAccount(ctx, account)
}

func (s *accountService) GetAccount(ctx context.Context, accountID string) (*orm.Account, error) {
	return s.repo.GetAccount(ctx, accountID)
}
