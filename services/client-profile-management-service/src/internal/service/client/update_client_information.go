package client

import (
	"client-profile-management-service/src/internal/models"
	"context"
)

func (s clientService) UpdateClientInformation(ctx context.Context, client models.Client) error {
	if err := validateClientProfile(client, false); err != nil {
		return err
	}

	err := s.repo.UpdateClientInformation(ctx, client.ClientID.String(), client)
	if err != nil {
		return err
	}

	return nil
}
