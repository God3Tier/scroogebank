package account

import (
	accounterrors "account-service/src/internal/errors"
	"account-service/src/internal/models"
	"context"
	"strings"
)

// UpdateAccountStatus lets an agent move their account to any status, in any
// direction, once its client is verified (ADR-0050). The router has already
// checked that an agent owns the account.
func (s *accountService) UpdateAccountStatus(ctx context.Context, accountID string, status models.AccountStatus, caller models.Caller, authorization string) (models.AccountStatus, models.AccountStatus, error) {
	// Admins do not manage client accounts (requirements Q&A UM18).
	if caller.Admin {
		return "", "", accounterrors.ErrForbidden
	}
	account, err := s.repo.GetAccount(ctx, accountID)
	if err != nil {
		return "", "", err
	}
	client, err := s.clients.Lookup(ctx, account.ClientID, authorization)
	if err != nil {
		return "", "", err
	}
	// An unverified client's accounts stay as they are until the agent
	// verifies the client.
	if !client.Verified {
		return "", "", accounterrors.ErrClientNotVerified
	}
	previous, err := s.repo.UpdateAccountStatus(ctx, accountID, string(status))
	if err != nil {
		return "", "", err
	}
	return models.AccountStatus(strings.ToUpper(previous)), status, nil
}
