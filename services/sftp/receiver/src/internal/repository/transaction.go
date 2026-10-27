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
	ListTransactions(ctx context.Context, query models.TransactionListQuery) (orm.TransactionSlice, error)
	ListClientHealthTransactions(ctx context.Context, query models.ClientHealthTransactionQuery) ([]models.ClientHealthTransaction, error)
}

type impl struct {
	db *sql.DB
}

func New(db *sql.DB) Transaction {
	return impl{
		db,
	}
}
