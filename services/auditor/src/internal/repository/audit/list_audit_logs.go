package audit

import (
	"context"

	"auditor/src/internal/models"
	orm "auditor/src/internal/repository/orm"

	"github.com/aarondl/sqlboiler/v4/queries/qm"
	pkgerrors "github.com/pkg/errors"
)

// ListAuditLogs returns entries matching filter, newest first. Paging is by
// sequence number rather than offset, so entries appended while a caller is
// paging do not shift the pages it has not read yet.
func (i impl) ListAuditLogs(ctx context.Context, filter models.AuditLogFilter) ([]models.AuditLog, error) {
	mods := []qm.QueryMod{}
	if filter.SourceService != "" {
		mods = append(mods, qm.Where("source_service = ?", string(filter.SourceService)))
	}
	if filter.ActorId != "" {
		mods = append(mods, qm.Where("actor_id = ?", filter.ActorId))
	}
	// "timestamp" is quoted because it is also a type name in SQL.
	if !filter.From.IsZero() {
		mods = append(mods, qm.Where(`"timestamp" >= ?`, filter.From))
	}
	if !filter.To.IsZero() {
		mods = append(mods, qm.Where(`"timestamp" < ?`, filter.To))
	}
	if filter.BeforeSequenceNo > 0 {
		mods = append(mods, qm.Where("sequence_no < ?", filter.BeforeSequenceNo))
	}
	mods = append(mods, qm.OrderBy("sequence_no desc"), qm.Limit(filter.Limit))

	raw, err := orm.AuditLogs(mods...).All(ctx, i.db)
	if err != nil {
		return nil, pkgerrors.WithStack(err)
	}

	response := make([]models.AuditLog, 0, len(raw))
	for _, auditLog := range raw {
		response = append(response, mapToAuditLog(auditLog))
	}
	return response, nil
}
