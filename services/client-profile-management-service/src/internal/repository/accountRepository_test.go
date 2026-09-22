package repository

import (
	"context"
	"errors"
	"testing"
	"time"
	"uuid"

	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"

	"github.com/DATA-DOG/go-sqlmock"
)

func TestAccountRepositoryCreateAccount_Success(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatalf("sqlmock.New() error = %v", err)
	}
	defer db.Close()

	account := &models.Account{
		ID:             uuid.UUID(uuid.New()),
		ClientID:       uuid.UUID(uuid.New()),
		Type:           "savings",
		Status:         "active",
		OpeningDate:    time.Now().UTC(),
		InitialDeposit: 2500,
		Currency:       "USD",
		BranchID:       "B001",
		CreatedAt:      time.Now().UTC(),
		UpdatedAt:      time.Now().UTC(),
	}

	mock.ExpectQuery(`SELECT EXISTS\(SELECT 1 FROM clients WHERE client_id = \$1\)`).
		WithArgs(account.ClientID).
		WillReturnRows(sqlmock.NewRows([]string{"exists"}).AddRow(true))
	mock.ExpectQuery(`SELECT EXISTS\(SELECT 1 FROM accounts WHERE client_id = \$1 AND account_type = \$2\)`).
		WithArgs(account.ClientID, account.Type).
		WillReturnRows(sqlmock.NewRows([]string{"exists"}).AddRow(false))
	mock.ExpectExec(`INSERT INTO accounts \(id, client_id, account_type, status, opening_date, initial_deposit, currency, branch_id, created_at, updated_at\)`).
		WithArgs(account.ID, account.ClientID, account.Type, account.Status, account.OpeningDate, account.InitialDeposit, account.Currency, account.BranchID, sqlmock.AnyArg(), sqlmock.AnyArg()).
		WillReturnResult(sqlmock.NewResult(1, 1))

	repo := &accountRepository{}
	if err := repo.CreateAccount(context.Background(), db, account); err != nil {
		t.Fatalf("CreateAccount() unexpected error: %v", err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatalf("unmet expectations: %v", err)
	}
}

func TestAccountRepositoryDeleteAccount_NotFound(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatalf("sqlmock.New() error = %v", err)
	}
	defer db.Close()

	id := uuid.New().String()
	mock.ExpectExec(`DELETE FROM accounts WHERE id = \$1`).
		WithArgs(sqlmock.AnyArg()).
		WillReturnResult(sqlmock.NewResult(0, 0))

	repo := &accountRepository{}
	err = repo.DeleteAccount(context.Background(), db, id)
	if !errors.Is(err, clienterrors.ErrNotFound) {
		t.Fatalf("DeleteAccount() error = %v, want ErrNotFound", err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatalf("unmet expectations: %v", err)
	}
}
