package sqs

import (
	"bytes"
	"context"
	"database/sql"
	"errors"
	"log"
	"os"
	"testing"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
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

func (f *fakeSQS) SendMessage(_ context.Context, input *sqs.SendMessageInput, _ ...func(*sqs.Options)) (*sqs.SendMessageOutput, error) {
	f.calls++
	if f.failFrom > 0 && f.calls >= f.failFrom {
		return nil, errors.New("SQS unavailable")
	}
	f.bodies = append(f.bodies, aws.ToString(input.MessageBody))
	f.queues = append(f.queues, aws.ToString(input.QueueUrl))
	return &sqs.SendMessageOutput{}, nil
}

func TestPostgresOutbox_SaveStoresTheEvent(t *testing.T) {
	db := openTestDB(t)
	clearOutbox(t, db)

	require.NoError(t, NewPostgresOutbox(db).Save(context.Background(), "00000000-0000-4000-8000-000000000001", []byte(`{"n":1}`)))

	assert.Equal(t, []string{`{"n":1}`}, waitingBodies(t, db))
}

func TestRelay_SendsOldestFirstAndDeletesWhatWasSent(t *testing.T) {
	db := openTestDB(t)
	clearOutbox(t, db)
	insertWaiting(t, db, "00000000-0000-4000-8000-000000000002", `{"n":2}`, "2026-10-09T06:00:02Z")
	insertWaiting(t, db, "00000000-0000-4000-8000-000000000001", `{"n":1}`, "2026-10-09T06:00:01Z")
	client := &fakeSQS{}

	require.NoError(t, NewRelay(db, client, testQueueURL).RelayOnce(context.Background()))

	assert.Equal(t, []string{`{"n":1}`, `{"n":2}`}, client.bodies)
	assert.Equal(t, []string{testQueueURL, testQueueURL}, client.queues)
	assert.Empty(t, waitingBodies(t, db))
}

func TestRelay_KeepsUnsentEventsWhenSQSFails(t *testing.T) {
	db := openTestDB(t)
	clearOutbox(t, db)
	insertWaiting(t, db, "00000000-0000-4000-8000-000000000001", `{"n":1}`, "2026-10-09T06:00:01Z")
	insertWaiting(t, db, "00000000-0000-4000-8000-000000000002", `{"n":2}`, "2026-10-09T06:00:02Z")
	insertWaiting(t, db, "00000000-0000-4000-8000-000000000003", `{"n":3}`, "2026-10-09T06:00:03Z")
	client := &fakeSQS{failFrom: 2}

	require.NoError(t, NewRelay(db, client, testQueueURL).RelayOnce(context.Background()))

	assert.Equal(t, []string{`{"n":1}`}, client.bodies)
	assert.Equal(t, 2, client.calls, "the relay stops at the first failure")
	assert.Equal(t, []string{`{"n":2}`, `{"n":3}`}, waitingBodies(t, db), "unsent events wait for the next run")
}

func TestRelay_DoesNothingWhenTheOutboxIsEmpty(t *testing.T) {
	db := openTestDB(t)
	clearOutbox(t, db)
	client := &fakeSQS{}

	require.NoError(t, NewRelay(db, client, testQueueURL).RelayOnce(context.Background()))

	assert.Zero(t, client.calls)
}

func openTestDB(t *testing.T) *sql.DB {
	t.Helper()
	dsn := "host=" + envOrDefault("DB_HOST", "localhost") + " port=" + envOrDefault("DB_PORT", "5435") +
		" user=" + envOrDefault("DB_USER", "postgres") + " password=" + envOrDefault("DB_PASSWORD", "postgres") +
		" dbname=" + envOrDefault("DB_NAME", "accounts") + " sslmode=disable"
	db, err := sql.Open("postgres", dsn)
	require.NoError(t, err)
	require.NoError(t, db.Ping(), "connect to the test database")
	t.Cleanup(func() { _ = db.Close() })
	return db
}

func clearOutbox(t *testing.T, db *sql.DB) {
	t.Helper()
	_, err := db.Exec(`DELETE FROM audit_outbox`)
	require.NoError(t, err)
	t.Cleanup(func() { _, _ = db.Exec(`DELETE FROM audit_outbox`) })
}

func insertWaiting(t *testing.T, db *sql.DB, eventID, body, createdAt string) {
	t.Helper()
	_, err := db.Exec(`INSERT INTO audit_outbox (event_id, body, created_at) VALUES ($1, $2, $3)`, eventID, body, createdAt)
	require.NoError(t, err)
}

func waitingBodies(t *testing.T, db *sql.DB) []string {
	t.Helper()
	rows, err := db.Query(`SELECT body FROM audit_outbox ORDER BY created_at`)
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

// captureLog sends the standard logger to a buffer for one test.
func captureLog(t *testing.T) *bytes.Buffer {
	var buf bytes.Buffer
	log.SetOutput(&buf)
	t.Cleanup(func() { log.SetOutput(os.Stderr) })
	return &buf
}

func TestQueueFallback_SavedEventIsNotSentDirectly(t *testing.T) {
	outbox := &fakeOutbox{}
	queue := &fakeSQS{}

	require.NoError(t, NewQueueFallback(outbox, queue, testQueueURL).Save(context.Background(), "event-1", []byte(`{"n":1}`)))

	require.Len(t, outbox.saved, 1)
	assert.Zero(t, queue.calls, "the relay sends saved events; no direct send")
}

func TestQueueFallback_SendsTheSameEventWhenTheOutboxFails(t *testing.T) {
	logs := captureLog(t)
	outbox := &fakeOutbox{err: context.DeadlineExceeded}
	queue := &fakeSQS{}
	// The save's context has already expired, as after a timed-out outbox write.
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	require.NoError(t, NewQueueFallback(outbox, queue, testQueueURL).Save(ctx, "event-1", []byte(`{"event_id":"event-1"}`)))

	assert.Equal(t, []string{`{"event_id":"event-1"}`}, queue.bodies, "the same body, so the Auditor can drop a repeat by event_id")
	assert.Equal(t, []string{testQueueURL}, queue.queues)
	assert.Contains(t, logs.String(), "sent straight to the queue")
	assert.NotContains(t, logs.String(), "AUDIT_SEND_FAILED")
}

func TestQueueFallback_LogsTheWholeEventWhenBothFail(t *testing.T) {
	logs := captureLog(t)
	outbox := &fakeOutbox{err: errors.New("pool exhausted")}
	queue := &fakeSQS{failFrom: 1}

	err := NewQueueFallback(outbox, queue, testQueueURL).Save(context.Background(), "event-1", []byte(`{"event_id":"event-1"}`))

	require.Error(t, err)
	assert.Contains(t, logs.String(), "AUDIT_SEND_FAILED event_id=event-1")
	assert.Contains(t, logs.String(), `audit_event={"event_id":"event-1"}`, "the event is logged whole, for replay")
}
