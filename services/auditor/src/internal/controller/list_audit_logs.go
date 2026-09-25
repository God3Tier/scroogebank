package controller

import (
	"auditor/src/gen"
	"auditor/src/internal/models"
	"context"
	"log"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/types/known/timestamppb"
)

const maxListPageSize = 100

// ListAuditLogs serves filtered, paged reads of the audit log, newest first.
// It is called over the internal network by other services (the user
// service's activity feed); it is not exposed through the API gateway.
func (s Controller) ListAuditLogs(ctx context.Context, req *gen.ListAuditLogsRequest) (*gen.ListAuditLogsResponse, error) {
	if req.PageSize < 1 || req.PageSize > maxListPageSize {
		return nil, status.Error(codes.InvalidArgument, "page_size must be between 1 and 100")
	}
	if req.BeforeSequenceNo < 0 {
		return nil, status.Error(codes.InvalidArgument, "before_sequence_no must not be negative")
	}

	serviceType := models.ServiceType(req.SourceService)
	if req.SourceService != "" && !serviceType.IsValid() {
		return nil, status.Error(codes.InvalidArgument, "Invalid service type")
	}

	filter := models.AuditLogFilter{
		SourceService:    serviceType,
		ActorId:          req.ActorId,
		BeforeSequenceNo: req.BeforeSequenceNo,
		// One extra row shows whether another page exists.
		Limit: int(req.PageSize) + 1,
	}
	if req.From != nil {
		if err := req.From.CheckValid(); err != nil {
			return nil, status.Error(codes.InvalidArgument, "invalid from")
		}
		filter.From = req.From.AsTime()
	}
	if req.To != nil {
		if err := req.To.CheckValid(); err != nil {
			return nil, status.Error(codes.InvalidArgument, "invalid to")
		}
		filter.To = req.To.AsTime()
	}
	if !filter.From.IsZero() && !filter.To.IsZero() && !filter.From.Before(filter.To) {
		return nil, status.Error(codes.InvalidArgument, "from must be before to")
	}

	logs, err := s.svc.ListAuditLogs(ctx, filter)
	if err != nil {
		log.Printf("Error {%s}\n", err.Error())
		return nil, status.Error(codes.Internal, "Something went wrong")
	}

	var next int64
	if len(logs) > int(req.PageSize) {
		logs = logs[:req.PageSize]
		next = logs[len(logs)-1].SequenceNo
	}

	entries := make([]*gen.AuditLogEntry, 0, len(logs))
	for _, entry := range logs {
		entries = append(entries, &gen.AuditLogEntry{
			Id:            entry.Id,
			SequenceNo:    entry.SequenceNo,
			EventType:     entry.EventType,
			Action:        entry.Action,
			SourceService: string(entry.SourceService),
			ActorId:       entry.ActorId,
			Timestamp:     timestamppb.New(entry.Timestamp),
			Payload:       entry.Payload,
		})
	}

	return &gen.ListAuditLogsResponse{Entries: entries, NextBeforeSequenceNo: next}, nil
}
