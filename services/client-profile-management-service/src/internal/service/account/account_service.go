package account

import (
	"client-profile-management-service/src/internal/models"
	"client-profile-management-service/src/internal/repository/account"
	orm "client-profile-management-service/src/internal/repository/orm"
	"context"
)

type AccountService interface {
	CreateAccount(ctx context.Context, account models.CreateAccountInput) error
	GetAccount(ctx context.Context, accountID string) (*orm.Account, error)
	DeleteAccount(ctx context.Context, accountID string) error
}

type accountService struct {
	repo account.Repository
}

func NewAccountService(repo account.Repository) AccountService {
	return &accountService{repo: repo}
}
