package repository

import (
	"client-profile-management-service/src/internal/encryption"
	"client-profile-management-service/src/internal/repository/client"
	"database/sql"
)

type Registry interface {
	GetClientRepository() client.Repository
}

type impl struct {
	db        *sql.DB
	protector encryption.Protector
}

func New(db *sql.DB, protector encryption.Protector) Registry {
	return impl{
		db,
		protector,
	}
}

func (i impl) GetClientRepository() client.Repository {
	return client.NewClientRepository(i.db, i.protector)
}
