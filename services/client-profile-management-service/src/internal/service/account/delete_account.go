package account

import (
	"context"
	"fmt"
	"strings"

	clienterrors "client-profile-management-service/src/internal/errors"
)

func (s *accountService) DeleteAccount(ctx context.Context, accountID string) error {
	if strings.TrimSpace(accountID) == "" {
		return fmt.Errorf("%w: account_id is required; %w", clienterrors.ErrBadInput, clienterrors.ErrInvalidAccountID)
	}
	return s.repo.DeleteAccount(ctx, accountID)
}
