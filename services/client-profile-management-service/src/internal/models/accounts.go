package models

import (
	"time"
	"uuid"
)

type Account struct {
	ID              uuid.UUID `json:"id"`
	ClientID        uuid.UUID `json:"client_id"`
	Type            string    `json:"account_type"`
	Status          string    `json:"status"`
	OpeningDate     time.Time `json:"opening_date"`
	InitialDeposit  float64   `json:"initial_deposit"`
	Currency        string    `json:"currency"`
	BranchID        string    `json:"branch_id"`
	UpdatedAt       time.Time `json:"updated_at"`
	CreatedAt       time.Time `json:"created_at"`
}
