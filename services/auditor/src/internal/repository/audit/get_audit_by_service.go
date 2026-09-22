package audit

import (
	"context"

	"auditor/src/internal/models"
	orm "auditor/src/internal/repository/orm"

	"github.com/aarondl/sqlboiler/v4/queries/qm"
)

func (i impl) GetAuditByService(ctx context.Context, service models.ServiceType) ([]models.AuditLog, error) {
	raw, err := orm.AuditLogs(
		qm.Where("source_service = ?", string(service)),
	).All(ctx, i.db)

	if err != nil {
		return nil, err
	}

	response := make([]models.AuditLog, 0, len(raw))

	for _, audit := range raw {
		response = append(response, mapToAuditLog(audit))
	}

	return response, nil
}
