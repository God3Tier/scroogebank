package account

import (
	accounterrors "account-service/src/internal/errors"
	"account-service/src/internal/models"
	orm "account-service/src/internal/repository/orm"
	"context"
	"fmt"
	"strings"
)

// ListAccounts scopes by the caller: an admin sees every account of the
// client, an agent only the accounts they own. Scope comes from the verified
// token, never from a request parameter.
func (s *accountService) ListAccounts(ctx context.Context, clientID string, caller models.Caller) (orm.AccountSlice, error) {
	if strings.TrimSpace(clientID) == "" {
		return nil, fmt.Errorf("%w: clientId is required; %w", accounterrors.ErrBadInput, accounterrors.ErrInvalidClientID)
	}
	if caller.Admin {
		return s.repo.ListAccountsByClient(ctx, clientID)
	}
	if strings.TrimSpace(caller.Subject) == "" {
		// Unreachable behind the Cognito middleware, which rejects tokens
		// without a sub. Refuse rather than fall through to an unscoped query.
		return nil, fmt.Errorf("%w: caller has no subject", accounterrors.ErrBadInput)
	}
	return s.repo.ListAccountsByClientAndOwner(ctx, clientID, caller.Subject)
}
