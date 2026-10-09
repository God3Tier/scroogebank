package client

import (
	clientErr "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	"encoding/json"
	"errors"
	"net/http"
	"time"

	"uuid"
)

// UpdateClientInput contains the profile fields that may be changed. The
// client ID comes from the route, while verification state and timestamps are
// managed by the system.
type UpdateClientInput struct {
	FirstName   string    `json:"first_name"`
	LastName    string    `json:"last_name"`
	DateOfBirth time.Time `json:"date_of_birth"`
	Gender      string    `json:"gender"`
	Email       string    `json:"email"`
	PhoneNumber string    `json:"phone_number"`
	Address     string    `json:"address"`
	City        string    `json:"city"`
	State       string    `json:"state"`
	Country     string    `json:"country"`
	PostalCode  string    `json:"postal_code"`
}

func (c clientController) UpdateClientInformation(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	var cli UpdateClientInput
	err := json.NewDecoder(r.Body).Decode(&cli)
	if err != nil {
		http.Error(w, "bad input", http.StatusBadRequest)
		return
	}

	idStr := r.URL.Query().Get("id")
	clientID, err := uuid.Parse(idStr)
	if err != nil {
		http.Error(w, "bad input", http.StatusBadRequest)
		return
	}

	client := models.Client{
		ClientID:    clientID,
		FirstName:   cli.FirstName,
		LastName:    cli.LastName,
		DateOfBirth: cli.DateOfBirth,
		Gender:      cli.Gender,
		Email:       cli.Email,
		PhoneNumber: cli.PhoneNumber,
		Address:     cli.Address,
		City:        cli.City,
		State:       cli.State,
		Country:     cli.Country,
		PostalCode:  cli.PostalCode,
	}
	
	err = c.svc.UpdateClientInformation(ctx, client)
	if err != nil {
		switch {
		case errors.Is(err, clientErr.ErrBadInput):
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		case errors.Is(err, clientErr.ErrClientNotFound):
			http.Error(w, err.Error(), http.StatusNotFound)
			return
		default:
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
	}
	w.WriteHeader(http.StatusOK)
	w.Write([]byte(`{"status": "Client information updated successfully"}`))
}
