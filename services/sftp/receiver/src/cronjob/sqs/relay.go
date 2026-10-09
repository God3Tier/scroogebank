package sqs

import (
	"context"
	"database/sql"
	"fmt"
	"log"

	"receiver/src/internal/models"

	"github.com/aws/aws-sdk-go-v2/aws"
	awssqs "github.com/aws/aws-sdk-go-v2/service/sqs"
)

// relayBatchSize caps the events one batch sends, so its transaction stays short.
const relayBatchSize = 50

type API interface {
	SendMessage(context.Context, *awssqs.SendMessageInput, ...func(*awssqs.Options)) (*awssqs.SendMessageOutput, error)
}

// Relay sends saved ingestion audit events from audit_outbox to the Auditor's
// SQS queue. The poller runs it at the end of every poll (ADR-0020 decision 3).
//
// Delivery is at least once: if the poller dies after SQS accepts an event but
// before its row's delete commits, the event is sent again on a later poll,
// and the Auditor drops the repeat by event_id. A row leaves the outbox only
// after SQS has accepted it, so an SQS outage delays events instead of losing
// them.
type Relay struct {
	db       *sql.DB
	client   API
	queueURL string
}

func NewRelay(db *sql.DB, client API, queueURL string) *Relay {
	return &Relay{db: db, client: client, queueURL: queueURL}
}

// RelayOnce sends every waiting event, oldest first, deleting each once SQS
// accepts it. It stops at the first failed send and keeps that event and the
// rest for the next poll.
func (r *Relay) RelayOnce(ctx context.Context) error {
	for {
		more, err := r.relayBatch(ctx)
		if err != nil || !more {
			return err
		}
	}
}

// relayBatch sends up to relayBatchSize events. It reports whether a full
// batch was sent, in which case more may be waiting.
func (r *Relay) relayBatch(ctx context.Context) (bool, error) {
	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return false, fmt.Errorf("begin relay: %w", err)
	}
	defer tx.Rollback()

	rows, err := tx.QueryContext(ctx,
		`SELECT event_id, body FROM audit_outbox ORDER BY created_at LIMIT $1 FOR UPDATE SKIP LOCKED`, relayBatchSize)
	if err != nil {
		return false, fmt.Errorf("lock waiting audit events: %w", err)
	}
	var batch []models.AuditEvent
	for rows.Next() {
		var id, body string
		if err := rows.Scan(&id, &body); err != nil {
			rows.Close()
			return false, fmt.Errorf("read waiting audit event: %w", err)
		}
		batch = append(batch, models.AuditEvent{ID: id, Body: []byte(body)})
	}
	if err := rows.Err(); err != nil {
		return false, fmt.Errorf("read waiting audit events: %w", err)
	}

	sentAll := true
	for _, event := range batch {
		if err := r.Send(ctx, event); err != nil {
			// Events already sent are still deleted when the transaction commits.
			log.Printf("%v; will retry on the next poll", err)
			sentAll = false
			break
		}
		if _, err := tx.ExecContext(ctx, `DELETE FROM audit_outbox WHERE event_id = $1`, event.ID); err != nil {
			return false, fmt.Errorf("delete sent audit event %s: %w", event.ID, err)
		}
	}
	if err := tx.Commit(); err != nil {
		return false, fmt.Errorf("commit relay: %w", err)
	}
	return sentAll && len(batch) == relayBatchSize, nil
}

// Send delivers one event straight to SQS. Besides the relay's own sends, the
// poller uses it for an event it could not save, typically because the
// database is down.
func (r *Relay) Send(ctx context.Context, event models.AuditEvent) error {
	if _, err := r.client.SendMessage(ctx, &awssqs.SendMessageInput{
		QueueUrl:    aws.String(r.queueURL),
		MessageBody: aws.String(string(event.Body)),
	}); err != nil {
		return fmt.Errorf("send audit event %s: %w", event.ID, err)
	}
	return nil
}
