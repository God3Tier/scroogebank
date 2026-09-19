package accounts

import "net/http"

type AccountController interface {
	CreateAccount(w http.ResponseWriter, r *http.Request)
	DeleteAccount(w http.ResponseWriter, r *http.Request)
}
