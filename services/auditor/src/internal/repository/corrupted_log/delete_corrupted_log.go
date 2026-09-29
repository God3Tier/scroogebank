package corrupted_log

import (
	orm "auditor/src/internal/repository/orm"
	"context"
	"database/sql"

	"github.com/aarondl/sqlboiler/v4/queries/qm"
)

func (i impl) DeleteCorruptedLog(ctx context.Context, uuid string) error {

	rows, err := orm.CorruptedAuditLogs(
		qm.Where("id = ?", uuid),
	).DeleteAll(ctx, i.db)

	if err != nil {
		return err
	}

	if rows == 0 {
		return sql.ErrNoRows
	}

	return nil
}
