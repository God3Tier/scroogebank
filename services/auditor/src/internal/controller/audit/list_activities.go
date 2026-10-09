package audit

import (
	auditv1 "auditor/src/gen/audit/v1"
	"auditor/src/internal/models"
	"context"
	"log"
	"strconv"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/types/known/timestamppb"
)

const maxPageSize = 100

// ListActivities serves the activity feed's entries, newest first. It is
// called over the internal network by other services (user-service's
// activity feed); it is not exposed through the API gateway.
func (s Controller) ListActivities(ctx context.Context, req *auditv1.ListActivitiesRequest) (*auditv1.ListActivitiesResponse, error) {
	if req.PageSize < 1 || req.PageSize > maxPageSize {
		return nil, status.Error(codes.InvalidArgument, "page_size must be between 1 and 100")
	}
	before, err := decodePageToken(req.PageToken)
	if err != nil {
		return nil, status.Error(codes.InvalidArgument, "invalid page_token")
	}

	filter := models.AuditLogFilter{
		ActorId:          req.ActorId,
		BeforeSequenceNo: before,
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

	var next string
	if len(logs) > int(req.PageSize) {
		logs = logs[:req.PageSize]
		next = encodePageToken(logs[len(logs)-1].SequenceNo)
	}

	activities := make([]*auditv1.Activity, 0, len(logs))
	for _, entry := range logs {
		activities = append(activities, &auditv1.Activity{
			Id:            entry.Id,
			SourceService: string(entry.SourceService),
			Action:        entry.Action,
			ActorId:       entry.ActorId,
			Timestamp:     timestamppb.New(entry.Timestamp),
			Payload:       entry.Payload,
		})
	}

	return &auditv1.ListActivitiesResponse{Activities: activities, NextPageToken: next}, nil
}

// A page token is the sequence number of the last entry returned. Callers
// treat it as opaque. Paging by sequence number rather than offset means
// entries appended while a caller pages do not shift the pages not yet read.
func encodePageToken(sequenceNo int64) string {
	return strconv.FormatInt(sequenceNo, 10)
}

func decodePageToken(token string) (int64, error) {
	if token == "" {
		return 0, nil
	}
	sequenceNo, err := strconv.ParseInt(token, 10, 64)
	if err != nil || sequenceNo < 1 {
		return 0, strconv.ErrSyntax
	}
	return sequenceNo, nil
}
