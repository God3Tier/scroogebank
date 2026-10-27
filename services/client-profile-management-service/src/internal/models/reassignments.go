package models

// Limits on one reassignment (ADR-0060).
const (
	// MaxReassignedClients is the most clients one request moves.
	MaxReassignedClients = 1000
	// MaxReassignClientIDs is the most client IDs one request may name.
	MaxReassignClientIDs = 100
)

// ReassignmentRequest is the body of POST /api/clients/reassignments. It
// names the agent who receives the clients and exactly one of FromUserID (all
// of that user's clients) or ClientIDs (these clients). Users are named by
// user-service's IDs, which Admin Home already has.
type ReassignmentRequest struct {
	ToUserID   string   `json:"to_user_id"`
	FromUserID string   `json:"from_user_id,omitempty"`
	ClientIDs  []string `json:"client_ids,omitempty"`
}

// Reassignment is one client moved from one agent to another: what its audit
// event records.
type Reassignment struct {
	ClientID    string
	FromAgentID string
	ToAgentID   string
}

// ReassignmentResult is the answer to a reassignment.
type ReassignmentResult struct {
	Reassigned int      `json:"reassigned"`
	ClientIDs  []string `json:"client_ids"`
}
