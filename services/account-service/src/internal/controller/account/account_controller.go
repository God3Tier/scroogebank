package account

import (
	"account-service/src/internal/models"
	account "account-service/src/internal/service/account"
	"net/http"
)

type AccountController interface {
	// CreateAccount takes the caller from the verified token too: an agent
	// may open an account only for a verified client.
	CreateAccount(w http.ResponseWriter, r *http.Request, caller models.Caller)
	GetAccount(w http.ResponseWriter, r *http.Request)
	DeleteAccount(w http.ResponseWriter, r *http.Request)
	// ListAccounts takes the caller explicitly rather than from a header, so
	// scope can only come from the verified token the router passes in.
	ListAccounts(w http.ResponseWriter, r *http.Request, caller models.Caller)
	LookupAccounts(w http.ResponseWriter, r *http.Request)
}

type accountController struct {
	svc account.AccountService
}

func NewAccountController(svc account.AccountService) AccountController {
	return &accountController{svc: svc}
}
