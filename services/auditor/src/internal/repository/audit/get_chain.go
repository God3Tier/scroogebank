package audit

import (
	"context"

	"auditor/src/internal/models"
	orm "auditor/src/internal/repository/orm"

	"github.com/aarondl/sqlboiler/v4/queries/qm"
	pkgerrors "github.com/pkg/errors"
)

func (i impl) GetChain(ctx context.Context) ([]models.AuditLog, error) {
	raw, err := orm.AuditLogs(
		qm.OrderBy("sequence_no"),
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
