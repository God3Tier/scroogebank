package audit

import (
	"context"

	"auditor/src/internal/models"
	orm "auditor/src/internal/repository/orm"

	"github.com/aarondl/sqlboiler/v4/queries/qm"
	pkgerrors "github.com/pkg/errors"
)

// ListAuditLogs returns every audit entry, newest first. Unlike ListActivities,
// it does not remove reads, failed requests or background events.
func (i impl) ListAuditLogs(ctx context.Context, filter models.AuditLogFilter) ([]models.AuditLog, error) {
	mods := []qm.QueryMod{}
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
