package repository

import (
	"context"
	"database/sql"
	"testing"
	"time"

	"receiver/src/internal/models"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func importTransaction(id, status string) models.Transaction {
	return models.Transaction{
		TransactionID: id, AccountID: "account-" + id, TransactionType: "Deposit", Amount: 10,
		Currency: "SGD", TransferDate: time.Date(2026, 10, 9, 6, 0, 0, 0, time.UTC), Status: status,
	}
}

func TestSaveImportCommitsTransactionsAndEventTogether(t *testing.T) {
	db := openTestDB(t)
	transaction := importTransaction("import-301", "Completed")
	event := models.AuditEvent{ID: "import-event-301", Body: []byte(`{"n":301}`)}
	clearImport(t, db, event.ID, transaction.TransactionID)

	require.NoError(t, NewImport(db).SaveImport(context.Background(), []models.Transaction{transaction}, &event))

	assert.Equal(t, 1, countRows(t, db, "SELECT count(*) FROM transactions WHERE transaction_id = $1", transaction.TransactionID))
	assert.Equal(t, `{"n":301}`, outboxBody(t, db, event.ID))
}

func TestSaveImportStoresNeitherWhenATransactionFails(t *testing.T) {
	db := openTestDB(t)
	good := importTransaction("import-302", "Completed")
	bad := importTransaction("import-303", "NotAValidStatus")
	event := models.AuditEvent{ID: "import-event-302", Body: []byte(`{"n":302}`)}
	clearImport(t, db, event.ID, good.TransactionID, bad.TransactionID)

	err := NewImport(db).SaveImport(context.Background(), []models.Transaction{good, bad}, &event)

	require.Error(t, err)
	assertTransactionNotStored(t, db, good.TransactionID)
	assert.Zero(t, countRows(t, db, "SELECT count(*) FROM audit_outbox WHERE event_id = $1", event.ID),
		"the file's event must not outlive its rolled-back import")
}

func TestSaveAuditEventKeepsARepeatedEventOnce(t *testing.T) {
	db := openTestDB(t)
	event := models.AuditEvent{ID: "import-event-304", Body: []byte(`{"n":304}`)}
	clearImport(t, db, event.ID)
	repo := NewImport(db)

	require.NoError(t, repo.SaveAuditEvent(context.Background(), event))
	require.NoError(t, repo.SaveAuditEvent(context.Background(), event))

	assert.Equal(t, 1, countRows(t, db, "SELECT count(*) FROM audit_outbox WHERE event_id = $1", event.ID))
}

func clearImport(t *testing.T, db *sql.DB, eventID string, transactionIDs ...string) {
	t.Helper()
	remove := func() {
		_, _ = db.Exec("DELETE FROM audit_outbox WHERE event_id = $1", eventID)
		for _, id := range transactionIDs {
			_, _ = db.Exec("DELETE FROM transactions WHERE transaction_id = $1", id)
		}
	}
	remove()
	t.Cleanup(remove)
}

func countRows(t *testing.T, db *sql.DB, query, arg string) int {
	t.Helper()
	var count int
	require.NoError(t, db.QueryRow(query, arg).Scan(&count))
	return count
}

func outboxBody(t *testing.T, db *sql.DB, eventID string) string {
	t.Helper()
	var body string
	require.NoError(t, db.QueryRow("SELECT body FROM audit_outbox WHERE event_id = $1", eventID).Scan(&body))
	return body
}
