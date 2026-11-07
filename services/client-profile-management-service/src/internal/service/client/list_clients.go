package client

import (
	clientErrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	"context"
	"fmt"
)

// MaxPageSize bounds one page of GET /api/clients (ADR-0043).
const MaxPageSize = 100

func (s clientService) ListClients(ctx context.Context, caller models.Caller, page, size int) (models.ClientPage, error) {
	if page < 0 {
		return models.ClientPage{}, fmt.Errorf("%w: page must not be negative", clientErrors.ErrBadInput)
	}
	if size < 1 || size > MaxPageSize {
		return models.ClientPage{}, fmt.Errorf("%w: size must be between 1 and %d", clientErrors.ErrBadInput, MaxPageSize)
	}

	// The scope comes from the verified token only: an agent can never list
	// another agent's clients. An empty owner means every client, so an
	// agent without a subject is refused rather than widened.
	ownerID := caller.Subject
	if caller.Admin {
		ownerID = ""
	} else if ownerID == "" {
		return models.ClientPage{}, clientErrors.ErrForbidden
	}
	clients, total, err := s.repo.ListClients(ctx, ownerID, page, size)
	if err != nil {
		return models.ClientPage{}, err
	}

	return models.ClientPage{
		Content:       clients,
		Page:          page,
		Size:          size,
		TotalElements: total,
		TotalPages:    int((total + int64(size) - 1) / int64(size)),
	}, nil
}
