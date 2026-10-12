package audit

import (
	"auditor/src/internal/chain"
	"auditor/src/internal/models"
	orm "auditor/src/internal/repository/orm"
	"context"
	"database/sql"
	"errors"
	"strings"

	"github.com/aarondl/null/v8"
	"github.com/aarondl/sqlboiler/v4/boil"
	"github.com/aarondl/sqlboiler/v4/types"
	"github.com/google/uuid"
	"github.com/lib/pq"
	pkgerrors "github.com/pkg/errors"
)

// chainLockKey names the advisory lock that writers to the chain take turns
// with. Any constant works; nothing else in audit-db takes advisory locks.
const chainLockKey = 22

func (i impl) StoreAuditChain(ctx context.Context, auditLog models.AuditLogInput) error {
	tx, err := i.db.BeginTx(ctx, nil)
	if err != nil {
		return pkgerrors.WithStack(err)
	}
	defer tx.Rollback()

	// Writers take turns, so each one appends after the newest entry and the
	// sequence numbers have no gaps. The lock is released when the transaction
	// ends. Unlike SELECT ... FOR UPDATE, an advisory lock needs no UPDATE
	// privilege on audit_log (ADR-0022 decision 2).
	if _, err := tx.ExecContext(ctx, `SELECT pg_advisory_xact_lock($1)`, chainLockKey); err != nil {
		return pkgerrors.WithStack(err)
	}

	// Entries are never deleted, so the newest has the highest sequence
	// number. An empty table starts the chain at 1 with no parent.
	var previousSequence int64
	var parentHash string
	err = tx.QueryRowContext(ctx,
		`SELECT sequence_no, hash FROM audit_log ORDER BY sequence_no DESC LIMIT 1`,
	).Scan(&previousSequence, &parentHash)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return pkgerrors.WithStack(err)
	}

	entry := models.AuditLog{
		Id:            uuid.New().String(),
		SequenceNo:    previousSequence + 1,
		EventID:       auditLog.EventID,
		EventType:     auditLog.EventType,
		Action:        auditLog.Action,
		SourceService: auditLog.SourceService,
		ActorId:       auditLog.ActorId,
		Timestamp:     chain.Now(),
		// The ORM stores a JSON value without the whitespace around it, so it
		// is hashed without it too.
		Payload:    strings.Trim(auditLog.Payload, " \t\r\n"),
		ParentHash: parentHash,
	}
	entry.Hash = chain.Hash(entry)

	newLog := &orm.AuditLog{
		ID:            entry.Id,
		SequenceNo:    entry.SequenceNo,
		EventID:       null.NewString(entry.EventID, entry.EventID != ""),
		EventType:     entry.EventType,
		Action:        entry.Action,
		SourceService: string(entry.SourceService),
		ActorID:       entry.ActorId,
		Timestamp:     entry.Timestamp,
		Payload:       types.JSON(entry.Payload),
		ParentHash:    entry.ParentHash,
		Hash:          entry.Hash,
	}

	err = newLog.Insert(ctx, tx, boil.Infer())
	if err != nil {
		if auditLog.EventID != "" && isDuplicateEventID(err) {
			return DuplicateEventError
		}
		return pkgerrors.WithStack(err)
	}
	if err := tx.Commit(); err != nil {
		return pkgerrors.WithStack(err)
	}

	return nil
}

// isDuplicateEventID reports whether err is a Postgres unique-violation on
// the audit_log_event_id_unique constraint — i.e. an SQS redelivery of an
// event this service already stored, rather than a genuine failure.
func isDuplicateEventID(err error) bool {
	var pqErr *pq.Error
	if errors.As(err, &pqErr) {
		return pqErr.Code == "23505" && pqErr.Constraint == "audit_log_event_id_unique"
	}
	return false
}
