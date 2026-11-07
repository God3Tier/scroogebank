package repository

import (
	"context"
	"testing"
	"time"

	"receiver/src/internal/models"
)

func TestListTransactions(t *testing.T) {
	db := openTestDB(t)
	repo := New(db)
	ctx := context.Background()
	// Far in the future, so rows other tests leave behind sort after these.
	base := time.Date(2099, 1, 1, 12, 0, 0, 0, time.UTC)
	const account = "00000000-0000-0000-0000-000000000501"
	seeded := []models.Transaction{
		{TransactionID: "list-501", Status: "Completed", TransferDate: base, LinkedAccountID: account},
		{TransactionID: "list-502", Status: "Pending", TransferDate: base.Add(-time.Hour), LinkedAccountID: account},
		{TransactionID: "list-503", Status: "Completed", TransferDate: base.Add(-2 * time.Hour), LinkedAccountID: account},
		// Not linked to an account: never listed.
		{TransactionID: "list-504", Status: "Completed", TransferDate: base.Add(time.Hour)},
	}
	for _, transaction := range seeded {
		transaction.AccountID = "source-ref"
		transaction.TransactionType = "Deposit"
		transaction.Amount = 10
		transaction.Currency = "SGD"
		id := transaction.TransactionID
		clearTransaction(t, db, id)
		t.Cleanup(func() { clearTransaction(t, db, id) })
		if err := repo.SaveTransaction(ctx, transaction); err != nil {
			t.Fatalf("seed %s: %v", id, err)
		}
	}

	ids := func(t *testing.T, query models.TransactionListQuery) []string {
		t.Helper()
		got, err := repo.ListTransactions(ctx, query)
		if err != nil {
			t.Fatalf("ListTransactions() error = %v", err)
		}
		var ids []string
		for _, transaction := range got {
			ids = append(ids, transaction.TransactionID)
		}
		return ids
	}

	tests := map[string]struct {
		query models.TransactionListQuery
		want  []string
	}{
		"newest first, linked rows only": {
			query: models.TransactionListQuery{Limit: 3},
			want:  []string{"list-501", "list-502", "list-503"},
		},
		"resumes after the cursor": {
			query: models.TransactionListQuery{Limit: 2, After: &models.TransactionCursor{Date: base, ID: "list-501"}},
			want:  []string{"list-502", "list-503"},
		},
		"filters by status": {
			query: models.TransactionListQuery{Limit: 1, Status: "Pending"},
			want:  []string{"list-502"},
		},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			got := ids(t, test.query)
			if len(got) != len(test.want) {
				t.Fatalf("ids = %v, want %v", got, test.want)
			}
			for i := range got {
				if got[i] != test.want[i] {
					t.Fatalf("ids = %v, want %v", got, test.want)
				}
			}
		})
	}

	t.Run("returns the account link", func(t *testing.T) {
		got, err := repo.ListTransactions(ctx, models.TransactionListQuery{Limit: 1})
		if err != nil {
			t.Fatalf("ListTransactions() error = %v", err)
		}
		if len(got) != 1 || got[0].AccountID.String != account {
			t.Fatalf("account_id = %v, want %s", got, account)
		}
	})
}
