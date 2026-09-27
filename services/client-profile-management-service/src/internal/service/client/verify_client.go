package client

import (
	clientErrors "client-profile-management-service/src/internal/errors"
	"context"
	"fmt"
	"strings"
)

func (s clientService) VerifyClientIdentity(ctx context.Context, clientID string) error {
	if strings.TrimSpace(clientID) == "" {
		return fmt.Errorf("%w: client_id is required; %w", clientErrors.ErrBadInput, clientErrors.ErrInvalidClientID)
	}

	// Some implementation to verify NRIC
	err := s.repo.VerifyClientIdentity(ctx, clientID)
	if err != nil {
		return err
	}

	return nil
}
