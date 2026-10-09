package account

import (
	accounts "account-service/src/internal/errors"
	"encoding/json"
	"errors"
	"net/http"
	"uuid"
)

func (c *accountController) CreateAccount(w http.ResponseWriter, r *http.Request) {
	var account CreateAccountInput
	if err := json.NewDecoder(r.Body).Decode(&account); err != nil {
		http.Error(w, "bad input", http.StatusBadRequest)
		return
	}

	res, err := account.ValidateAccountAndParse()
	if err != nil {
		http.Error(w, "bad input", http.StatusBadRequest)
		return
	}
	res.AccountID = uuid.New()

	// TODO Refactor such that service layer owns the struct
	// The caller's own token is forwarded to client-profile-management-service,
	// so the client lookup is held to the caller's access, not this service's.
	err = c.svc.CreateAccount(r.Context(), res, r.Header.Get("Authorization"))
	if err != nil {
		switch {
		case errors.Is(err, accounts.ErrBadInput):
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		case errors.Is(err, accounts.ErrDuplicateAccount):
			http.Error(w, err.Error(), http.StatusConflict)
			return
		case errors.Is(err, accounts.ErrClientNotFound):
			http.Error(w, err.Error(), http.StatusNotFound)
			return
		case errors.Is(err, accounts.ErrClientServiceUnavailable):
			http.Error(w, "client service unavailable", http.StatusServiceUnavailable)
			return
		default:
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(map[string]string{
		"account_id": res.AccountID.String(),
		"status":     "Account created successfully",
	})
}
