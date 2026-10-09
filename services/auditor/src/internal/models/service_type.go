package models

import "strings"

type ServiceType string

const (
	UserManagementServiceType ServiceType = "USER_MANAGEMENT"
	CRMServiceType            ServiceType = "CRM"
	TransactionServiceType    ServiceType = "TRANSACTION_SERVICE"
	AuditService              ServiceType = "AUDIT_SERVICE"
	DashboardServiceType      ServiceType = "DASHBOARD"
)

func (s ServiceType) IsValid() bool {
	switch s {
	case UserManagementServiceType, CRMServiceType, TransactionServiceType, AuditService, DashboardServiceType:
		return true
	}

	return false
}

func (s ServiceType) IsValidAction(action string) bool {
	switch s {
	case UserManagementServiceType:
		return strings.EqualFold("CRUD", action) ||
			strings.EqualFold("CREATE", action) ||
			strings.EqualFold("READ", action) ||
			strings.EqualFold("UPDATE", action) ||
			strings.EqualFold("DISABLE", action) ||
			strings.EqualFold("PASSWORD_RESET", action) ||
			strings.EqualFold("ROLE_CHANGE", action) ||
			strings.EqualFold("LOGOUT", action)

	case CRMServiceType:
		return strings.EqualFold("CREATE", action) ||
			strings.EqualFold("GET", action) ||
			strings.EqualFold("UPDATE", action) ||
			strings.EqualFold("DELETE", action)

	case TransactionServiceType:
		return strings.EqualFold("TRANSACTION_INGESTION", action)

	case AuditService:
		return strings.EqualFold("SECURITY_VIOLATION", action) || strings.EqualFold("VALIDATION_REJECTED", action)

	case DashboardServiceType:
		return strings.EqualFold("DASHBOARD_VIEW", action)
	}

	return false
}
