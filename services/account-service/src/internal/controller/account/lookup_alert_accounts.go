package account

import (
	accounterrors "account-service/src/internal/errors"
	"encoding/json"
	"errors"
	"net/http"
)

// MaxAlertLookupIDs bounds one alert-directory lookup (ADR-0066).
const MaxAlertLookupIDs = 200

// alertAccount is the least the event-alert service needs: which client the
// account belongs to, whether it can hold securities, and the agent who owns
// it now. The owner follows a client's reassignment (ADR-0060), so it is the
// client's current agent.
type alertAccount struct {
	ID           string `json:"id"`
	ClientID     string `json:"client_id"`
	Type         string `json:"type"`
	Status       string `json:"status"`
	Currency     string `json:"currency"`
	OwnerAgentID string `json:"owner_agent_id"`
}

// LookupAlertAccounts serves POST /internal/alerts/accounts/lookup. Only the
// event-alert service calls it, with its own service token (ADR-0066). Unknown
// and deleted accounts are left out.
func (c *accountController) LookupAlertAccounts(w http.ResponseWriter, r *http.Request) {
	var request lookupRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10)).Decode(&request); err != nil {
		writeAccountError(w, http.StatusBadRequest, "body must be {\"ids\": [...]}")
		return
	}
	seen := make(map[string]struct{}, len(request.IDs))
	ids := make([]string, 0, len(request.IDs))
	for _, id := range request.IDs {
		if _, duplicate := seen[id]; !duplicate {
			seen[id] = struct{}{}
			ids = append(ids, id)
		}
	}
	if len(ids) > MaxAlertLookupIDs {
		writeAccountError(w, http.StatusBadRequest, "at most 200 distinct ids per lookup")
		return
	}

	accounts, err := c.svc.LookupAccounts(r.Context(), ids)
	if err != nil {
		if errors.Is(err, accounterrors.ErrBadInput) {
			writeAccountError(w, http.StatusBadRequest, err.Error())
			return
		}
		writeAccountError(w, http.StatusInternalServerError, "unable to look up accounts")
		return
	}
	items := make([]alertAccount, 0, len(accounts))
	for _, account := range accounts {
		items = append(items, alertAccount{
			ID: account.ID, ClientID: account.ClientID, Type: account.AccountType,
			Status: account.Status, Currency: account.Currency, OwnerAgentID: account.OwnerAgentID,
		})
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string][]alertAccount{"items": items})
}
