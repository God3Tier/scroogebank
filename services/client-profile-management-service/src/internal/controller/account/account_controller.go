package account

import (
	account "client-profile-management-service/src/internal/service/account"
	"net/http"
)

type AccountController interface {
	CreateAccount(w http.ResponseWriter, r *http.Request)
	GetAccount(w http.ResponseWriter, r *http.Request)
	DeleteAccount(w http.ResponseWriter, r *http.Request)
}

type accountController struct {
	svc account.AccountService
}

func NewAccountController(svc account.AccountService) AccountController {
	return &accountController{svc: svc}
}
