package audit

import (
	"auditor/src/internal/models"
	"auditor/src/internal/pkg"
	orm "auditor/src/internal/repository/orm"
	"context"
	"database/sql"
	"errors"
	"strconv"
	"time"

	"github.com/aarondl/null/v8"
	"github.com/aarondl/sqlboiler/v4/boil"
	"github.com/google/uuid"
	"github.com/lib/pq"
	pkgerrors "github.com/pkg/errors"
)

func (i impl) StoreAuditChain(ctx context.Context, auditLog models.AuditLogInput) error {
	tx, err := i.db.BeginTx(ctx, nil)
	if err != nil {
		return pkgerrors.WithStack(err)
	}
	defer tx.Rollback()

	// The head is the log entry that no other entry references as its parent.
	// Use the sequence only to choose among multiple leaves; sequence numbers
	// can have gaps and therefore must not be derived from row counts.
	var previousSequence int64
	var parentHash string
	err = tx.QueryRowContext(ctx, `
		SELECT COALESCE(candidate.sequence_no, 0), COALESCE(candidate.hash, '')
		FROM audit_log AS candidate
		WHERE NOT EXISTS (
			SELECT 1
			FROM audit_log AS successor
			WHERE successor.parent_hash = candidate.hash
		)
		ORDER BY candidate.sequence_no DESC NULLS LAST
		LIMIT 1
		FOR UPDATE OF candidate`,
	).Scan(&previousSequence, &parentHash)
	if errors.Is(err, sql.ErrNoRows) {
		// An empty table has no head. Start a new chain at sequence 1 with no
		// parent; this is the only case where no previous sequence exists.
		previousSequence = 0
		parentHash = ""
	} else if err != nil {
		return pkgerrors.WithStack(err)
	}

	now := time.Now()

	auditID := uuid.New().String()

	rawString := auditID + strconv.FormatInt(previousSequence+1, 10) + auditLog.EventType + auditLog.Action + string(auditLog.SourceService) + auditLog.ActorId + now.Format(time.RFC3339) + auditLog.Payload

	newLog := &orm.AuditLog{
		ID:            auditID,
		SequenceNo:    null.Int64From(previousSequence + 1),
		EventID:       null.NewString(auditLog.EventID, auditLog.EventID != ""),
		EventType:     null.StringFrom(auditLog.EventType),
		Action:        null.StringFrom(auditLog.Action),
		SourceService: null.StringFrom(string(auditLog.SourceService)),
		ActorID:       null.StringFrom(auditLog.ActorId),
		Timestamp:     null.TimeFrom(now),
		Payload:       null.JSONFrom([]byte(auditLog.Payload)),
		ParentHash:    null.StringFrom(parentHash),
		Hash:          null.StringFrom(pkg.Hash(rawString)),
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
