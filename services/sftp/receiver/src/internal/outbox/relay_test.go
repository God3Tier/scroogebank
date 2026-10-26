package outbox

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"testing"
	"time"

	"receiver/src/internal/models"

	"github.com/aws/aws-sdk-go-v2/aws"
	awssqs "github.com/aws/aws-sdk-go-v2/service/sqs"
	_ "github.com/lib/pq"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const testQueueURL = "audit-queue-url"

// fakeSQS accepts messages until call number failFrom (1-based); from then on
// every send fails. failFrom 0 never fails.
type fakeSQS struct {
	bodies   []string
	queues   []string
	calls    int
	failFrom int
}

func (f *fakeSQS) SendMessage(_ context.Context, input *awssqs.SendMessageInput, _ ...func(*awssqs.Options)) (*awssqs.SendMessageOutput, error) {
	f.calls++
	if f.failFrom > 0 && f.calls >= f.failFrom {
		return nil, errors.New("SQS unavailable")
	}
	f.bodies = append(f.bodies, aws.ToString(input.MessageBody))
	f.queues = append(f.queues, aws.ToString(input.QueueUrl))
	return &awssqs.SendMessageOutput{}, nil
}

// The tests that empty and drain a table use ReadTable: the repository tests,
// which run at the same time in another package, write to IngestTable. The
// relay works the same way on either table.
func TestRelay_SendsEveryWaitingEventOldestFirst(t *testing.T) {
	db := openTestDB(t)
	clearOutbox(t, db, ReadTable)
	// More than one batch, inserted newest first.
	total := relayBatchSize + 5
	start := time.Date(2026, 10, 9, 6, 0, 0, 0, time.UTC)
	for n := total; n >= 1; n-- {
		insertWaiting(t, db, ReadTable, fmt.Sprintf("event-%03d", n), fmt.Sprintf(`{"n":%d}`, n), start.Add(time.Duration(n)*time.Second))
	}
	client := &fakeSQS{}

	require.NoError(t, NewRelay(db, client, testQueueURL, ReadTable).RelayOnce(context.Background()))

	require.Len(t, client.bodies, total)
	assert.Equal(t, `{"n":1}`, client.bodies[0])
	assert.Equal(t, fmt.Sprintf(`{"n":%d}`, total), client.bodies[total-1])
	assert.Equal(t, testQueueURL, client.queues[0])
	assert.Empty(t, waitingBodies(t, db, ReadTable))
}

func TestRelay_KeepsUnsentEventsWhenSQSFails(t *testing.T) {
	db := openTestDB(t)
	clearOutbox(t, db, ReadTable)
	start := time.Date(2026, 10, 9, 6, 0, 0, 0, time.UTC)
	for n := 1; n <= 3; n++ {
		insertWaiting(t, db, ReadTable, fmt.Sprintf("event-%03d", n), fmt.Sprintf(`{"n":%d}`, n), start.Add(time.Duration(n)*time.Second))
	}
	client := &fakeSQS{failFrom: 2}

	require.NoError(t, NewRelay(db, client, testQueueURL, ReadTable).RelayOnce(context.Background()))

	assert.Equal(t, []string{`{"n":1}`}, client.bodies)
	assert.Equal(t, 2, client.calls, "the relay stops at the first failure")
	assert.Equal(t, []string{`{"n":2}`, `{"n":3}`}, waitingBodies(t, db, ReadTable), "unsent events wait for the next run")
}

func TestRelay_SendDeliversOneEventDirectly(t *testing.T) {
	client := &fakeSQS{}

	require.NoError(t, NewRelay(nil, client, testQueueURL, IngestTable).Send(context.Background(), models.AuditEvent{ID: "event-1", Body: []byte(`{"n":1}`)}))

	assert.Equal(t, []string{`{"n":1}`}, client.bodies)
	assert.Equal(t, []string{testQueueURL}, client.queues)
}

// Each relay sends only its own table's events (ADR-0064 decision 5). Only
// this test's own row in IngestTable is touched, since the repository tests
// use that table at the same time.
func TestRelay_SendsOnlyItsOwnTable(t *testing.T) {
	db := openTestDB(t)
	clearOutbox(t, db, ReadTable)
	start := time.Date(2026, 10, 9, 6, 0, 0, 0, time.UTC)
	const ingestID = "relay-test-ingest-1"
	insertWaiting(t, db, IngestTable, ingestID, `{"from":"ingest"}`, start)
	t.Cleanup(func() { _, _ = db.Exec(`DELETE FROM `+IngestTable+` WHERE event_id = $1`, ingestID) })
	require.NoError(t, Save(context.Background(), db, models.AuditEvent{ID: "read-1", Body: []byte(`{"from":"read"}`)}))
	client := &fakeSQS{}

	require.NoError(t, NewRelay(db, client, testQueueURL, ReadTable).RelayOnce(context.Background()))

	assert.Equal(t, []string{`{"from":"read"}`}, client.bodies)
	assert.Empty(t, waitingBodies(t, db, ReadTable))
	assert.Contains(t, waitingBodies(t, db, IngestTable), `{"from":"ingest"}`)
}

func TestNewRelay_RefusesAnUnknownTable(t *testing.T) {
	assert.Panics(t, func() { NewRelay(nil, &fakeSQS{}, testQueueURL, "transactions") })
}

func openTestDB(t *testing.T) *sql.DB {
	t.Helper()
	dsn := fmt.Sprintf("host=%s port=%s user=%s password=%s dbname=%s sslmode=disable",
		envOrDefault("DB_HOST", "localhost"), envOrDefault("DB_PORT", "5432"), envOrDefault("DB_USER", "crm"),
		envOrDefault("DB_PASSWORD", "crm"), envOrDefault("DB_NAME", "transactions"))
	db, err := sql.Open("postgres", dsn)
	require.NoError(t, err)
	require.NoError(t, db.Ping(), "connect to the test database")
	t.Cleanup(func() { _ = db.Close() })
	return db
}

func clearOutbox(t *testing.T, db *sql.DB, table string) {
	t.Helper()
	_, err := db.Exec(`DELETE FROM ` + table)
	require.NoError(t, err)
	t.Cleanup(func() { _, _ = db.Exec(`DELETE FROM ` + table) })
}

func insertWaiting(t *testing.T, db *sql.DB, table, eventID, body string, createdAt time.Time) {
	t.Helper()
	_, err := db.Exec(`INSERT INTO `+table+` (event_id, body, created_at) VALUES ($1, $2, $3)`, eventID, body, createdAt)
	require.NoError(t, err)
}

func waitingBodies(t *testing.T, db *sql.DB, table string) []string {
	t.Helper()
	rows, err := db.Query(`SELECT body FROM ` + table + ` ORDER BY created_at`)
	require.NoError(t, err)
	defer rows.Close()
	var bodies []string
	for rows.Next() {
		var body string
		require.NoError(t, rows.Scan(&body))
		bodies = append(bodies, body)
	}
	require.NoError(t, rows.Err())
	return bodies
}

func envOrDefault(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}
