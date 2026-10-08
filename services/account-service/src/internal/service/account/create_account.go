package account

import (
	"account-service/src/internal/models"
	orm "account-service/src/internal/repository/orm"
	"context"
)

// CreateAccount replaces the old accounts-to-clients foreign key with an API
// check. Admins go through it too: it is what proves the client exists.
func (s *accountService) CreateAccount(ctx context.Context, account models.CreateAccountInput, authorization string) error {
	owner, err := s.clients.OwnerOf(ctx, account.ClientID.String(), authorization)
	if err != nil {
		return err
	}
	// The client's owner, not the caller: an admin opening an account for an
	// agent's client must not become its owner.
	account.OwnerAgentID = owner
	return s.repo.CreateAccount(ctx, account)
}

func (s *accountService) GetAccount(ctx context.Context, accountID string) (*orm.Account, error) {
	return s.repo.GetAccount(ctx, accountID)
}
