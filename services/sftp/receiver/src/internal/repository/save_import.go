package repository

import (
	"context"
	"database/sql"
	"fmt"

	"receiver/src/internal/models"

	"github.com/aarondl/sqlboiler/v4/boil"
)

// Import saves what a poll imports, with its audit events (ADR-0020 decision 3).
type Import interface {
	// SaveImport saves one file's transactions and, when event is not nil,
	// its audit event in a single database transaction: either both are
	// stored or neither is.
	SaveImport(ctx context.Context, transactions []models.Transaction, event *models.AuditEvent) error
	// SaveAuditEvent saves an audit event that has no import to join, such as
	// a connection failure.
	SaveAuditEvent(ctx context.Context, event models.AuditEvent) error
}

func NewImport(db *sql.DB) Import {
	return impl{db}
}

func (i impl) SaveImport(ctx context.Context, transactions []models.Transaction, event *models.AuditEvent) error {
	tx, err := i.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin import: %w", err)
	}
	defer tx.Rollback()

	for _, transaction := range transactions {
		if err := saveTransaction(ctx, tx, transaction); err != nil {
			return err
		}
	}
	if event != nil {
		if err := saveAuditEvent(ctx, tx, *event); err != nil {
			return err
		}
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit import: %w", err)
	}
	return nil
}

func (i impl) SaveAuditEvent(ctx context.Context, event models.AuditEvent) error {
	return saveAuditEvent(ctx, i.db, event)
}

// saveAuditEvent adds the event to audit_outbox. Event IDs are derived from
// the event's content, so an event written twice, such as a failure event
// retried within its episode, is kept once, as the Auditor would keep it.
func saveAuditEvent(ctx context.Context, exec boil.ContextExecutor, event models.AuditEvent) error {
	if _, err := exec.ExecContext(ctx,
		`INSERT INTO audit_outbox (event_id, body) VALUES ($1, $2) ON CONFLICT (event_id) DO NOTHING`,
		event.ID, string(event.Body)); err != nil {
		return fmt.Errorf("save audit event %s: %w", event.ID, err)
	}
	return nil
}
