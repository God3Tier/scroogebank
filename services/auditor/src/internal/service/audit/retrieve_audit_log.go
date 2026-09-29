package audit

import (
	"auditor/src/internal/models"
	"context"
	"time"
)

type RetrieveAuditLogResponse struct {
	EventType string
	Action    string
	Timestamp time.Time
	Payload   string
}

func (i impl) RetrieveAuditLog(ctx context.Context, serviceType models.ServiceType) ([]RetrieveAuditLogResponse, error) {

	// TODO: implement the get thing from db
	raw, err := i.repo.GetAuditByService(ctx, serviceType)
	if err != nil {
		return nil, err
	}

	response := make([]RetrieveAuditLogResponse, 0, len(raw))

	for _, auditLog := range raw {
		response = append(response, mapAuditLog(auditLog))
	}
	
	return response, nil
}


func mapAuditLog(raw models.AuditLog) RetrieveAuditLogResponse {
	return RetrieveAuditLogResponse{
		EventType: raw.EventType, 
		Action: raw.Action, 
		Timestamp: raw.Timestamp, 
		Payload: raw.Payload, 
	}
}