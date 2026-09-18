package audit

import (
	"auditor/src/internal/models"
	orm "auditor/src/internal/repository/orm"
	"context"
	"database/sql"
	"errors"

	"github.com/aarondl/sqlboiler/v4/queries/qm"
	pkgerrors "github.com/pkg/errors"
)

func (i impl) GetChainByOffset(ctx context.Context, startChain int, offset int) ([]models.AuditLog, error) {
	raw, err := orm.AuditLogs(
		qm.OrderBy("sequence_no"),
		qm.Where("sequence_no >= ?", startChain),
		qm.And("sequence_no <= ?", startChain+offset),
	).All(ctx, i.db)

	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return nil, pkgerrors.WithStack(err)
	}

	response := make([]models.AuditLog, 0, len(raw))

	for _, log := range raw {
		response = append(response, mapToAuditLog(log))
	}

	return response, nil
}
