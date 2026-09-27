package account

import (
	accounts "client-profile-management-service/src/internal/errors"
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
	err = c.svc.CreateAccount(r.Context(), res)
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
