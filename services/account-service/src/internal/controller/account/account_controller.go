package account

import (
	"account-service/src/internal/models"
	account "account-service/src/internal/service/account"
	"net/http"
)

type AccountController interface {
	CreateAccount(w http.ResponseWriter, r *http.Request)
	GetAccount(w http.ResponseWriter, r *http.Request)
	DeleteAccount(w http.ResponseWriter, r *http.Request)
	// ListAccounts takes the caller explicitly rather than from a header, so
	// scope can only come from the verified token the router passes in.
	ListAccounts(w http.ResponseWriter, r *http.Request, caller models.Caller)
}

type accountController struct {
	svc account.AccountService
}

func NewAccountController(svc account.AccountService) AccountController {
	return &accountController{svc: svc}
}
