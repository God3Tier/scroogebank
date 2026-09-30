package account

import (
	"client-profile-management-service/src/internal/models"
	orm "client-profile-management-service/src/internal/repository/orm"
	"context"
)

func (s *accountService) CreateAccount(ctx context.Context, account models.CreateAccountInput) error {
	return s.repo.CreateAccount(ctx, account)
}

func (s *accountService) GetAccount(ctx context.Context, accountID string) (*orm.Account, error) {
	return s.repo.GetAccount(ctx, accountID)
}
