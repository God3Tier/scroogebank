package queue

import (
	"context"
	"encoding/json"
	"log"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/google/uuid"
)

// RejectedEvent is the payload written to the dead-letter queue for a
// message the auditor could not accept — either malformed or sent by a
// service not authorized to send that event type.
type RejectedEvent struct {
	ID           string    `json:"id"`
	MessageID    string    `json:"message_id"`
	ReceiveCount int       `json:"receive_count"`
	RawPayload   string    `json:"raw_payload"` // the raw bytes, so you can inspect what actually broke
	Reason       string    `json:"reason"`
	RejectedAt   time.Time `json:"rejected_at"`
}

// Rejecter sends rejected events to the dead-letter queue. Unlike the
// previous Kafka DLQ writer, a failed send here never stalls processing of
// later messages: it is logged and the caller still deletes the original
// message from the main queue, since retrying an unparseable or
// unauthorized payload would only fail the same way again.
type Rejecter struct {
	sqs    SQSAPI
	dlqURL string
}

func NewRejecter(client SQSAPI, dlqURL string) *Rejecter {
	return &Rejecter{sqs: client, dlqURL: dlqURL}
}

func (r *Rejecter) Send(ctx context.Context, rejection Rejection) {
	event := RejectedEvent{
		ID:           uuid.NewString(),
		MessageID:    rejection.MessageID,
		ReceiveCount: rejection.ReceiveCount,
		RawPayload:   rejection.Body,
		Reason:       rejection.Reason,
		RejectedAt:   time.Now().UTC(),
	}

	body, err := json.Marshal(event)
	if err != nil {
		log.Printf("CRITICAL: failed to marshal rejection, message_id=%s: %v", rejection.MessageID, err)
		return
	}

	if _, err := r.sqs.SendMessage(ctx, &sqs.SendMessageInput{
		QueueUrl:    aws.String(r.dlqURL),
		MessageBody: aws.String(string(body)),
	}); err != nil {
		log.Printf("CRITICAL: failed to publish rejection to DLQ, message_id=%s: %v", rejection.MessageID, err)
		return
	}

	log.Printf("rejected event published to DLQ, message_id=%s reason=%s", rejection.MessageID, rejection.Reason)
}
