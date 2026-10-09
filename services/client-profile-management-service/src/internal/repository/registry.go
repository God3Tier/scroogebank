package repository

import (
	"client-profile-management-service/src/internal/repository/client"
	"database/sql"
)

type Registry interface {
	GetClientRepository() client.Repository
}

type impl struct {
	db *sql.DB
}

func New(db *sql.DB) Registry {
	return impl{
		db,
	}
}

func (i impl) GetClientRepository() client.Repository {
	return client.NewClientRepository(i.db)
}
