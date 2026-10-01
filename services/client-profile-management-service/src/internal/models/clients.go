package models

import (
	"time"
	"uuid"
)

type Client struct {
	ClientID           uuid.UUID `json:"client_id"`
	FirstName          string    `json:"first_name"`
	LastName           string    `json:"last_name"`
	DateOfBirth        time.Time `json:"date_of_birth"`
	Gender             string    `json:"gender"`
	Email              string    `json:"email"`
	PhoneNumber        string    `json:"phone_number"`
	Address            string    `json:"address"`
	City               string    `json:"city"`
	State              string    `json:"state"`
	Country            string    `json:"country"`
	PostalCode         string    `json:"postal_code"`
	VerificationStatus string    `json:"verification_status"`
	CreatedByAgentID   string    `json:"created_by_agent_id"`
	CreatedAt          time.Time `json:"created_at"`
	UpdatedAt          time.Time `json:"updated_at"`
}
