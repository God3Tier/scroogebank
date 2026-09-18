package client

// TODO: Implement clientService
// Refactor code (too eepy at time of commit)

import (
	"client-profile-management-service/src/internal/models"
	"client-profile-management-service/src/internal/service"
	"encoding/json"
	"errors"
	"net/http"
)

type ClientController interface {
	CreateClientProfile(w http.ResponseWriter, r *http.Request)
	VerifyClientIdentity(w http.ResponseWriter, r *http.Request)
	UpdateClientInformation(w http.ResponseWriter, r *http.Request)
	GetClientProfile(w http.ResponseWriter, r *http.Request)
	DeleteClientProfile(w http.ResponseWriter, r *http.Request)
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
	var client models.Client
	err := json.NewDecoder(r.Body).Decode(&client)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}


	err = c.svc.CreateClientProfile(client)
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

func (c *clientController) UpdateClientInformation(w http.ResponseWriter, r *http.Request) {
	c.svc.UpdateClientInformation()
}

func (c *clientController) GetClientProfile(w http.ResponseWriter, r *http.Request) {
	c.svc.GetClientProfile()
}

func (c *clientController) DeleteClientProfile(w http.ResponseWriter, r *http.Request) {
	c.svc.DeleteClientProfile()
}
