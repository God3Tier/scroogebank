package accounts

import (
	accounts "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	"client-profile-management-service/src/internal/service"
	"encoding/json"
	"errors"
	"net/http"
	"uuid"
)

type accountController struct {
	svc service.AccountService
}

func NewAccountController(svc service.AccountService) *accountController {
	return &accountController{svc: svc}
}

func (c *accountController) CreateAccount(w http.ResponseWriter, r *http.Request)  {
	var account models.Account
	account.ID = uuid.New()
	if err := json.NewDecoder(r.Body).Decode(&account); err != nil {
		return
	}
	err := c.svc.CreateAccount(r.Context(), &account)
	if err != nil {
		return
	}

	w.WriteHeader(http.StatusCreated)
	return
}

func (c *accountController) DeleteAccount(w http.ResponseWriter, r *http.Request)  {
	id := r.URL.Query().Get("id")
	if id == "" {
		http.Error(w, "id is required", http.StatusBadRequest)
		return
	}

	err := c.svc.DeleteAccount(r.Context(), id)
	if err != nil {
		switch {
		case errors.Is(err, accounts.ErrNotFound):
			http.Error(w, err.Error(), http.StatusNotFound)
			return
		default:
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
	}
	return
}
