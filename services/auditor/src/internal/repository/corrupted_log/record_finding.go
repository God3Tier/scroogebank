package corrupted_log

import (
	"auditor/src/internal/models"
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"
	pkgerrors "github.com/pkg/errors"
)

// RecordFinding records a problem chain-verify found, unless the same problem
// with the same row is already recorded, and reports whether it recorded a new
// one. The chain is never repaired, so chain-verify finds a problem again on
// every run (ADR-0022 decision 4).
func (i impl) RecordFinding(ctx context.Context, finding models.ChainFinding) (bool, error) {
	entry, err := json.Marshal(finding.Entry)
	if err != nil {
		return false, pkgerrors.WithStack(err)
	}

	// SQLBoiler's Insert API does not expose this composite ON CONFLICT target.
	// Keep this statement local to the repository to preserve once-only finding
	// recording and report whether a new row was inserted.
	result, err := i.db.ExecContext(ctx, `
		INSERT INTO corrupted_audit_log (id, audit_log_id, reason, log_type, date_of_detect)
		VALUES ($1, $2, $3, $4, $5)
		ON CONFLICT (audit_log_id, reason) DO NOTHING`,
		uuid.New().String(), finding.Entry.ID, string(finding.Reason), string(entry), time.Now().UTC(),
	)
	if err != nil {
		return false, pkgerrors.WithStack(err)
	}

	inserted, err := result.RowsAffected()
	if err != nil {
		return false, pkgerrors.WithStack(err)
	}
	return inserted == 1, nil
}
