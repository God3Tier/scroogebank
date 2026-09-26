package account

import (
	"client-profile-management-service/src/internal/models"
	"context"
)

func (s *accountService) CreateAccount(ctx context.Context, account models.CreateAccountInput) error {
	return s.repo.CreateAccount(ctx, account)
}
