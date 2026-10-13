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

// A re-sent transaction moves only a Pending status forward; everything else
// keeps its first imported value (ADR-0024 decision 4).
func TestSaveTransactionOnARepeatedID(t *testing.T) {
	db := openTestDB(t)
	repo := New(db)
	ctx := context.Background()

	tests := map[string]struct {
		firstStatus, laterStatus, wantStatus string
	}{
		"a pending transaction completes":         {"Pending", "Completed", "Completed"},
		"a pending transaction fails":             {"Pending", "Failed", "Failed"},
		"a pending transaction stays pending":     {"Pending", "Pending", "Pending"},
		"a completed transaction is not reopened": {"Completed", "Pending", "Completed"},
		"a completed transaction does not fail":   {"Completed", "Failed", "Completed"},
		"a failed transaction does not complete":  {"Failed", "Completed", "Failed"},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			first := models.Transaction{
				TransactionID:   "00000000-0000-0000-0000-000000000103",
				AccountID:       "account-103",
				TransactionType: "Deposit",
				Amount:          25.50,
				Currency:        "SGD",
				TransferDate:    time.Date(2026, 9, 24, 8, 30, 0, 0, time.UTC),
				Status:          test.firstStatus,
				Description:     "original transaction",
			}
			clearTransaction(t, db, first.TransactionID)
			t.Cleanup(func() { clearTransaction(t, db, first.TransactionID) })
			if err := repo.SaveTransaction(ctx, first); err != nil {
				t.Fatalf("save first version: %v", err)
			}

			// A later file sends the same ID with a new status and different
			// values, now for an account that exists.
			later := first
			later.Status = test.laterStatus
			later.Amount = 99.99
			later.Description = "re-sent with other values"
			later.AccountID = "a0000000-0000-0000-0000-000000000231"
			later.LinkedAccountID = "a0000000-0000-0000-0000-000000000231"
			later.ClientID = "00000000-0000-0000-0000-000000000203"
			later.ImportStatus = "Matched"
			if err := repo.SaveTransaction(ctx, later); err != nil {
				t.Fatalf("save later version: %v", err)
			}

			var status, amount, description, sourceRef, importStatus string
			var accountID sql.NullString
			err := db.QueryRowContext(ctx, `
				SELECT status, amount::text, description, source_account_ref, account_id::text, import_status
				FROM transactions WHERE transaction_id = $1`, first.TransactionID,
			).Scan(&status, &amount, &description, &sourceRef, &accountID, &importStatus)
			if err != nil {
				t.Fatalf("read transaction: %v", err)
			}
			if status != test.wantStatus {
				t.Errorf("status = %q, want %q", status, test.wantStatus)
			}
			if amount != "25.50" || description != "original transaction" || sourceRef != "account-103" {
				t.Errorf("re-sent values changed the stored row: amount=%s description=%q source=%q", amount, description, sourceRef)
			}
			if accountID.Valid || importStatus != "Unmatched" {
				t.Errorf("re-sent row was linked later: account=%v import status=%q, want no link and Unmatched (ADR-0037 decision 6)", accountID, importStatus)
			}
		})
	}
}

func TestSaveTransactionStoresTheAccountLink(t *testing.T) {
	db := openTestDB(t)
	transaction := models.Transaction{
		TransactionID:   "00000000-0000-0000-0000-000000000104",
		AccountID:       "A0000000-0000-0000-0000-000000000231",
		TransactionType: "Deposit",
		Amount:          10,
		Currency:        "SGD",
		TransferDate:    time.Date(2026, 9, 24, 8, 30, 0, 0, time.UTC),
		Status:          "Completed",
		LinkedAccountID: "a0000000-0000-0000-0000-000000000231",
		ClientID:        "00000000-0000-0000-0000-000000000203",
		ImportStatus:    "Matched",
	}
	clearTransaction(t, db, transaction.TransactionID)
	t.Cleanup(func() { clearTransaction(t, db, transaction.TransactionID) })

	if err := New(db).SaveTransaction(context.Background(), transaction); err != nil {
		t.Fatalf("SaveTransaction() error = %v", err)
	}

	var accountID, clientID, importStatus string
	err := db.QueryRowContext(context.Background(),
		"SELECT account_id::text, client_id::text, import_status FROM transactions WHERE transaction_id = $1",
		transaction.TransactionID,
	).Scan(&accountID, &clientID, &importStatus)
	if err != nil {
		t.Fatalf("read saved transaction: %v", err)
	}
	if accountID != transaction.LinkedAccountID || clientID != transaction.ClientID || importStatus != "Matched" {
		t.Errorf("link = (%s, %s, %s), want (%s, %s, Matched)", accountID, clientID, importStatus, transaction.LinkedAccountID, transaction.ClientID)
	}
}

func openTestDB(t *testing.T) *sql.DB {
	t.Helper()
	host := envOrDefault("DB_HOST", "localhost")
	port := envOrDefault("DB_PORT", "5432")
	user := envOrDefault("DB_USER", "crm")
	password := envOrDefault("DB_PASSWORD", "crm")
	database := envOrDefault("DB_NAME", "transactions")
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
