package models

import "time"

// VerifiedStatus is the verification status a client gets when an agent
// verifies them. Clients verified before ADR-0041 hold "verified", so compare
// it case-insensitively.
const VerifiedStatus = "VERIFIED"

// ClientDocument describes one identity document uploaded for a client
// (ADR-0041). Its content is never part of it, and the original file name is
// not kept, because it may contain the client's name.
type ClientDocument struct {
	DocumentID        string    `json:"document_id"`
	ClientID          string    `json:"client_id"`
	ContentType       string    `json:"content_type"`
	SizeBytes         int       `json:"size_bytes"`
	UploadedByAgentID string    `json:"uploaded_by_agent_id"`
	UploadedAt        time.Time `json:"uploaded_at"`
	// VerificationEvidence is set on the documents the agent named when
	// verifying the client.
	VerificationEvidence bool `json:"verification_evidence"`
}
