package audit

import (
	"auditor/src/gen"
	"auditor/src/internal/models"
	auditSvc "auditor/src/internal/service/audit"
	"context"
	"database/sql"
	"errors"
	"log"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/types/known/timestamppb"
)

// Controller is the placeholder for auditor request handlers.
type Controller struct {
	gen.UnimplementedAuditServiceServer
	svc auditSvc.Service
}

func (s Controller) GetAuditLog(ctx context.Context, req *gen.GetAuditLogRequest) (*gen.GetAuditLogResponse, error) {
	serviceType := models.ServiceType(req.Service)

	if !serviceType.IsValid() {
		return nil, status.Error(codes.InvalidArgument, "Invalid service type")
	}

	logsRaw, err := s.svc.RetrieveAuditLog(ctx, serviceType)

	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, status.Error(codes.NotFound, "audit log not found")
		}
		log.Printf("Error {%s}\n", err.Error())
		return nil, status.Error(codes.Internal, "Something went wrong")
	}

	logs := make([]*gen.AuditLog, 0, len(logsRaw))

	for _, log := range logsRaw {
		logs = append(logs, &gen.AuditLog{
			EventType: log.EventType,
			Action:    log.Action,
			Timestamp: timestamppb.New(log.Timestamp),
			Payload:   log.Payload,
		})
	}

	return &gen.GetAuditLogResponse{Logs: logs}, nil
}

func New(svc auditSvc.Service) Controller {
	return Controller{
		svc: svc,
	}
}
