package models

import (
	"uuid"
)

// CreateAccountInput is a validated account-creation request.
//
// OwnerAgentID is the Cognito sub of the agent who owns ClientID. The service
// layer copies it from client-profile-management-service; it is never read
// from the request body.
type CreateAccountInput struct {
	AccountID      uuid.UUID
	ClientID       uuid.UUID
	OwnerAgentID   string
	Type           AccountType
	Status         AccountStatus
	InitialDeposit float64
	Currency       string
	BranchID       string
}
