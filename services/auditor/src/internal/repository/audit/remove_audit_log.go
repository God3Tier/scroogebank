package audit

import (
	orm "auditor/src/internal/repository/orm"
	"context"
	"database/sql"
	"errors"

	"github.com/aarondl/null/v8"
	"github.com/aarondl/sqlboiler/v4/boil"
	"github.com/aarondl/sqlboiler/v4/queries/qm"
	pkgerrors "github.com/pkg/errors"
)

func (i impl) RemoveAuditLog(ctx context.Context, hash string) error {

	tx, err := i.db.BeginTx(ctx, nil)
	if err != nil {
		return pkgerrors.WithStack(err)
	}

	defer tx.Rollback()

	current, err := orm.AuditLogs(
		qm.Where("hash = ?", hash),
	).One(ctx, tx)

	if err != nil {
		return err
	}

	after, err := orm.AuditLogs(
		qm.Where("parent_hash = ?", hash),
	).One(ctx, tx)

	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			// In this case, we know it is the tail node
			rowsAffected, err := current.Delete(ctx, tx)
			if err != nil {
				return err
			}

			if rowsAffected == 0 {
				return sql.ErrNoRows
			}
			if err := tx.Commit(); err != nil {
				return pkgerrors.WithStack(err)
			}

			return nil
		}
		return err
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
				return err
			}

			if rowsAffected == 0 {
				return sql.ErrNoRows
			}
			rowsAffected, err = after.Update(ctx, tx, boil.Infer())
			if err != nil {
				return err
			}

			if rowsAffected == 0 {
				return sql.ErrNoRows
			}
			if err := tx.Commit(); err != nil {
				return pkgerrors.WithStack(err)
			}

			return nil
		}
		return err
	}
	after.ParentHash = before.Hash
	rowsAffected, err := after.Update(ctx, tx, boil.Infer())
	if err != nil {
		return err
	}

	if rowsAffected == 0 {
		return sql.ErrNoRows
	}
	rowsAffected, err = current.Delete(ctx, tx)
	if err != nil {
		return err
	}
	if rowsAffected == 0 {
		return sql.ErrNoRows
	}
	if err := tx.Commit(); err != nil {
		return pkgerrors.WithStack(err)
	}

	return nil
}
