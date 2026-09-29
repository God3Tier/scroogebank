package repository

import (
	"auditor/src/internal/repository/audit"
	"auditor/src/internal/repository/corrupted_log"
	"database/sql"
)

type Registry interface {
	GetAudit() audit.Repository
	GetCorruptedLog() corrupted_log.Repository
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

func (i impl) GetCorruptedLog() corrupted_log.Repository {
	return corrupted_log.New(i.db)
}
