package corrupted_log

import (
	"time"

	"auditor/src/internal/models"
)

// auditLogResponse and corruptedAuditLogResponse are the HTTP representation.
// They keep JSON naming and optional fields at the controller boundary.
type auditLogResponse struct {
	ID            string    `json:"id"`
	SequenceNo    int64     `json:"sequence_no"`
	EventID       string    `json:"event_id,omitempty"`
	EventType     string    `json:"event_type"`
	Action        string    `json:"action"`
	ActorID       string    `json:"actor_id"`
	SourceService string    `json:"source_service"`
	Timestamp     time.Time `json:"timestamp"`
	Payload       string    `json:"payload"`
	ParentHash    string    `json:"parent_hash"`
	Hash          string    `json:"hash"`
}

type corruptedAuditLogResponse struct {
	ID           string           `json:"id"`
	AuditLogID   string           `json:"audit_log_id,omitempty"`
	Reason       string           `json:"reason,omitempty"`
	Payload      auditLogResponse `json:"payload"`
	DateOfDetect time.Time        `json:"date_of_detect"`
}

type insertCorruptedAuditLogRequest struct {
	ID      string           `json:"id"`
	Payload auditLogResponse `json:"payload"`
}

type statusResponse struct {
	Status string `json:"status"`
}

func corruptedAuditLogToResponse(log models.CorruptedAuditLog) corruptedAuditLogResponse {
	return corruptedAuditLogResponse{
		ID: log.ID, AuditLogID: log.AuditLogID, Reason: string(log.Reason),
		Payload: auditLogToResponse(log.Payload), DateOfDetect: log.DateOfDetect,
	}
}

func auditLogToResponse(log models.AuditLog) auditLogResponse {
	return auditLogResponse{
		ID: log.ID, SequenceNo: log.SequenceNo, EventID: log.EventID,
		EventType: log.EventType, Action: log.Action, ActorID: log.ActorID,
		SourceService: string(log.SourceService), Timestamp: log.Timestamp,
		Payload: log.Payload, ParentHash: log.ParentHash, Hash: log.Hash,
	}
}

func (request insertCorruptedAuditLogRequest) model() models.CorruptedAuditLog {
	return models.CorruptedAuditLog{ID: request.ID, Payload: models.AuditLog{
		ID: request.Payload.ID, SequenceNo: request.Payload.SequenceNo,
		EventID: request.Payload.EventID, EventType: request.Payload.EventType,
		Action: request.Payload.Action, ActorID: request.Payload.ActorID,
		SourceService: models.ServiceType(request.Payload.SourceService),
		Timestamp:     request.Payload.Timestamp, Payload: request.Payload.Payload,
		ParentHash: request.Payload.ParentHash, Hash: request.Payload.Hash,
	}}
}
