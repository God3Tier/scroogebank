package account

import (
	"client-profile-management-service/src/internal/models"
	"client-profile-management-service/src/internal/repository/account"
	"context"
)

type AccountService interface {
	CreateAccount(ctx context.Context, account models.CreateAccountInput) error
	DeleteAccount(ctx context.Context, accountID string) error
}

type accountService struct {
	repo account.Repository
}

func NewAccountService(repo account.Repository) AccountService {
	return &accountService{repo: repo}
}
