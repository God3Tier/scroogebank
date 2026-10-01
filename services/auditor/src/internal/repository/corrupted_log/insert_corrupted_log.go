package corrupted_log

import (
	"auditor/src/internal/models"
	orm "auditor/src/internal/repository/orm"
	"context"
	"encoding/json"
	"time"

	"github.com/aarondl/null/v8"
	"github.com/aarondl/sqlboiler/v4/boil"
)

func (i impl) InsertCorruptedLog(ctx context.Context, raw models.CorruptedAuditLog) error {
	payload, err := json.Marshal(raw.Payload)

	if err != nil {
		return err
	}

	insert := &orm.CorruptedAuditLog{
		ID:           raw.ID,
		LogType:      null.JSONFrom(payload),
		DateOfDetect: null.TimeFrom(time.Now()),
	}

	return insert.Insert(ctx, i.db, boil.Infer())
}
