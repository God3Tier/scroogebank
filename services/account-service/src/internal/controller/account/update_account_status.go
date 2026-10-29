package account

import (
	"account-service/src/internal/audit"
	accounts "account-service/src/internal/errors"
	"account-service/src/internal/models"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
)

type updateAccountStatusInput struct {
	Status string `json:"status"`
}

// UpdateAccountStatus serves PATCH /api/accounts/{accountId} with a body of
// {"status": "ACTIVE"}: any of the four statuses, in any direction (ADR-0050).
// Only the status can be changed.
func (c *accountController) UpdateAccountStatus(w http.ResponseWriter, r *http.Request, caller models.Caller, accountID string) {
	var input updateAccountStatusInput
	if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
		http.Error(w, "bad input", http.StatusBadRequest)
		return
	}
	status := models.AccountStatus(normalizeAccountInput(input.Status))
	if !status.IsValid() {
		http.Error(w, "bad input", http.StatusBadRequest)
		return
	}

	// The caller's own token is forwarded to client-profile-management-service
	// to check that the client is verified.
	before, after, err := c.svc.UpdateAccountStatus(r.Context(), accountID, status, caller, r.Header.Get("Authorization"))
	if err != nil {
		switch {
		case errors.Is(err, accounts.ErrForbidden):
			http.Error(w, "admins do not manage client accounts", http.StatusForbidden)
		case errors.Is(err, accounts.ErrClientNotVerified):
			http.Error(w, "verify the client before changing an account's status", http.StatusForbidden)
		case errors.Is(err, accounts.ErrNotFound), errors.Is(err, accounts.ErrClientNotFound):
			http.Error(w, "account not found", http.StatusNotFound)
		case errors.Is(err, accounts.ErrClientServiceUnavailable):
			http.Error(w, "client service unavailable", http.StatusServiceUnavailable)
		default:
			http.Error(w, "unable to update account", http.StatusInternalServerError)
		}
		return
	}

	// The audit event records the status before and after.
	audit.Record(r.Context(), "status", string(before), string(after))

	// In lower case, as the account itself is stored and read (GET).
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]string{
		"account_id": accountID,
		"status":     strings.ToLower(string(after)),
	})
}
