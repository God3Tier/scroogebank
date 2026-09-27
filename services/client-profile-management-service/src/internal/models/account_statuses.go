package models

type AccountStatus string

const (
	AccountStatusActive   AccountStatus = "ACTIVE"
	AccountStatusInactive AccountStatus = "INACTIVE"
	AccountStatusPending  AccountStatus = "PENDING"
	AccountStatusClosed   AccountStatus = "CLOSED"
)

func (a AccountStatus) IsValid() bool {
	switch a{
		case AccountStatusActive, AccountStatusInactive, AccountStatusPending, AccountStatusClosed: 
		return true
	}

	return false
}