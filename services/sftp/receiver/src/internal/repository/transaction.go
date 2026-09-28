package repository

import (
	"context"
	"database/sql"
	"receiver/src/internal/models"
)

type Transaction interface {
	SaveTransaction(ctx context.Context, transaction models.Transaction) error 
}

type impl struct {
	db *sql.DB
}

func New(db *sql.DB) Transaction {
	return impl{
		db,
	}
}
