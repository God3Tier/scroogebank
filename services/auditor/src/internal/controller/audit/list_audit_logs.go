package audit

import (
	auditv1 "auditor/src/gen/audit/v1"
	"auditor/src/internal/models"
	"auditor/src/internal/security"
	"context"
	"log"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/types/known/timestamppb"
)

// ListAuditLogs serves the complete audit trail to administrators only.
func (s Controller) ListAuditLogs(ctx context.Context, req *auditv1.ListAuditLogsRequest) (*auditv1.ListAuditLogsResponse, error) {
	caller, ok := security.CallerFrom(ctx)
	if !ok {
		return nil, status.Error(codes.Unauthenticated, "bearer access token required")
	}
	if !caller.Admin() {
		return nil, status.Error(codes.PermissionDenied, "only administrators can list the complete audit log")
	}
	if req.PageSize < 1 || req.PageSize > maxPageSize {
		return nil, status.Error(codes.InvalidArgument, "page_size must be between 1 and 100")
	}
	before, err := decodePageToken(req.PageToken)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid page_token")
	}

	logs, err := s.svc.ListAuditLogs(ctx, models.AuditLogFilter{
		BeforeSequenceNo: before,
		Limit:            int(req.PageSize) + 1,
	})
	if err != nil {
		log.Printf("Error {%s}\n", err.Error())
		return nil, status.Error(codes.Internal, "Something went wrong")
	}

	var next string
	if len(logs) > int(req.PageSize) {
		logs = logs[:req.PageSize]
		next = encodePageToken(logs[len(logs)-1].SequenceNo)
	}
	entries := make([]*auditv1.AuditLogEntry, 0, len(logs))
	for _, entry := range logs {
		entries = append(entries, &auditv1.AuditLogEntry{
			Id:            entry.ID,
			EventType:     entry.EventType,
			Action:        entry.Action,
			SourceService: string(entry.SourceService),
			ActorId:       entry.ActorID,
			Timestamp:     timestamppb.New(entry.Timestamp),
			Payload:       entry.Payload,
		})
	}
	return &auditv1.ListAuditLogsResponse{Entries: entries, NextPageToken: next}, nil
}
