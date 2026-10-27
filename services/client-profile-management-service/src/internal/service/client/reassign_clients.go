package client

import (
	"context"
	"fmt"
	"strings"
	"uuid"

	clientErrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
)

// ReassignClients moves clients to another agent for an admin (ADR-0060).
// The receiver must be an enabled agent, looked up in user-service with the
// admin's token, and not the agent the clients come from. The clients'
// accounts move in account-service while the clients are locked, with the
// same token; if either service cannot answer, nothing moves.
func (s clientService) ReassignClients(ctx context.Context, request models.ReassignmentRequest, authorization string) ([]models.Reassignment, error) {
	toUserID, fromUserID, clientIDs, err := validateReassignment(request)
	if err != nil {
		return nil, err
	}

	receiver, err := s.users.Lookup(ctx, toUserID, authorization)
	if err != nil {
		return nil, err
	}
	if receiver.Role != "AGENT" || !receiver.Enabled {
		return nil, clientErrors.ErrInvalidReceiver
	}
	fromAgentID := ""
	if fromUserID != "" {
		previous, err := s.users.Lookup(ctx, fromUserID, authorization)
		if err != nil {
			return nil, err
		}
		if previous.Subject == receiver.Subject {
			return nil, clientErrors.ErrInvalidReceiver
		}
		fromAgentID = previous.Subject
	}

	return s.repo.ReassignClients(ctx, fromAgentID, clientIDs, receiver.Subject, func(ctx context.Context, ids []string) error {
		return s.accounts.SetOwner(ctx, ids, receiver.Subject, authorization)
	})
}

// validateReassignment checks the request's shape before any service is
// asked: user IDs are UUIDs, and exactly one of from_user_id and client_ids
// is given, client_ids holding 1 to MaxReassignClientIDs distinct UUIDs.
func validateReassignment(request models.ReassignmentRequest) (toUserID, fromUserID string, clientIDs []string, err error) {
	toUserID, err = parseID("to_user_id", request.ToUserID)
	if err != nil {
		return "", "", nil, err
	}
	hasFrom := strings.TrimSpace(request.FromUserID) != ""
	if hasFrom == (len(request.ClientIDs) > 0) {
		return "", "", nil, fmt.Errorf("%w: give exactly one of from_user_id and client_ids", clientErrors.ErrBadInput)
	}
	if hasFrom {
		fromUserID, err = parseID("from_user_id", request.FromUserID)
		if err != nil {
			return "", "", nil, err
		}
		if fromUserID == toUserID {
			return "", "", nil, clientErrors.ErrInvalidReceiver
		}
		return toUserID, fromUserID, nil, nil
	}
	if len(request.ClientIDs) > models.MaxReassignClientIDs {
		return "", "", nil, fmt.Errorf("%w: at most %d client_ids", clientErrors.ErrBadInput, models.MaxReassignClientIDs)
	}
	seen := make(map[string]bool, len(request.ClientIDs))
	for _, raw := range request.ClientIDs {
		id, err := parseID("client_ids", raw)
		if err != nil {
			return "", "", nil, err
		}
		if seen[id] {
			return "", "", nil, fmt.Errorf("%w: client_ids must be distinct", clientErrors.ErrBadInput)
		}
		seen[id] = true
		clientIDs = append(clientIDs, id)
	}
	return toUserID, "", clientIDs, nil
}

func parseID(name, raw string) (string, error) {
	id, err := uuid.Parse(strings.TrimSpace(raw))
	if err != nil {
		return "", fmt.Errorf("%w: %s must be a UUID", clientErrors.ErrBadInput, name)
	}
	return id.String(), nil
}
