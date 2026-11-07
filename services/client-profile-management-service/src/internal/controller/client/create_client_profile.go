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

// CreateClientInput contains the client details required to create a profile.
// The profile ID is assigned by the controller. X-Agent-ID represents the
// authenticated actor ID supplied by the caller's auth context.
type CreateClientInput struct {
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
	AgentID     string    `json:"agent_id"`
	PostalCode  string    `json:"postal_code"`
}

func (c clientController) CreateClientProfile(w http.ResponseWriter, r *http.Request) {
	var cli CreateClientInput
	err := json.NewDecoder(r.Body).Decode(&cli)
	if err != nil {
		http.Error(w, "bad input", http.StatusBadRequest)
		return
	}

	ctx := r.Context()
	profile := models.Client{
		FirstName:       cli.FirstName,
		LastName:        cli.LastName,
		DateOfBirth:     cli.DateOfBirth,
		Gender:          cli.Gender,
		Email:           cli.Email,
		PhoneNumber:     cli.PhoneNumber,
		Address:         cli.Address,
		City:            cli.City,
		State:           cli.State,
		Country:         cli.Country,
		PostalCode:      cli.PostalCode,
		ClientID:        uuid.New(),
		AssignedAgentID: r.Header.Get("X-Agent-ID"),
	}
	err = c.svc.CreateClientProfile(ctx, profile)
	if err != nil {
		switch {
		case errors.Is(err, clientErr.ErrDuplicateClient):
			http.Error(w, err.Error(), http.StatusConflict)
			return
		case errors.Is(err, clientErr.ErrBadInput):
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		default:
			writeServerError(w, r, err)
			return
		}
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(map[string]string{
		"client_id": profile.ClientID.String(),
		"status":    "Client profile created successfully",
	})
}
