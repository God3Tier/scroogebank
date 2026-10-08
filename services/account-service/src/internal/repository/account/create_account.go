package account

import (
	"account-service/src/internal/models"
	orm "account-service/src/internal/repository/orm"
	"context"
	"strings"
	"time"
	"uuid"

	"github.com/aarondl/sqlboiler/v4/boil"
	"github.com/aarondl/sqlboiler/v4/types"
	"github.com/ericlagergren/decimal"
)

func (r accountRepository) CreateAccount(ctx context.Context, account models.CreateAccountInput) error {
	accountID := account.AccountID
	if accountID == uuid.Nil() {
		accountID = uuid.New()
	}
	initialDeposit := types.NewDecimal(new(decimal.Big))
	initialDeposit.SetFloat64(account.InitialDeposit)
	newAccount := &orm.Account{
		ID:             accountID.String(),
		ClientID:       account.ClientID.String(),
		OwnerAgentID:   account.OwnerAgentID,
		AccountType:    strings.ToLower(string(account.Type)),
		Status:         strings.ToLower(string(account.Status)),
		OpeningDate:    time.Now(),
		InitialDeposit: initialDeposit,
		Currency:       account.Currency,
		BranchID:       account.BranchID,
	}

	err := newAccount.Insert(ctx, r.db, boil.Infer())
	return err
}
