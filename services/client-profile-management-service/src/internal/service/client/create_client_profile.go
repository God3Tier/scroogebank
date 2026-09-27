package client

import (
	"client-profile-management-service/src/internal/models"
	"context"
	"uuid"
)

func (s clientService) CreateClientProfile(ctx context.Context, client models.Client) error {
	if client.ClientID == uuid.Nil() {
		client.ClientID = uuid.New()
	}
	if err := validateClientProfile(client, true); err != nil {
		return err
	}

	err := s.repo.CreateClient(ctx, client)
	if err != nil {
		return err
	}

	return nil
}
