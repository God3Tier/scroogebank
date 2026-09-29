package audit

import (
	"context"
	"database/sql"
	"errors"
	"strconv"
	"time"
	"uuid"

	"auditor/src/internal/models"
	"auditor/src/internal/pkg"
	orm "auditor/src/internal/repository/orm"

	"github.com/aarondl/null/v8"
	"github.com/aarondl/sqlboiler/v4/boil"
	"github.com/aarondl/sqlboiler/v4/queries/qm"
	pkgerrors "github.com/pkg/errors"
)

func (i impl) StoreAuditChain(ctx context.Context, auditLog models.AuditLogInput) error {
	tx, err := i.db.BeginTx(ctx, nil)
	if err != nil {
		return pkgerrors.WithStack(err)
	}
	defer tx.Rollback()

	prevChain, err := orm.AuditLogs(
		qm.OrderBy("sequence_no desc"),
		qm.Limit(1),
		qm.For("UPDATE"),
	).One(ctx, tx)

	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return pkgerrors.WithStack(err)
	}

	var previousSequence int64
	var parentHash = ""
	if prevChain != nil {
		previousSequence = prevChain.SequenceNo.Int64
		parentHash = prevChain.Hash.String
	}

	now := time.Now()

	auditID := auditLog.EventId
	if auditID == "" {
		auditID = uuid.New().String()
	}

	rawString := auditID + strconv.FormatInt(previousSequence+1, 10) + auditLog.EventType + auditLog.Action + string(auditLog.SourceService) + auditLog.ActorId + now.Format(time.RFC3339) + auditLog.Payload

	newLog := &orm.AuditLog{
		ID:            auditID,
		SequenceNo:    null.Int64From(previousSequence + 1),
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
		return pkgerrors.WithStack(err)
	}
	if err := tx.Commit(); err != nil {
		return pkgerrors.WithStack(err)
	}

	return nil
}
