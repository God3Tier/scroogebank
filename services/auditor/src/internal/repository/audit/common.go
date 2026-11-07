package audit

import (
	"auditor/src/internal/models"
	orm "auditor/src/internal/repository/orm"
)

func mapToAuditLog(auditLog *orm.AuditLog) models.AuditLog {
	return models.AuditLog{
		ID:            auditLog.ID,
		SequenceNo:    auditLog.SequenceNo,
		EventID:       auditLog.EventID.String,
		EventType:     auditLog.EventType,
		Action:        auditLog.Action,
		ActorID:       auditLog.ActorID,
		SourceService: models.ServiceType(auditLog.SourceService),
		Timestamp:     auditLog.Timestamp,
		Payload:       string(auditLog.Payload),
		ParentHash:    auditLog.ParentHash,
		Hash:          auditLog.Hash,
	}
}
