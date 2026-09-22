package repository

import (
	"auditor/src/internal/repository/audit"
	"database/sql"
)

type Registry interface {
	GetAudit() audit.Repository
}

type impl struct {
	db *sql.DB
}

func New(db *sql.DB) Registry {
	return impl{
		db, 
	}
}

func (i impl) GetAudit() audit.Repository {
	return audit.New(i.db)
}