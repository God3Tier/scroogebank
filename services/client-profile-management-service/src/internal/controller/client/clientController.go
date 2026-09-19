package client

import (
	client "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	"client-profile-management-service/src/internal/service"
	"encoding/json"
	"errors"
	"net/http"
	"uuid"
)

type clientController struct {
	svc service.ClientService
}

func New(svc service.ClientService) ClientController {
	return &clientController{
		svc: svc,
	}
}

func (c *clientController) CreateClientProfile(w http.ResponseWriter, r *http.Request) {
	var cli models.Client
	err := json.NewDecoder(r.Body).Decode(&cli)
	if err != nil {
		http.Error(w, "bad input", http.StatusBadRequest)
		return
	}

	ctx := r.Context()
	err = c.svc.CreateClientProfile(ctx, cli)
	if err != nil {
		switch {
		case errors.Is(err, client.ErrDuplicateClient):
			http.Error(w, err.Error(), http.StatusConflict)
			return
		case errors.Is(err, client.ErrBadInput):
			http.Error(w, err.Error(), http.StatusBadRequest)
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
		case errors.Is(err, client.ErrClientNotFound):
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
	var cli models.Client
	err := json.NewDecoder(r.Body).Decode(&cli)
	if err != nil {
		http.Error(w, "bad input", http.StatusBadRequest)
		return
	}

	idStr := r.URL.Query().Get("id")
	cli.ClientID, err = uuid.Parse(idStr)
	if err != nil {
		http.Error(w, "bad input", http.StatusBadRequest)
		return
	}

	err = c.svc.UpdateClientInformation(ctx, cli)
	if err != nil {
		switch {
		case errors.Is(err, client.ErrBadInput):
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		default:
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
	}
	w.WriteHeader(http.StatusOK)
	w.Write([]byte(`{"status": "Client information updated successfully"}`))
}

func (c *clientController) GetClientProfile(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	idStr := r.URL.Query().Get("id")
	ret, err := c.svc.GetClientProfile(ctx, idStr)
	if err != nil {
		switch {
		case errors.Is(err, client.ErrClientNotFound):
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
		case errors.Is(err, client.ErrClientNotFound):
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
