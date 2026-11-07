package models

import "testing"

func TestServiceTypeIsValidAction(t *testing.T) {
	tests := []struct {
		name    string
		service ServiceType
		action  string
		want    bool
	}{
		{name: "CRM create", service: CRMServiceType, action: "CREATE", want: true},
		{name: "CRM get", service: CRMServiceType, action: "GET", want: true},
		{name: "CRM update", service: CRMServiceType, action: "UPDATE", want: true},
		{name: "CRM delete", service: CRMServiceType, action: "DELETE", want: true},
		{name: "CRM actions ignore case", service: CRMServiceType, action: "cReAtE", want: true},
		{name: "CRM rejects CRUD as action", service: CRMServiceType, action: "CRUD", want: false},
		{name: "CRM rejects unsupported action", service: CRMServiceType, action: "UPSERT", want: false},
		{name: "user management CRUD event type", service: UserManagementServiceType, action: "CRUD", want: true},
		{name: "user management role change", service: UserManagementServiceType, action: "ROLE_CHANGE", want: true},
		{name: "user management logout", service: UserManagementServiceType, action: "LOGOUT", want: true},
		{name: "user management delete", service: UserManagementServiceType, action: "DELETE", want: true},
		{name: "transaction ingestion event type", service: TransactionServiceType, action: "TRANSACTION_INGESTION", want: true},
		{name: "transaction read event type", service: TransactionServiceType, action: "CRUD", want: true},
		{name: "transaction service rejects dashboard views", service: TransactionServiceType, action: "DASHBOARD_VIEW", want: false},
		{name: "event alert event type", service: EventAlertServiceType, action: "EVENT_ALERT", want: true},
		{name: "audit security violation", service: AuditService, action: "SECURITY_VIOLATION", want: true},
		{name: "dashboard view event type", service: DashboardServiceType, action: "DASHBOARD_VIEW", want: true},
		{name: "dashboard rejects CRUD", service: DashboardServiceType, action: "CRUD", want: false},
		{name: "cognito triggers authentication event type", service: CognitoTriggersServiceType, action: "AUTHENTICATION", want: true},
		{name: "cognito triggers reject CRUD", service: CognitoTriggersServiceType, action: "CRUD", want: false},
		{name: "engagement event type", service: EngagementServiceType, action: "ENGAGEMENT", want: true},
		{name: "engagement rejects CRUD", service: EngagementServiceType, action: "CRUD", want: false},
		{name: "relationship health event type", service: RelationshipHealthServiceType, action: "RELATIONSHIP_HEALTH", want: true},
		{name: "relationship health rejects CRUD", service: RelationshipHealthServiceType, action: "CRUD", want: false},
		{name: "unknown service", service: ServiceType("UNKNOWN"), action: "CREATE", want: false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := tt.service.IsValidAction(tt.action); got != tt.want {
				t.Errorf("IsValidAction(%q) = %t, want %t", tt.action, got, tt.want)
			}
		})
	}
}
