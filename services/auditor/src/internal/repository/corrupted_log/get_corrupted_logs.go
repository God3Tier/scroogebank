package corrupted_log

import (
	"auditor/src/internal/models"
	orm "auditor/src/internal/repository/orm"
	"context"
	"encoding/json"
	"log"
)

func (i impl) GetCorruptedLogs(ctx context.Context) ([]models.CorruptedAuditLog, error) {
	raw, err := orm.CorruptedAuditLogs().All(ctx, i.db)

	if err != nil {
		return nil, err
	}

	corruptedLogs := make([]models.CorruptedAuditLog, 0, len(raw))

	for _, audit := range raw {
		var payload models.AuditLog

		err := json.Unmarshal(audit.LogType.JSON, &payload)
		if err != nil {
			log.Printf("Error unable to unmarshal: %s", err.Error())
			continue
		}

		corruptedLogs = append(corruptedLogs, models.CorruptedAuditLog{
			ID:           audit.ID,
			Payload:      payload,
			DateOfDetect: audit.DateOfDetect.Time,
		})
	}

	return corruptedLogs, nil
}
