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

	ctx := r.Context()
	err = c.svc.CreateClientProfile(ctx, client)
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
	ctx := r.Context()
	idStr := r.URL.Query().Get("id")
	err := c.svc.VerifyClientIdentity(ctx, idStr)
	if err != nil {
		switch {
			case errors.Is(err, service.ErrClientNotFound):
				http.Error(w, err.Error(), http.StatusNotFound)
				return
			default:
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
		}
	}

	w.WriteHeader(http.StatusOK)
	w.Write([]byte(`{"status": "Client identity verified successfully"}`))
}

func (c *clientController) UpdateClientInformation(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	idStr := r.URL.Query().Get("id")
	var client models.Client
	client.ClientID = idStr
	err := json.NewDecoder(r.Body).Decode(&client)
	if err != nil {
		switch {
			case errors.Is(err, service.ErrBadInput):
				http.Error(w, err.Error(), http.StatusBadRequest)
				return
			default:
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
		}
	}

	c.svc.UpdateClientInformation(ctx, client)
	w.WriteHeader(http.StatusOK)
	w.Write([]byte(`{"status": "Client information updated successfully"}`))
}

func (c *clientController) GetClientProfile(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	idStr := r.URL.Query().Get("id")
	ret, err := c.svc.GetClientProfile(ctx, idStr)
	if err != nil {
		switch {
			case errors.Is(err, service.ErrClientNotFound):
				http.Error(w, err.Error(), http.StatusNotFound)
				return
			default:
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
		}
	}

	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(ret)
}

func (c *clientController) DeleteClientProfile(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	idStr := r.URL.Query().Get("id")
	err := c.svc.DeleteClientProfile(ctx, idStr)
	if err != nil {
		switch {
			case errors.Is(err, service.ErrClientNotFound):
				http.Error(w, err.Error(), http.StatusNotFound)
				return
			default:
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
		}
	}

	w.WriteHeader(http.StatusOK)
	w.Write([]byte(`{"status": "Client profile deleted successfully"}`))
}
