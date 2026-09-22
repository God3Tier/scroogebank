package service

import (
	"client-profile-management-service/src/internal/models"
	"context"
)

type AccountService interface {
	CreateAccount(ctx context.Context, account *models.Account) error
	DeleteAccount(ctx context.Context, accountID string) error
}
