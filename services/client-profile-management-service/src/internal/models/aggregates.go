package models

import "time"

// Caller is the verified user an aggregate is computed for: admins see every
// client, agents see the clients they created. It comes from the token, never
// from request data (ADR-0039).
type Caller struct {
	Subject string
	Admin   bool
}

// Window is a reporting period of whole days in Singapore time, both ends
// included (ADR-0038).
type Window struct {
	From time.Time
	To   time.Time
}

// DailyCount is one day of a series, in Singapore time.
type DailyCount struct {
	Date  string `json:"date"`
	Count int64  `json:"count"`
}

// ClientStats are the client figures the business dashboard shows. Deleted
// clients are never counted (ADR-0036).
type ClientStats struct {
	// Total and ByVerificationStatus cover every client in scope, whenever
	// it was created. Statuses are upper-cased, because clients verified
	// before ADR-0041 hold "verified"; newer ones hold "VERIFIED".
	Total                int64            `json:"total"`
	ByVerificationStatus map[string]int64 `json:"byVerificationStatus"`
	// CreatedInWindow and DailyCreated cover clients created in the window.
	CreatedInWindow int64        `json:"createdInWindow"`
	DailyCreated    []DailyCount `json:"dailyCreated"`
	// ByAgent is filled only for an admin who asks for it.
	ByAgent []AgentClientCounts `json:"byAgent,omitempty"`
}

// AgentClientCounts are one agent's clients created in the window, keyed by
// the agent's Cognito sub.
type AgentClientCounts struct {
	AgentID          string `json:"agentId" boil:"agent_id"`
	ClientsOnboarded int64  `json:"clientsOnboarded" boil:"clients_onboarded"`
	Verified         int64  `json:"verified" boil:"verified"`
	Unverified       int64  `json:"unverified" boil:"unverified"`
}

// ClientActionItems is the caller's work queue: clients still not verified
// some days after they were created, oldest first.
type ClientActionItems struct {
	StaleUnverified      []ClientRef `json:"staleUnverified"`
	StaleUnverifiedTotal int64       `json:"staleUnverifiedTotal"`
}

// ClientRef identifies a client in a work queue. The caller can already open
// each of these clients.
type ClientRef struct {
	ClientID  string    `json:"clientId" boil:"client_id"`
	FirstName string    `json:"firstName" boil:"first_name"`
	LastName  string    `json:"lastName" boil:"last_name"`
	CreatedAt time.Time `json:"createdAt" boil:"created_at"`
}

// TeamOnboarding is what the repository finds for a team comparison: the
// caller's own count and the median across the comparison group. It never
// leaves the service as it is: the service hides the median for small groups.
type TeamOnboarding struct {
	ClientsOnboarded int64
	Median           *float64
	CohortSize       int64
}

// TeamComparison compares the caller's clients onboarded with the team's
// median over a preset window ending today (ADR-0038). It holds no other
// agent's figure, and no median when the group is too small to hide one.
type TeamComparison struct {
	Days             int      `json:"days"`
	From             string   `json:"from"`
	To               string   `json:"to"`
	ClientsOnboarded int64    `json:"clientsOnboarded"`
	TeamMedian       *float64 `json:"teamMedian"`
	CohortSize       int64    `json:"cohortSize"`
}
