package repository

import (
	"context"

	"client-profile-management-service/src/internal/models"
	orm "client-profile-management-service/src/internal/repository/orm"

	"github.com/aarondl/sqlboiler/v4/boil"
	"github.com/aarondl/sqlboiler/v4/types"
)

type accountRepository struct {
}

func NewAccountRepository() AccountRepository {
	return &accountRepository{}
}

func (r *accountRepository) CreateAccount(ctx context.Context, exec boil.ContextExecutor, account *models.Account) error {
	var initialDeposit types.Decimal
	initialDeposit.SetFloat64(account.InitialDeposit)
	newAccount := &orm.Account{
		ID:        account.ID.String(),
		ClientID:  account.ClientID.String(),
		AccountType:      account.Type,
		Status:    account.Status,
		OpeningDate: account.OpeningDate,
		InitialDeposit: initialDeposit,
		Currency:       account.Currency,
		BranchID:       account.BranchID,
		CreatedAt:      account.CreatedAt,
		UpdatedAt:      account.UpdatedAt,
	}

	err := newAccount.Insert(ctx, exec, boil.Infer())
	return err
}

func (r *accountRepository) DeleteAccount(ctx context.Context, exec boil.ContextExecutor, accountID string) error {
	account, err := orm.FindAccount(ctx, exec, accountID)
	if err != nil {
		return err
	}

	_, err = account.Delete(ctx, exec)
	return err
}
