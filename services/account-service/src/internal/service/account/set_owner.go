package account

import (
	accounterrors "account-service/src/internal/errors"
	"context"
	"fmt"
	"strings"
	"uuid"
)

// MaxOwnerClients bounds one reassignment, the same limit client-profile-
// management-service applies (ADR-0060).
const MaxOwnerClients = 1000

// maxOwnerAgentIDLength is owner_agent_id's column size.
const maxOwnerAgentIDLength = 128

// SetOwner checks the request before any account changes: every client ID
// must be a UUID, and the owner a Cognito sub that fits the column.
func (s *accountService) SetOwner(ctx context.Context, clientIDs []string, ownerAgentID string) (int64, error) {
	owner := strings.TrimSpace(ownerAgentID)
	if owner == "" || len(owner) > maxOwnerAgentIDLength {
		return 0, fmt.Errorf("%w: owner_agent_id is required, up to %d characters", accounterrors.ErrBadInput, maxOwnerAgentIDLength)
	}
	if len(clientIDs) == 0 || len(clientIDs) > MaxOwnerClients {
		return 0, fmt.Errorf("%w: 1 to %d client_ids", accounterrors.ErrBadInput, MaxOwnerClients)
	}
	ids := make([]string, 0, len(clientIDs))
	seen := make(map[string]bool, len(clientIDs))
	for _, raw := range clientIDs {
		id, err := uuid.Parse(strings.TrimSpace(raw))
		if err != nil {
			return 0, fmt.Errorf("%w: client_ids must be UUIDs", accounterrors.ErrBadInput)
		}
		if !seen[id.String()] {
			seen[id.String()] = true
			ids = append(ids, id.String())
		}
	}
	return s.repo.SetOwner(ctx, ids, owner)
}
