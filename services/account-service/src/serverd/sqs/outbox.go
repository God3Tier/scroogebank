package sqs

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
)

const (
	// relayInterval is how often the relay checks the outbox for events to send.
	relayInterval = 2 * time.Second
	// relayBatchSize caps the events one run sends, so its transaction stays short.
	relayBatchSize = 50
	// saveTimeout bounds the outbox write that follows each request. The
	// endpoint has already answered, so it can wait out a burst (ADR-0078).
	saveTimeout = 5 * time.Second
	// fallbackTimeout bounds the direct send to the queue when the outbox
	// write has failed (ADR-0078).
	fallbackTimeout = 3 * time.Second
)

type SQSAPI interface {
	SendMessage(context.Context, *sqs.SendMessageInput, ...func(*sqs.Options)) (*sqs.SendMessageOutput, error)
}

// Outbox holds audit events until the relay sends them to the Auditor (ADR-0020).
type Outbox interface {
	Save(ctx context.Context, eventID string, body []byte) error
}

// PostgresOutbox keeps audit events in the audit_outbox table.
type PostgresOutbox struct {
	db *sql.DB
}

func NewPostgresOutbox(db *sql.DB) *PostgresOutbox {
	return &PostgresOutbox{db: db}
}

// Save writes one event in a transaction of its own. The request's change has
// already committed, so a crash between the two loses the event; this is the
// exception ADR-0020 decision 1 allows for audit middleware.
func (o *PostgresOutbox) Save(ctx context.Context, eventID string, body []byte) error {
	if _, err := o.db.ExecContext(ctx,
		`INSERT INTO audit_outbox (event_id, body) VALUES ($1, $2)`, eventID, string(body)); err != nil {
		return fmt.Errorf("save audit event %s: %w", eventID, err)
	}
	return nil
}

// QueueFallback saves each event to the outbox and, if that fails, sends it
// straight to the Auditor's queue, so a burst that holds every database
// connection does not lose events (ADR-0078; ADR-0020 allows direct sends).
// The event keeps its event_id, and the Auditor drops a repeat by event_id,
// so an event that reached both does no harm. Only when both fail is the
// event lost; it is then logged whole as AUDIT_SEND_FAILED, which is alarmed,
// for an operator to send again.
type QueueFallback struct {
	outbox   Outbox
	client   SQSAPI
	queueURL string
}

func NewQueueFallback(outbox Outbox, client SQSAPI, queueURL string) *QueueFallback {
	return &QueueFallback{outbox: outbox, client: client, queueURL: queueURL}
}

func (q *QueueFallback) Save(ctx context.Context, eventID string, body []byte) error {
	saveErr := q.outbox.Save(ctx, eventID, body)
	if saveErr == nil {
		return nil
	}
	// ctx may have expired with the failed save; the send gets its own time.
	sendCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), fallbackTimeout)
	defer cancel()
	_, sendErr := q.client.SendMessage(sendCtx, &sqs.SendMessageInput{
		QueueUrl:    aws.String(q.queueURL),
		MessageBody: aws.String(string(body)),
	})
	if sendErr == nil {
		log.Printf("audit event %s sent straight to the queue; the outbox save failed: %v", eventID, saveErr)
		return nil
	}
	log.Printf("AUDIT_SEND_FAILED event_id=%s outbox_error=%q queue_error=%q audit_event=%s", eventID, saveErr, sendErr, body)
	return fmt.Errorf("audit event %s neither saved nor sent: %w", eventID, errors.Join(saveErr, sendErr))
}

// Relay sends audit events from the outbox to the Auditor's SQS queue.
//
// Delivery is at least once: if the service dies after SQS accepts an event
// but before its row's delete commits, the event is sent again on a later run,
// and the Auditor drops the repeat by event_id. A row leaves the outbox only
// after SQS has accepted it, so an SQS outage delays events instead of losing
// them.
type Relay struct {
	db       *sql.DB
	client   SQSAPI
	queueURL string
}

func NewRelay(db *sql.DB, client SQSAPI, queueURL string) *Relay {
	return &Relay{db: db, client: client, queueURL: queueURL}
}

// Run relays waiting events every relayInterval until ctx is done.
func (r *Relay) Run(ctx context.Context) {
	ticker := time.NewTicker(relayInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := r.RelayOnce(ctx); err != nil {
				log.Printf("audit relay: %v", err)
			}
		}
	}
}

// RelayOnce sends up to relayBatchSize of the oldest waiting events, deleting
// each once SQS accepts it. FOR UPDATE SKIP LOCKED lets each replica take
// different rows. It stops at the first failed send and keeps that event and
// the rest for the next run.
func (r *Relay) RelayOnce(ctx context.Context) error {
	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin relay: %w", err)
	}
	defer tx.Rollback()

	rows, err := tx.QueryContext(ctx,
		`SELECT event_id, body FROM audit_outbox ORDER BY created_at LIMIT $1 FOR UPDATE SKIP LOCKED`, relayBatchSize)
	if err != nil {
		return fmt.Errorf("lock waiting audit events: %w", err)
	}
	type waiting struct{ id, body string }
	var batch []waiting
	for rows.Next() {
		var event waiting
		if err := rows.Scan(&event.id, &event.body); err != nil {
			rows.Close()
			return fmt.Errorf("read waiting audit event: %w", err)
		}
		batch = append(batch, event)
	}
	if err := rows.Err(); err != nil {
		return fmt.Errorf("read waiting audit events: %w", err)
	}

	for _, event := range batch {
		if _, err := r.client.SendMessage(ctx, &sqs.SendMessageInput{
			QueueUrl:    aws.String(r.queueURL),
			MessageBody: aws.String(event.body),
		}); err != nil {
			// Events already sent are still deleted when the transaction commits.
			log.Printf("audit event %s not sent; will retry: %v", event.id, err)
			break
		}
		if _, err := tx.ExecContext(ctx, `DELETE FROM audit_outbox WHERE event_id = $1`, event.id); err != nil {
			return fmt.Errorf("delete sent audit event %s: %w", event.id, err)
		}
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit relay: %w", err)
	}
	return nil
}
