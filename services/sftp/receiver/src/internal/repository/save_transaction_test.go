package repository

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"testing"
	"time"

	"receiver/src/internal/models"

	_ "github.com/lib/pq"
)

func TestSaveTransaction(t *testing.T) {
	db := openTestDB(t)
	repo := New(db)
	ctx := context.Background()
	transferDate := time.Date(2026, 9, 24, 8, 30, 0, 0, time.UTC)

	tests := map[string]struct {
		transaction models.Transaction
		wantErr     bool
	}{
		"persists transaction fields": {
			transaction: models.Transaction{
				TransactionID:   "00000000-0000-0000-0000-000000000101",
				AccountID:       "account-101",
				TransactionType: "Deposit",
				Amount:          125.75,
				Currency:        "SGD",
				TransferDate:    transferDate,
				Status:          "Completed",
				Description:     "cash deposit",
			},
		},
		"returns database constraint errors": {
			transaction: models.Transaction{
				TransactionID:   "00000000-0000-0000-0000-000000000102",
				AccountID:       "account-102",
				TransactionType: "Deposit",
				Amount:          40.00,
				Currency:        "SGD",
				TransferDate:    transferDate,
				Status:          "NotAValidStatus",
			},
			wantErr: true,
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			clearTransaction(t, db, test.transaction.TransactionID)
			t.Cleanup(func() { clearTransaction(t, db, test.transaction.TransactionID) })

			err := repo.SaveTransaction(ctx, test.transaction)
			if test.wantErr {
				if err == nil {
					t.Fatal("SaveTransaction() error = nil, want a database constraint error")
				}
				assertTransactionNotStored(t, db, test.transaction.TransactionID)
				return
			}
			if err != nil {
				t.Fatalf("SaveTransaction() error = %v", err)
			}

			var got struct {
				accountID       string
				transactionType string
				amount          string
				currency        sql.NullString
				transferDate    time.Time
				status          string
				description     sql.NullString
				importStatus    string
			}
			err = db.QueryRowContext(ctx, `
				SELECT source_account_ref, transaction_type, amount::text, currency,
				       transaction_date, status, description, import_status
				FROM transactions
				WHERE transaction_id = $1`, test.transaction.TransactionID,
			).Scan(
				&got.accountID,
				&got.transactionType,
				&got.amount,
				&got.currency,
				&got.transferDate,
				&got.status,
				&got.description,
				&got.importStatus,
			)
			if err != nil {
				t.Fatalf("read saved transaction: %v", err)
			}

			if got.accountID != test.transaction.AccountID {
				t.Errorf("source account = %q, want %q", got.accountID, test.transaction.AccountID)
			}
			if got.transactionType != test.transaction.TransactionType {
				t.Errorf("type = %q, want %q", got.transactionType, test.transaction.TransactionType)
			}
			if got.amount != "125.75" {
				t.Errorf("amount = %q, want %q", got.amount, "125.75")
			}
			if !got.currency.Valid || got.currency.String != test.transaction.Currency {
				t.Errorf("currency = %#v, want %q", got.currency, test.transaction.Currency)
			}
			if !got.transferDate.Equal(test.transaction.TransferDate) {
				t.Errorf("date = %s, want %s", got.transferDate, test.transaction.TransferDate)
			}
			if got.status != test.transaction.Status {
				t.Errorf("status = %q, want %q", got.status, test.transaction.Status)
			}
			if !got.description.Valid || got.description.String != test.transaction.Description {
				t.Errorf("description = %#v, want %q", got.description, test.transaction.Description)
			}
			if got.importStatus != "Unmatched" {
				t.Errorf("default import status = %q, want %q", got.importStatus, "Unmatched")
			}
		})
	}
}

func TestSaveTransactionDoesNotOverwriteDuplicateID(t *testing.T) {
	db := openTestDB(t)
	transaction := models.Transaction{
		TransactionID:   "00000000-0000-0000-0000-000000000103",
		AccountID:       "account-103",
		TransactionType: "Deposit",
		Amount:          25.50,
		Currency:        "SGD",
		TransferDate:    time.Date(2026, 9, 24, 8, 30, 0, 0, time.UTC),
		Status:          "Completed",
		Description:     "original transaction",
	}
	clearTransaction(t, db, transaction.TransactionID)
	t.Cleanup(func() { clearTransaction(t, db, transaction.TransactionID) })

	repo := New(db)
	if err := repo.SaveTransaction(context.Background(), transaction); err != nil {
		t.Fatalf("save initial transaction: %v", err)
	}

	transaction.Amount = 99.99
	transaction.Description = "duplicate file retry"
	if err := repo.SaveTransaction(context.Background(), transaction); err != nil {
		t.Fatalf("retry duplicate transaction: %v", err)
	}

	var amount, description string
	err := db.QueryRowContext(context.Background(),
		"SELECT amount::text, description FROM transactions WHERE transaction_id = $1",
		transaction.TransactionID,
	).Scan(&amount, &description)
	if err != nil {
		t.Fatalf("read transaction after retry: %v", err)
	}
	if amount != "25.50" || description != "original transaction" {
		t.Fatalf("duplicate retry changed stored row: amount=%s description=%q", amount, description)
	}
}

func openTestDB(t *testing.T) *sql.DB {
	t.Helper()
	host := envOrDefault("DB_HOST", "localhost")
	port := envOrDefault("DB_PORT", "5432")
	user := envOrDefault("DB_USER", "crm")
	password := envOrDefault("DB_PASSWORD", "crm")
	database := envOrDefault("DB_NAME", "crm")
	dsn := fmt.Sprintf("host=%s port=%s user=%s password=%s dbname=%s sslmode=disable", host, port, user, password, database)

	db, err := sql.Open("postgres", dsn)
	if err != nil {
		t.Fatalf("open test database: %v", err)
	}
	if err := db.Ping(); err != nil {
		db.Close()
		t.Fatalf("connect to test database at %s:%s: %v", host, port, err)
	}
	t.Cleanup(func() { db.Close() })
	return db
}

func envOrDefault(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

func clearTransaction(t *testing.T, db *sql.DB, transactionID string) {
	t.Helper()
	if _, err := db.ExecContext(context.Background(), "DELETE FROM transactions WHERE transaction_id = $1", transactionID); err != nil {
		t.Fatalf("clear test transaction %s: %v", transactionID, err)
	}
}

func assertTransactionNotStored(t *testing.T, db *sql.DB, transactionID string) {
	t.Helper()
	var count int
	if err := db.QueryRowContext(context.Background(), "SELECT count(*) FROM transactions WHERE transaction_id = $1", transactionID).Scan(&count); err != nil {
		t.Fatalf("count transaction rows: %v", err)
	}
	if count != 0 {
		t.Errorf("found %d transaction rows after failed save, want 0", count)
	}
}
