package audit

import (
	"context"

	"auditor/src/internal/models"
	orm "auditor/src/internal/repository/orm"

	"github.com/aarondl/sqlboiler/v4/queries/qm"
	pkgerrors "github.com/pkg/errors"
)

// GetChainPage lets chain-verify read the chain a page at a time, so it never
// holds the whole chain in memory.
func (i impl) GetChainPage(ctx context.Context, afterSequenceNo int64, limit int) ([]models.AuditLog, error) {
	raw, err := orm.AuditLogs(
		qm.Where("sequence_no > ?", afterSequenceNo),
		qm.OrderBy("sequence_no"),
		qm.Limit(limit),
	).All(ctx, i.db)

	if err != nil {
		return nil, pkgerrors.WithStack(err)
	}

	response := make([]models.AuditLog, 0, len(raw))

	for _, audit := range raw {
		response = append(response, mapToAuditLog(audit))
	}

	return response, nil
}
