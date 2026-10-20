package account

import (
	accounterrors "account-service/src/internal/errors"
	"account-service/src/internal/models"
	orm "account-service/src/internal/repository/orm"
	"context"
)

// CreateAccount replaces the old accounts-to-clients foreign key with an API
// check. Admins go through it too: it is what proves the client exists.
func (s *accountService) CreateAccount(ctx context.Context, account models.CreateAccountInput, caller models.Caller, authorization string) error {
	client, err := s.clients.Lookup(ctx, account.ClientID.String(), authorization)
	if err != nil {
		return err
	}
	// Agents open accounts only for verified clients; admins may open one
	// for any client they can see (ADR-0050).
	if !caller.Admin && !client.Verified {
		return accounterrors.ErrClientNotVerified
	}
	// The client's owner, not the caller: an admin opening an account for an
	// agent's client must not become its owner.
	account.OwnerAgentID = client.OwnerAgentID
	return s.repo.CreateAccount(ctx, account)
}

func (s *accountService) GetAccount(ctx context.Context, accountID string) (*orm.Account, error) {
	return s.repo.GetAccount(ctx, accountID)
}
