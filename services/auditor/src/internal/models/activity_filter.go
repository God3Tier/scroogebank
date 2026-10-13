package models

import "time"

// ActivityFilter selects entries for the activity feed (ListActivities). A
// zero value in any field means "no restriction" on that field.
type ActivityFilter struct {
	ActorId          string
	From             time.Time // inclusive
	To               time.Time // exclusive
	BeforeSequenceNo int64     // only entries with a lower sequence_no
	Limit            int
}

// ActivityActions are the actions the activity feed shows for each source
// service: changes CRM users make to records (ADR-0021 decision 3). Every
// other entry, such as reads (READ, GET), sign-ins and sign-outs, dashboard
// views and events from transaction ingestion or chain-verify, stays in the
// audit log but is not in the feed. A client's verification is recorded as
// UPDATE. A user's own forgotten-password reset comes from the Cognito
// triggers (ADR-0040).
var ActivityActions = []struct {
	Source  ServiceType
	Actions []string
}{
	{UserManagementServiceType, []string{"CREATE", "UPDATE", "DISABLE", "ROLE_CHANGE", "PASSWORD_RESET", "DELETE"}},
	{CRMServiceType, []string{"CREATE", "UPDATE", "DELETE"}},
	{CognitoTriggersServiceType, []string{"PASSWORD_RESET"}},
}
