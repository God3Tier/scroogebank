package audit

import (
	"auditor/src/internal/models"
	orm "auditor/src/internal/repository/orm"
)

func mapToAuditLog(auditLog *orm.AuditLog) models.AuditLog {
	return models.AuditLog{
		Id:            auditLog.ID,
		SequenceNo:    auditLog.SequenceNo.Int64,
		EventType:     auditLog.EventType.String,
		Action:        auditLog.Action.String,
		ActorId:       auditLog.ActorID.String,
		SourceService: models.ServiceType(auditLog.SourceService.String),
		Timestamp:     auditLog.Timestamp.Time,
		Payload:       string(auditLog.Payload.JSON),
		ParentHash:    auditLog.ParentHash.String,
		Hash:          auditLog.Hash.String,
	}
}
