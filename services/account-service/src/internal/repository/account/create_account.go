package account

import (
	accounterrors "account-service/src/internal/errors"
	"account-service/src/internal/models"
	orm "account-service/src/internal/repository/orm"
	"context"
	"errors"
	"strings"
	"time"
	"uuid"

	"github.com/aarondl/sqlboiler/v4/boil"
	"github.com/aarondl/sqlboiler/v4/types"
	"github.com/ericlagergren/decimal"
	"github.com/lib/pq"
)

// singapore is the bank's business day (ADR-0038). Singapore has had no
// daylight saving since 1982, so a fixed offset needs no time zone database
// in the image.
var singapore = time.FixedZone("SGT", 8*60*60)

// clientTypeIndex keeps one account per type per client, not counting
// deleted accounts (data/V1_init.sql).
const clientTypeIndex = "uk_accounts_client_type"

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
		OpeningDate:    openingDate(time.Now()),
		InitialDeposit: initialDeposit,
		Currency:       account.Currency,
		BranchID:       account.BranchID,
	}

	err := newAccount.Insert(ctx, r.db, boil.Infer())
	if isUniqueViolation(err, clientTypeIndex) {
		// The index is the check, so two requests racing for the same type
		// get one account and one conflict.
		return accounterrors.ErrDuplicateAccount
	}
	return err
}

// openingDate is the Singapore date at now, as the DATE column stores it. The
// tasks run in UTC, so time.Now() alone would date an account opened before
// 08:00 in Singapore a day early, and the dashboard counts opening dates as
// Singapore days (ADR-0038).
func openingDate(now time.Time) time.Time {
	year, month, day := now.In(singapore).Date()
	return time.Date(year, month, day, 0, 0, 0, 0, time.UTC)
}

// isUniqueViolation reports whether err is Postgres refusing a row because of
// the unique constraint or index named constraint.
func isUniqueViolation(err error, constraint string) bool {
	var pqErr *pq.Error
	return errors.As(err, &pqErr) && pqErr.Code == "23505" && pqErr.Constraint == constraint
}
