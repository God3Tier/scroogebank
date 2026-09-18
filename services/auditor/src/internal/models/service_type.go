package models

import "strings"

type ServiceType string

const (
	UserManagementServiceType ServiceType = "USER_MANAGEMENT"
	CRMServiceType            ServiceType = "CRM"
	TransactionServiceType    ServiceType = "TRANSACTION_SERVICE"
	AuditService              ServiceType = "AUDIT_SERVICE"
)

func (s ServiceType) IsValid() bool {
	switch s {
	case UserManagementServiceType, CRMServiceType, TransactionServiceType, AuditService:
		return true
	}

	return false
}

func (s ServiceType) IsValidAction(action string) bool {
	switch s {
	case UserManagementServiceType:
		return strings.EqualFold("CRUD", action)

	case CRMServiceType:
		return strings.EqualFold("CRUD", action)

	case TransactionServiceType:
		return strings.EqualFold("TRANSACTION_INGESTION", action)
		
	case AuditService:
		return strings.EqualFold("SECURITY_VIOLATION", action) || strings.EqualFold("VALIDATION_REJECTED", action)
	}

	return false
}
