package client

// TODO: Implement clientService
// Refactor code (too eepy at time of commit)

import (
	"client-profile-management-service/m/internal/service"
	"errors"
	"net/http"
)

type ClientController interface {
	CreateClientProfile(w http.ResponseWriter, r *http.Request)
	VerifyClientIdentity(w http.ResponseWriter, r *http.Request)

}

type clientController struct {
	svc service.ClientService
}

func New(svc service.ClientService) ClientController {
	return &clientController{
		svc: svc,
	}
}

func (c *clientController) CreateClientProfile(w http.ResponseWriter, r *http.Request) {
	err := c.svc.CreateClientProfile()
	if err != nil {

		switch {
			case errors.Is(err, service.ErrBadInput):
				http.Error(w, err.Error(), http.StatusBadRequest)
				return
			case errors.Is(err, service.ErrDuplicateClient):
				http.Error(w, err.Error(), http.StatusConflict)
				return
			default:
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
		}
	}

	w.WriteHeader(http.StatusCreated)
	w.Write([]byte(`{"status": "Client profile created successfully"}`))
}

func (c *clientController) VerifyClientIdentity(w http.ResponseWriter, r *http.Request) {
	c.svc.VerifyClientIdentity()
}
