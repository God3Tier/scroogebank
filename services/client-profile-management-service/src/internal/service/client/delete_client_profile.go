package client

import (
	"context"
	"fmt"
	"strings"

	clientErrors "client-profile-management-service/src/internal/errors"
)

// DeleteClientProfile keeps the rule the old accounts-to-clients foreign key
// enforced (ON DELETE RESTRICT): a client with accounts is not deleted. The
// accounts are in account-service's database, so it is asked first. If it
// cannot answer, the delete is refused rather than risk orphaning accounts.
//
// Deleted accounts are not listed, so they do not block; closed ones do (ADR-0036).
//
// The check and the delete are not atomic: an account created between them is
// orphaned. ADR-0035 records this.
func (s clientService) DeleteClientProfile(ctx context.Context, clientID, authorization string) error {
	if strings.TrimSpace(clientID) == "" {
		return fmt.Errorf("%w: client_id is required; %w", clientErrors.ErrBadInput, clientErrors.ErrInvalidClientID)
	}

	hasAccounts, err := s.accounts.HasAccounts(ctx, clientID, authorization)
	if err != nil {
		return err
	}
	if hasAccounts {
		return clientErrors.ErrClientHasAccounts
	}

	err = s.repo.DeleteClient(ctx, clientID)
	if err != nil {
		return err
	}

	return nil
}
