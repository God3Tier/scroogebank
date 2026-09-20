package accounts

import (
	"encoding/json"
	"errors"
	"net/http"

	accounts "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	"client-profile-management-service/src/internal/service"
)

type accountController struct {
	svc service.AccountService
}

func NewAccountController(svc service.AccountService) *accountController {
	return &accountController{svc: svc}
}

func (c *accountController) CreateAccount(w http.ResponseWriter, r *http.Request) {
	var account models.Account
	if err := json.NewDecoder(r.Body).Decode(&account); err != nil {
		http.Error(w, "bad input", http.StatusBadRequest)
		return
	}


	err := c.svc.CreateAccount(r.Context(), &account)
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

	w.WriteHeader(http.StatusCreated)
	w.Write([]byte(`{"status": "Account created successfully"}`))
}

func (c *accountController) DeleteAccount(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("id")
	if id == "" {
		http.Error(w, "id is required", http.StatusBadRequest)
		return
	}

	err := c.svc.DeleteAccount(r.Context(), id)
	if err != nil {
		switch {
		case errors.Is(err, accounts.ErrBadInput):
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		case errors.Is(err, accounts.ErrNotFound):
			http.Error(w, err.Error(), http.StatusNotFound)
			return
		default:
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
	}

	w.WriteHeader(http.StatusOK)
	w.Write([]byte(`{"status": "Account deleted successfully"}`))
}
