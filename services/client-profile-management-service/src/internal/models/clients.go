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
	// Who verified the client, when, and whether the verification named any
	// identity documents; empty until the client is verified, and for clients
	// verified before ADR-0041. VerifiedWithDocuments false means verified
	// without a document.
	VerifiedByAgentID     string     `json:"verified_by_agent_id,omitempty"`
	VerifiedAt            *time.Time `json:"verified_at,omitempty"`
	VerifiedWithDocuments *bool      `json:"verified_with_documents,omitempty"`
	CreatedByAgentID      string     `json:"created_by_agent_id"`
	CreatedAt             time.Time  `json:"created_at"`
	UpdatedAt             time.Time  `json:"updated_at"`
}
