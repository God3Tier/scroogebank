package models

import "time"

type Client struct {
	ClientID           string
	FirstName          string
	LastName           string
	DateOfBirth        time.Time
	Gender             string
	Email              string
	PhoneNumber        string
	Address            string
	City               string
	State              string
	Country            string
	PostalCode         string
	VerificationStatus string
	CreatedByAgentID   string
	CreatedAt          time.Time
	UpdatedAt          time.Time
}
