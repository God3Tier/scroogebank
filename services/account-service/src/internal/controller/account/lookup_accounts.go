package account

import (
	accounterrors "account-service/src/internal/errors"
	"encoding/json"
	"errors"
	"net/http"
)

type lookupRequest struct {
	IDs []string `json:"ids"`
}

// accountRef is what the import needs to link a transaction: the account's
// client, and its currency, which the transaction's must match (ADR-0037).
type accountRef struct {
	ID       string `json:"id"`
	ClientID string `json:"client_id"`
	Currency string `json:"currency"`
}

// LookupAccounts serves POST /internal/accounts/lookup. Only the transaction
// import calls it, with a service token (ADR-0037).
func (c *accountController) LookupAccounts(w http.ResponseWriter, r *http.Request) {
	var request lookupRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10)).Decode(&request); err != nil {
		writeAccountError(w, http.StatusBadRequest, "body must be {\"ids\": [...]}")
		return
	}

	accounts, err := c.svc.LookupAccounts(r.Context(), request.IDs)
	if err != nil {
		if errors.Is(err, accounterrors.ErrBadInput) {
			writeAccountError(w, http.StatusBadRequest, err.Error())
			return
		}
		writeAccountError(w, http.StatusInternalServerError, "unable to look up accounts")
		return
	}
	found := make([]accountRef, 0, len(accounts))
	for _, account := range accounts {
		found = append(found, accountRef{ID: account.ID, ClientID: account.ClientID, Currency: account.Currency})
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string][]accountRef{"accounts": found})
}
