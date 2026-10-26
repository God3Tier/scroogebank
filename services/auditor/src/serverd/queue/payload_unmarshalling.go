package queue

import (
	"auditor/src/internal/models"
	"encoding/json"
	"fmt"
	"strings"
	"time"
)

type ValidEvent struct {
	// EventID identifies this event for de-duplication. SQS standard queues
	// deliver at-least-once, so the same message can be received more than
	// once; the auditor uses EventID to skip an event it has already stored.
	EventID       string          `json:"event_id"`
	EventType     string          `json:"event_type"`
	Action        string          `json:"action"`
	SourceService string          `json:"source_service"`
	ActorID       string          `json:"actor_id"`
	Payload       json.RawMessage `json:"payload"`
}

type ValidCRUDPayload struct {
	AttributeName string `json:"attribute_name"`
	BeforeValue   string `json:"before_value"`
	AfterValue    string `json:"after_value"`
	AgentID       string `json:"agent_id"`
	ClientID      string `json:"client_id"`
	DateTime      string `json:"datetime"`
}

// ValidUserAuditPayload describes user-service endpoint activity. It records
// the target resource and operation metadata without copying names, email
// addresses, passwords, or request bodies into the audit chain.
type ValidUserAuditPayload struct {
	ResourceType string `json:"resource_type"`
	ResourceID   string `json:"resource_id"`
	Endpoint     string `json:"endpoint"`
	HTTPMethod   string `json:"http_method"`
	StatusCode   int    `json:"status_code"`
	DateTime     string `json:"datetime"`
	UserRole     string `json:"user_role,omitempty"`
}

type ValidFileCompletedPayload struct {
	FileName         string         `json:"file_name"`
	ProcessingStart  time.Time      `json:"processing_start"`
	ProcessingEnd    time.Time      `json:"processing_end"`
	Status           string         `json:"status"`
	RecordsProcessed int            `json:"records_processed"`
	RecordsAccepted  int            `json:"records_accepted"`
	RecordsRejected  int            `json:"records_rejected"`
	ParseFailures    []ParseFailure `json:"parse_failures,omitempty"`
}

type ParseFailure struct {
	RecordRef string `json:"record_ref"` // identifies which record this was
	Reason    string `json:"reason"`     // why it was rejected
}

type ProcessingErrorPayload struct {
	FileName string `json:"file_name"`
	Stage    string `json:"stage"` // "FILE_FORMAT" | "CONNECTION" | "SYSTEM_EXCEPTION"
	Error    string `json:"error"`
}

// ValidDashboardViewPayload records one business dashboard view: who looked
// is the actor, and the payload holds what they looked at. It carries the
// scope, window and panel statuses only, never figures or client data
// (ADR-0033).
type ValidDashboardViewPayload struct {
	Scope       string            `json:"scope"` // "ADMIN" | "AGENT"
	WindowFrom  string            `json:"window_from"`
	WindowTo    string            `json:"window_to"`
	Panels      map[string]string `json:"panels"` // panel name to "OK" | "UNAVAILABLE"
	ResultCount int               `json:"result_count"`
	CacheHit    bool              `json:"cache_hit"`
	DateTime    string            `json:"datetime"`
}

// ValidAuthenticationPayload records a login or a forgotten-password reset
// that Cognito reported through its triggers; the actor is the user (ADR-0040).
// It names the user pool and app client only, never the user's attributes.
type ValidAuthenticationPayload struct {
	UserPoolID    string `json:"user_pool_id"`
	AppClientID   string `json:"app_client_id,omitempty"`
	TriggerSource string `json:"trigger_source"`
	DateTime      string `json:"datetime"`
}

// authenticationTriggers are the actions the Cognito triggers send, each with
// the trigger source it must come from.
var authenticationTriggers = map[string]string{
	"LOGIN":          "PostAuthentication_Authentication",
	"PASSWORD_RESET": "PostConfirmation_ConfirmForgotPassword",
}

// userAuditActions lists, for each resource type user-service reports, the
// actions it may send about it.
var userAuditActions = map[string]map[string]bool{
	"USER": {
		"CREATE": true, "READ": true, "UPDATE": true, "DISABLE": true,
		"PASSWORD_RESET": true, "ROLE_CHANGE": true, "LOGOUT": true,
	},
	// An account's transactions, and every account's for an admin (ADR-0043),
	// read through user-service until transaction-service served them itself
	// (ADR-0064). Kept for events still queued from before.
	"ACCOUNT": {"READ": true},
	"TRANSACTION": {"READ": true},
	// The auditor's corrupted-log table, managed through user-service.
	"CORRUPTED_AUDIT_LOG": {"CREATE": true, "READ": true, "DELETE": true},
}

// userAuditNeedsResourceID reports whether a successful event must name the
// resource it acted on. Reading the corrupted-log list or the transaction list
// names none.
func userAuditNeedsResourceID(resourceType, action string) bool {
	return action != "READ" || (resourceType != "CORRUPTED_AUDIT_LOG" && resourceType != "TRANSACTION")
}

// transactionReadResources are what transaction-service reads (ADR-0064): an
// account's transactions, and every account's for an admin. The first names
// its account; the list names none.
var transactionReadResources = map[string]bool{"ACCOUNT": true, "TRANSACTION": true}

// validateTransactionReadPayload checks transaction-service's record of a
// read. It has the fields user-service sent for these reads before ADR-0064.
func validateTransactionReadPayload(envelope ValidEvent) error {
	if envelope.Action != "READ" {
		return fmt.Errorf("TRANSACTION_SERVICE is not permitted to send CRUD action %s", envelope.Action)
	}
	if strings.TrimSpace(envelope.ActorID) == "" {
		return fmt.Errorf("TRANSACTION_SERVICE read event requires actor_id")
	}
	var payload ValidUserAuditPayload
	if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
		return fmt.Errorf("invalid transaction read payload: %w", err)
	}
	if !transactionReadResources[payload.ResourceType] {
		return fmt.Errorf("invalid transaction read resource_type: %q", payload.ResourceType)
	}
	missingID := payload.ResourceType == "ACCOUNT" && payload.ResourceID == "" && payload.StatusCode < 400
	if missingID || payload.Endpoint == "" ||
		payload.HTTPMethod == "" || payload.StatusCode < 100 || payload.StatusCode > 599 {
		return fmt.Errorf("invalid transaction read payload: missing or invalid operation fields")
	}
	if _, err := time.Parse(time.RFC3339Nano, payload.DateTime); err != nil {
		return fmt.Errorf("invalid transaction read payload datetime: %w", err)
	}
	return nil
}

// dashboardViewActions are the dashboard's endpoints, one action each.
var dashboardViewActions = map[string]bool{
	"VIEW_OVERVIEW":          true,
	"VIEW_PORTFOLIO":         true,
	"VIEW_TRANSACTIONS":      true,
	"VIEW_ACTION_ITEMS":      true,
	"VIEW_AGENT_PERFORMANCE": true,
}

// engagementActions are what engagement-service records (agent-engagement
// plan §11): calendar changes, the invite and reminder emails its dispatcher
// sends, and client follow-up drafts.
var engagementActions = map[string]bool{
	"EVENT_CREATE":       true,
	"EVENT_UPDATE":       true,
	"EVENT_CANCEL":       true,
	"INVITE_SENT":        true,
	"INVITE_FAILED":      true,
	"REMINDER_SENT":      true,
	"REMINDER_FAILED":    true,
	"REMINDER_MISSED":    true,
	"DRAFT_GENERATE":     true,
	"DRAFT_DISCARD":      true,
	"EMAIL_SEND":         true,
	"EMAIL_SEND_FAILED":  true,
	"PREFERENCES_UPDATE": true,
}

// ValidEngagementPayload is engagement-service's outbox envelope, the same
// shape as event-alert-service's (ADR-0051 decision 7). data holds IDs,
// statuses and changed field names, never titles, email bodies or addresses.
type ValidEngagementPayload struct {
	EventID      string                     `json:"event_id"`
	EventVersion int                        `json:"event_version"`
	OccurredAt   time.Time                  `json:"occurred_at"`
	Data         map[string]json.RawMessage `json:"data"`
}

type ValidEventAlertPayload struct {
	EventID      string          `json:"event_id"`
	EventVersion int             `json:"event_version"`
	OccurredAt   time.Time       `json:"occurred_at"`
	Data         json.RawMessage `json:"data"`
}

func validatePayload(envelope ValidEvent) error {
	svc := models.ServiceType(envelope.SourceService)
	if !svc.IsValid() {
		return fmt.Errorf("unknown source_service: %s", envelope.SourceService)
	}
	if svc == models.CRMServiceType {
		if envelope.EventType != "CRUD" {
			return fmt.Errorf("%s is not permitted to send event_type %s",
				envelope.SourceService, envelope.EventType)
		}
		if !svc.IsValidAction(envelope.Action) {
			return fmt.Errorf("%s is not permitted to send action %s",
				envelope.SourceService, envelope.Action)
		}
	} else if !svc.IsValidAction(envelope.EventType) {
		return fmt.Errorf("%s is not permitted to send event_type %s",
			envelope.SourceService, envelope.EventType)
	}

	switch svc {
	case models.TransactionServiceType:
		return validateTransactionPayload(envelope)

	case models.UserManagementServiceType:
		return validateUserManagementPayload(envelope)

	case models.DashboardServiceType:
		return validateDashboardPayload(envelope)

	case models.CRMServiceType:
		var p ValidCRUDPayload
		if err := json.Unmarshal(envelope.Payload, &p); err != nil {
			return fmt.Errorf("invalid CRUDPayload: %w", err)
		}
		return nil

	case models.EventAlertServiceType:
		return validateEventAlertPayload(envelope)

	case models.CognitoTriggersServiceType:
		return validateAuthenticationPayload(envelope)

	case models.EngagementServiceType:
		return validateEngagementPayload(envelope)

	default:
		return fmt.Errorf("unhandled source_service: %s", envelope.SourceService)
	}
}

func validateEventAlertPayload(envelope ValidEvent) error {
	allowedActions := map[string]bool{
		"CLASSIFICATION_RECORDED":     true,
		"BRIEFING_GENERATED":          true,
		"GENERATED":                   true,
		"DECISION_RECORDED":           true,
		"FEEDBACK_RECORDED":           true,
		"OPERATIONAL_CONTROL_CHANGED": true,
	}
	if !allowedActions[envelope.Action] {
		return fmt.Errorf("unrecognized action for EVENT_ALERT_SERVICE: %s", envelope.Action)
	}

	var payload ValidEventAlertPayload
	if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
		return fmt.Errorf("invalid EventAlertPayload: %w", err)
	}
	if payload.EventID == "" || payload.EventVersion < 1 || payload.OccurredAt.IsZero() || len(payload.Data) == 0 {
		return fmt.Errorf("invalid EventAlertPayload: required audit metadata is missing")
	}
	return nil
}

// validateEngagementPayload accepts engagement-service's actions. The actor is
// the agent's sub, or "system" for what its dispatcher sends.
func validateEngagementPayload(envelope ValidEvent) error {
	if !engagementActions[envelope.Action] {
		return fmt.Errorf("unrecognized action for ENGAGEMENT: %s", envelope.Action)
	}
	if strings.TrimSpace(envelope.ActorID) == "" {
		return fmt.Errorf("ENGAGEMENT event requires actor_id")
	}
	var payload ValidEngagementPayload
	if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
		return fmt.Errorf("invalid EngagementPayload: %w", err)
	}
	if payload.EventID == "" || payload.EventVersion < 1 || payload.OccurredAt.IsZero() || payload.Data == nil {
		return fmt.Errorf("invalid EngagementPayload: required audit metadata is missing")
	}
	return nil
}

func validateUserManagementPayload(envelope ValidEvent) error {
	if !models.UserManagementServiceType.IsValidAction(envelope.Action) || strings.EqualFold(envelope.Action, "CRUD") {
		return fmt.Errorf("unrecognized action for USER_MANAGEMENT: %s", envelope.Action)
	}
	if strings.TrimSpace(envelope.ActorID) == "" {
		return fmt.Errorf("USER_MANAGEMENT event requires actor_id")
	}
	var payload ValidUserAuditPayload
	if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
		return fmt.Errorf("invalid UserAuditPayload: %w", err)
	}
	if payload.ResourceType == "" {
		// Accept the previous generic CRUD payload during rollout so messages
		// already queued before the user-service deployment remain consumable.
		var legacy ValidCRUDPayload
		if err := json.Unmarshal(envelope.Payload, &legacy); err != nil {
			return fmt.Errorf("invalid legacy CRUDPayload: %w", err)
		}
		return nil
	}
	action := strings.ToUpper(envelope.Action)
	actions, known := userAuditActions[payload.ResourceType]
	if !known {
		return fmt.Errorf("invalid UserAuditPayload resource_type: %q", payload.ResourceType)
	}
	if !actions[action] {
		return fmt.Errorf("USER_MANAGEMENT is not permitted to send action %s for %s", envelope.Action, payload.ResourceType)
	}
	missingID := payload.ResourceID == "" && payload.StatusCode < 400 && userAuditNeedsResourceID(payload.ResourceType, action)
	if missingID || payload.Endpoint == "" ||
		payload.HTTPMethod == "" || payload.StatusCode < 100 || payload.StatusCode > 599 {
		return fmt.Errorf("invalid UserAuditPayload: missing or invalid user operation fields")
	}
	if _, err := time.Parse(time.RFC3339Nano, payload.DateTime); err != nil {
		return fmt.Errorf("invalid UserAuditPayload datetime: %w", err)
	}
	return nil
}

func validateAuthenticationPayload(envelope ValidEvent) error {
	triggerSource, known := authenticationTriggers[envelope.Action]
	if !known {
		return fmt.Errorf("unrecognized action for COGNITO_TRIGGERS: %s", envelope.Action)
	}
	if strings.TrimSpace(envelope.ActorID) == "" {
		return fmt.Errorf("COGNITO_TRIGGERS event requires actor_id")
	}
	var payload ValidAuthenticationPayload
	if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
		return fmt.Errorf("invalid AuthenticationPayload: %w", err)
	}
	if payload.UserPoolID == "" {
		return fmt.Errorf("invalid AuthenticationPayload: missing user_pool_id")
	}
	if payload.TriggerSource != triggerSource {
		return fmt.Errorf("%s must come from trigger source %s, not %q", envelope.Action, triggerSource, payload.TriggerSource)
	}
	if _, err := time.Parse(time.RFC3339Nano, payload.DateTime); err != nil {
		return fmt.Errorf("invalid AuthenticationPayload datetime: %w", err)
	}
	return nil
}

func validateDashboardPayload(envelope ValidEvent) error {
	if !dashboardViewActions[envelope.Action] {
		return fmt.Errorf("unrecognized action for DASHBOARD: %s", envelope.Action)
	}
	if strings.TrimSpace(envelope.ActorID) == "" {
		return fmt.Errorf("DASHBOARD event requires actor_id")
	}
	var payload ValidDashboardViewPayload
	if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
		return fmt.Errorf("invalid DashboardViewPayload: %w", err)
	}
	if payload.Scope != "ADMIN" && payload.Scope != "AGENT" {
		return fmt.Errorf("invalid DashboardViewPayload scope: %q", payload.Scope)
	}
	// The per-agent table is admin-only, so an agent-scoped view of it means
	// the dashboard's own check failed.
	if envelope.Action == "VIEW_AGENT_PERFORMANCE" && payload.Scope != "ADMIN" {
		return fmt.Errorf("VIEW_AGENT_PERFORMANCE requires scope ADMIN")
	}
	from, fromErr := time.Parse(time.DateOnly, payload.WindowFrom)
	to, toErr := time.Parse(time.DateOnly, payload.WindowTo)
	if fromErr != nil || toErr != nil || to.Before(from) {
		return fmt.Errorf("invalid DashboardViewPayload window: %q to %q", payload.WindowFrom, payload.WindowTo)
	}
	if len(payload.Panels) == 0 || payload.ResultCount < 0 || payload.ResultCount > len(payload.Panels) {
		return fmt.Errorf("invalid DashboardViewPayload panels")
	}
	if _, err := time.Parse(time.RFC3339Nano, payload.DateTime); err != nil {
		return fmt.Errorf("invalid DashboardViewPayload datetime: %w", err)
	}
	return nil
}

func validateTransactionPayload(envelope ValidEvent) error {
	if strings.EqualFold(envelope.EventType, "CRUD") {
		return validateTransactionReadPayload(envelope)
	}
	switch envelope.Action {
	case "FILE_PROCESSING_COMPLETED":
		var p ValidFileCompletedPayload
		if err := json.Unmarshal(envelope.Payload, &p); err != nil {
			return fmt.Errorf("invalid FileCompletedPayload: %w", err)
		}
		return nil

	case "FILE_FORMAT_INVALID", "CONNECTION_FAILURE", "SYSTEM_EXCEPTION":
		var p ProcessingErrorPayload
		if err := json.Unmarshal(envelope.Payload, &p); err != nil {
			return fmt.Errorf("invalid ProcessingErrorPayload: %w", err)
		}
		return nil

	default:
		return fmt.Errorf("unrecognized action for TRANSACTION_SERVICE: %s", envelope.Action)
	}
}
