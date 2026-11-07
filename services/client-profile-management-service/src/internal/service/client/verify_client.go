package client

import (
	clientErrors "client-profile-management-service/src/internal/errors"
	"context"
	"fmt"
	"strings"
	"uuid"
)

// VerifyClientIdentity records the agent's decision. The agent checks the
// documents; naming them is optional, and each one named must be the
// client's, which the repository confirms. With none, the client is verified
// without a document, and recorded as such (ADR-0041 decision 5).
func (s clientService) VerifyClientIdentity(ctx context.Context, clientID, agentID string, documentIDs []string) error {
	if strings.TrimSpace(clientID) == "" {
		return fmt.Errorf("%w: client_id is required; %w", clientErrors.ErrBadInput, clientErrors.ErrInvalidClientID)
	}
	if strings.TrimSpace(agentID) == "" {
		return fmt.Errorf("%w: the verifying agent is unknown", clientErrors.ErrBadInput)
	}
	ids, err := distinctDocumentIDs(documentIDs)
	if err != nil {
		return err
	}
	return s.repo.VerifyClientIdentity(ctx, clientID, agentID, ids)
}

// distinctDocumentIDs checks that each ID is a UUID and drops repeats. The
// result is empty, never nil, when there are none.
func distinctDocumentIDs(documentIDs []string) ([]string, error) {
	seen := map[string]bool{}
	ids := make([]string, 0, len(documentIDs))
	for _, raw := range documentIDs {
		parsed, err := uuid.Parse(strings.TrimSpace(raw))
		if err != nil {
			return nil, fmt.Errorf("%w: document_ids must hold document IDs", clientErrors.ErrBadInput)
		}
		id := parsed.String()
		if !seen[id] {
			seen[id] = true
			ids = append(ids, id)
		}
	}
	return ids, nil
}
