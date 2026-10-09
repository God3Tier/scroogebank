package models

import (
	"time"
	"uuid"
)

type Account struct {
	ID             uuid.UUID
	ClientID       uuid.UUID
	OwnerAgentID   string
	Type           AccountType
	Status         AccountStatus
	OpeningDate    time.Time
	InitialDeposit float64
	Currency       string
	BranchID       string
}
