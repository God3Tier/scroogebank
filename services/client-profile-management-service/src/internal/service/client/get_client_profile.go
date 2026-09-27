package client

import (
	clientErrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	"context"
	"fmt"
	"strings"
)

func (s clientService) GetClientProfile(ctx context.Context, clientID string) (models.Client, error) {
	if strings.TrimSpace(clientID) == "" {
		return models.Client{}, fmt.Errorf("%w: client_id is required; %w", clientErrors.ErrBadInput, clientErrors.ErrInvalidClientID)
	}

	client, err := s.repo.GetClientProfile(ctx, clientID)
	if err != nil {
		return models.Client{}, err
	}

	return client, nil
}
