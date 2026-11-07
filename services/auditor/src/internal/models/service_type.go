package models

import "strings"

type ServiceType string

const (
	UserManagementServiceType ServiceType = "USER_MANAGEMENT"
	CRMServiceType            ServiceType = "CRM"
	TransactionServiceType    ServiceType = "TRANSACTION_SERVICE"
	EventAlertServiceType     ServiceType = "EVENT_ALERT_SERVICE"
	AuditService              ServiceType = "AUDIT_SERVICE"
	DashboardServiceType      ServiceType = "DASHBOARD"

	// CognitoTriggersServiceType is the Lambda Cognito runs after a login or a
	// forgotten-password reset (ADR-0040).
	CognitoTriggersServiceType ServiceType = "COGNITO_TRIGGERS"

	// EngagementServiceType is engagement-service: agents' calendar events,
	// reminder and invite emails, and client follow-up drafts (ADR-0051).
	EngagementServiceType         ServiceType = "ENGAGEMENT"
	RelationshipHealthServiceType ServiceType = "RELATIONSHIP_HEALTH_SERVICE"
)

func (s ServiceType) IsValid() bool {
	switch s {
	case UserManagementServiceType, CRMServiceType, TransactionServiceType, EventAlertServiceType, AuditService, DashboardServiceType, CognitoTriggersServiceType, EngagementServiceType, RelationshipHealthServiceType:
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
			strings.EqualFold("LOGOUT", action) ||
			strings.EqualFold("DELETE", action)

	case CRMServiceType:
		return strings.EqualFold("CREATE", action) ||
			strings.EqualFold("GET", action) ||
			strings.EqualFold("UPDATE", action) ||
			strings.EqualFold("DELETE", action)

	case TransactionServiceType:
		// CRUD is transaction-service's record of a transaction read
		// (ADR-0064); TRANSACTION_INGESTION is the import's.
		return strings.EqualFold("TRANSACTION_INGESTION", action) ||
			strings.EqualFold("CRUD", action)

	case EventAlertServiceType:
		return strings.EqualFold("EVENT_ALERT", action)
	case AuditService:
		return strings.EqualFold("SECURITY_VIOLATION", action) || strings.EqualFold("VALIDATION_REJECTED", action)

	case DashboardServiceType:
		return strings.EqualFold("DASHBOARD_VIEW", action)

	case CognitoTriggersServiceType:
		return strings.EqualFold("AUTHENTICATION", action)

	case EngagementServiceType:
		return strings.EqualFold("ENGAGEMENT", action)
	case RelationshipHealthServiceType:
		return strings.EqualFold("RELATIONSHIP_HEALTH", action)
	}

	return false
}
