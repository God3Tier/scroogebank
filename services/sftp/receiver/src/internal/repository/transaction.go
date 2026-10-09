package repository

import (
	"context"
	"database/sql"
	"receiver/src/internal/models"
	"receiver/src/internal/repository/orm"
)

type Transaction interface {
	SaveTransaction(ctx context.Context, transaction models.Transaction) error
	GetTransactionsByAccountID(ctx context.Context, accountID string) (orm.TransactionSlice, error)
}

type impl struct {
	db *sql.DB
}

func New(db *sql.DB) Transaction {
	return impl{
		db,
	}
}
