package audit

import (
	"auditor/src/internal/models"
	orm "auditor/src/internal/repository/orm"
	"context"
	"database/sql"
	"errors"

	"github.com/aarondl/null/v8"
	"github.com/aarondl/sqlboiler/v4/boil"
	"github.com/aarondl/sqlboiler/v4/queries/qm"
	pkgerrors "github.com/pkg/errors"
)

func (i impl) RemoveAuditLog(ctx context.Context, hash string) (models.AuditLog, error) {

	tx, err := i.db.BeginTx(ctx, nil)
	if err != nil {
		return models.AuditLog{}, pkgerrors.WithStack(err)
	}

	defer tx.Rollback()

	current, err := orm.AuditLogs(
		qm.Where("hash = ?", hash),
	).One(ctx, tx)

	if err != nil {
		return models.AuditLog{}, err
	}
	removedLog := models.AuditLog{
		Id:            current.ID,
		SequenceNo:    current.SequenceNo.Int64,
		EventID:       current.EventID.String,
		EventType:     current.EventType.String,
		Action:        current.Action.String,
		SourceService: models.ServiceType(current.SourceService.String),
		ActorId:       current.ActorID.String,
		Timestamp:     current.Timestamp.Time,
		Payload:       string(current.Payload.JSON),
		ParentHash:    current.ParentHash.String,
		Hash:          current.Hash.String,
	}

	after, err := orm.AuditLogs(
		qm.Where("parent_hash = ?", hash),
	).One(ctx, tx)

	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			// In this case, we know it is the tail node
			rowsAffected, err := current.Delete(ctx, tx)
			if err != nil {
				return models.AuditLog{}, err
			}

			if rowsAffected == 0 {
				return models.AuditLog{}, sql.ErrNoRows
			}
			if err := tx.Commit(); err != nil {
				return models.AuditLog{}, pkgerrors.WithStack(err)
			}

			return removedLog, nil
		}
		return models.AuditLog{}, err
	}

	before, err := orm.AuditLogs(
		qm.Where("hash = ?", current.ParentHash),
	).One(ctx, tx)

	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			// This is the head condition; the successor becomes the new root.
			after.ParentHash = null.StringFrom("")
			rowsAffected, err := current.Delete(ctx, tx)
			if err != nil {
				return models.AuditLog{}, err
			}

			if rowsAffected == 0 {
				return models.AuditLog{}, sql.ErrNoRows
			}
			rowsAffected, err = after.Update(ctx, tx, boil.Infer())
			if err != nil {
				return models.AuditLog{}, err
			}

			if rowsAffected == 0 {
				return models.AuditLog{}, sql.ErrNoRows
			}
			if err := tx.Commit(); err != nil {
				return models.AuditLog{}, pkgerrors.WithStack(err)
			}

			return removedLog, nil
		}
		return models.AuditLog{}, err
	}
	after.ParentHash = before.Hash
	rowsAffected, err := after.Update(ctx, tx, boil.Infer())
	if err != nil {
		return models.AuditLog{}, err
	}

	if rowsAffected == 0 {
		return models.AuditLog{}, sql.ErrNoRows
	}
	rowsAffected, err = current.Delete(ctx, tx)
	if err != nil {
		return models.AuditLog{}, err
	}
	if rowsAffected == 0 {
		return models.AuditLog{}, sql.ErrNoRows
	}
	if err := tx.Commit(); err != nil {
		return models.AuditLog{}, pkgerrors.WithStack(err)
	}

	return removedLog, nil
}
