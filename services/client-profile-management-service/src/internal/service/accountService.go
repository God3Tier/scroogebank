package service

import (
	"context"
	"database/sql"

	"client-profile-management-service/src/internal/models"
	"client-profile-management-service/src/internal/repository"
)

type accountService struct {
	db *sql.DB
	repo repository.AccountRepository
}

func NewAccountService(db *sql.DB, repo repository.AccountRepository) *accountService {
	return &accountService{db: db, repo: repo}
}

func (s *accountService) CreateAccount(ctx context.Context, account *models.Account) error {
	return s.repo.CreateAccount(ctx, s.db, account)
}

func (s *accountService) DeleteAccount(ctx context.Context, accountID string) error {
	return s.repo.DeleteAccount(ctx, s.db, accountID)
}
