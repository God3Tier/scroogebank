package client

import "net/http"

type ClientController interface {
	CreateClientProfile(w http.ResponseWriter, r *http.Request)
	VerifyClientIdentity(w http.ResponseWriter, r *http.Request)
	UpdateClientInformation(w http.ResponseWriter, r *http.Request)
	GetClientProfile(w http.ResponseWriter, r *http.Request)
	DeleteClientProfile(w http.ResponseWriter, r *http.Request)
}
