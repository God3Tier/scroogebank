package models

import (
	"uuid"
)

type CreateAccountInput struct {
	AccountID      uuid.UUID
	ClientID       uuid.UUID
	Type           AccountType
	Status         AccountStatus
	InitialDeposit float64
	Currency       string
	BranchID       string
}
