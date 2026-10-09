package account

import (
	accounterrors "account-service/src/internal/errors"
	"account-service/src/internal/models"
	"encoding/json"
	"errors"
	"net/http"
	"uuid"
)

// ListAccounts serves GET /api/accounts?clientId={uuid}. client-profile-
// management-service calls it before deleting a client, so a client with
// accounts is not deleted from under them.
func (c *accountController) ListAccounts(w http.ResponseWriter, r *http.Request, caller models.Caller) {
	clientID := r.URL.Query().Get("clientId")
	if _, err := uuid.Parse(clientID); err != nil {
		writeAccountError(w, http.StatusBadRequest, "clientId must be a UUID")
		return
	}

	accounts, err := c.svc.ListAccounts(r.Context(), clientID, caller)
	if err != nil {
		if errors.Is(err, accounterrors.ErrBadInput) {
			writeAccountError(w, http.StatusBadRequest, "invalid list request")
			return
		}
		writeAccountError(w, http.StatusInternalServerError, "unable to list accounts")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(accounts); err != nil {
		writeAccountError(w, http.StatusInternalServerError, "unable to encode accounts")
	}
}

func writeAccountError(w http.ResponseWriter, status int, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": message})
}
