package queue

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"strconv"

	"auditor/src/internal/models"
	"auditor/src/internal/repository/audit"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/aws/aws-sdk-go-v2/service/sqs/types"
)

const (
	// waitTimeSeconds is the SQS long-poll duration: how long ReceiveMessage
	// waits for a message to arrive before returning empty, instead of
	// polling in a tight loop.
	waitTimeSeconds = 20
	// maxNumberOfMessages is the largest batch SQS will return per receive.
	maxNumberOfMessages = 10
)

// Consumer long-polls the audit-insert queue, validates and stores each
// message, and deletes it once it has been handled — either persisted,
// recognised as a duplicate delivery, or rejected to the DLQ. A message is
// left in the queue (not deleted) only when storing it fails for a reason
// that might succeed on retry, so SQS's normal visibility-timeout/redrive
// behavior can retry or eventually dead-letter it.
type Consumer struct {
	sqs      SQSAPI
	queueURL string
	repo     audit.Repository
	rejecter *Rejecter
}

func NewConsumer(client SQSAPI, queueURL string, repo audit.Repository, rejecter *Rejecter) *Consumer {
	return &Consumer{sqs: client, queueURL: queueURL, repo: repo, rejecter: rejecter}
}

// Run polls until ctx is cancelled. It is meant to be run in its own
// goroutine; cancelling ctx also interrupts an in-flight long poll rather
// than waiting up to waitTimeSeconds to notice shutdown.
func (c *Consumer) Run(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		default:
		}

		out, err := c.sqs.ReceiveMessage(ctx, &sqs.ReceiveMessageInput{
			QueueUrl:                    aws.String(c.queueURL),
			MaxNumberOfMessages:         maxNumberOfMessages,
			WaitTimeSeconds:             waitTimeSeconds,
			MessageSystemAttributeNames: []types.MessageSystemAttributeName{types.MessageSystemAttributeNameApproximateReceiveCount},
		})
		if err != nil {
			if ctx.Err() != nil {
				return // shutting down; the cancelled long poll is expected, not a failure
			}
			log.Printf("receive message failed: %v", err)
			continue
		}

		for _, msg := range out.Messages {
			c.handle(ctx, msg)
		}
	}
}

func (c *Consumer) handle(ctx context.Context, msg types.Message) {
	var event ValidEvent
	if err := json.Unmarshal([]byte(aws.ToString(msg.Body)), &event); err != nil {
		c.reject(ctx, msg, "unparseable payload: "+err.Error())
		return
	}

	if err := validatePayload(event); err != nil {
		c.reject(ctx, msg, fmt.Sprintf(
			"unauthorized: %s not permitted to send %s",
			event.SourceService, event.EventType,
		))
		return
	}

	parsedEvent := models.AuditLogInput{
		EventID:       event.EventID,
		EventType:     event.EventType,
		Action:        event.Action,
		SourceService: models.ServiceType(event.SourceService),
		ActorId:       event.ActorId,
		Payload:       string(event.Payload),
	}

	err := c.repo.StoreAuditChain(ctx, parsedEvent) // only valid, authorized events make it into the actual chain
	switch {
	case err == nil:
		c.delete(ctx, msg)

	case errors.Is(err, audit.DuplicateEventError):
		// Already processed on an earlier, redelivered attempt. SQS standard
		// queues deliver at-least-once, so this is expected, not a failure.
		log.Printf("skipped duplicate event_id=%s message_id=%s", event.EventID, aws.ToString(msg.MessageId))
		c.delete(ctx, msg)

	default:
		// Leave the message in the queue so SQS redelivers it after the
		// visibility timeout — a transient failure here should not silently
		// lose the event the way the previous Kafka auto-commit could.
		log.Printf("store audit chain failed, leaving message_id=%s for retry: %v", aws.ToString(msg.MessageId), err)
	}
}

func (c *Consumer) reject(ctx context.Context, msg types.Message, reason string) {
	c.rejecter.Send(ctx, Rejection{
		MessageID:     aws.ToString(msg.MessageId),
		ReceiptHandle: aws.ToString(msg.ReceiptHandle),
		Body:          aws.ToString(msg.Body),
		ReceiveCount:  receiveCount(msg),
		Reason:        reason,
	})
	c.delete(ctx, msg)
}

func (c *Consumer) delete(ctx context.Context, msg types.Message) {
	_, err := c.sqs.DeleteMessage(ctx, &sqs.DeleteMessageInput{
		QueueUrl:      aws.String(c.queueURL),
		ReceiptHandle: msg.ReceiptHandle,
	})
	if err != nil {
		log.Printf("delete message failed, message_id=%s: %v", aws.ToString(msg.MessageId), err)
	}
}

func receiveCount(msg types.Message) int {
	raw, ok := msg.Attributes[string(types.MessageSystemAttributeNameApproximateReceiveCount)]
	if !ok {
		return 0
	}
	count, err := strconv.Atoi(raw)
	if err != nil {
		return 0
	}
	return count
}
