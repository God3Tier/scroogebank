package client

import (
	"context"
	"fmt"
	"strings"

	clientErrors "client-profile-management-service/src/internal/errors"
)

func (s clientService) DeleteClientProfile(ctx context.Context, clientID string) error {
	if strings.TrimSpace(clientID) == "" {
		return fmt.Errorf("%w: client_id is required; %w", clientErrors.ErrBadInput, clientErrors.ErrInvalidClientID)
	}

	err := s.repo.DeleteClient(ctx, clientID)
	if err != nil {
		return err
	}

	return nil
}
