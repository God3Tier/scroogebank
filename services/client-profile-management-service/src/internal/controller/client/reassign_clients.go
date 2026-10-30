package client

import (
	"client-profile-management-service/src/internal/auditnote"
	clientErr "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	"encoding/json"
	"errors"
	"net/http"
)

// maxReassignBodySize is far more than 100 client IDs need.
const maxReassignBodySize = 64 << 10

// ReassignClients serves POST /api/clients/reassignments, for admins only
// (ADR-0060). Each client moved is noted for the audit middleware, which saves
// one event per client with the agent before and after.
func (c clientController) ReassignClients(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	var request models.ReassignmentRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxReassignBodySize)).Decode(&request); err != nil {
		http.Error(w, "bad input: send to_user_id and one of from_user_id or client_ids", http.StatusBadRequest)
		return
	}

	// The admin's own token goes to user-service and account-service.
	moved, err := c.svc.ReassignClients(ctx, request, r.Header.Get("Authorization"))
	if err != nil {
		switch {
		case errors.Is(err, clientErr.ErrBadInput):
			http.Error(w, err.Error(), http.StatusBadRequest)
		case errors.Is(err, clientErr.ErrInvalidReceiver):
			http.Error(w, err.Error(), http.StatusBadRequest)
		case errors.Is(err, clientErr.ErrUserNotFound):
			http.Error(w, "user not found", http.StatusNotFound)
		case errors.Is(err, clientErr.ErrClientNotFound):
			http.Error(w, "client not found", http.StatusNotFound)
		case errors.Is(err, clientErr.ErrTooManyClients):
			http.Error(w, "this agent has more than 1000 clients; reassign them by client_ids", http.StatusConflict)
		case errors.Is(err, clientErr.ErrUserServiceUnavailable):
			http.Error(w, "user service unavailable", http.StatusServiceUnavailable)
		case errors.Is(err, clientErr.ErrAccountServiceUnavailable):
			http.Error(w, "account service unavailable", http.StatusServiceUnavailable)
		default:
			http.Error(w, "unable to reassign clients", http.StatusInternalServerError)
		}
		return
	}

	result := models.ReassignmentResult{Reassigned: len(moved), ClientIDs: make([]string, 0, len(moved))}
	for _, reassignment := range moved {
		auditnote.AddReassignment(ctx, reassignment)
		result.ClientIDs = append(result.ClientIDs, reassignment.ClientID)
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(result)
}
