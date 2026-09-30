package queue

import (
	"context"
	"errors"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestRejecter_Send_PublishesRejectedEvent(t *testing.T) {
	sqsClient := &fakeSQS{}
	rejecter := NewRejecter(sqsClient, "dlq-url")

	rejecter.Send(context.Background(), Rejection{
		MessageID:     "msg-1",
		ReceiptHandle: "receipt-1",
		Body:          `{"event_type":"CRUD"}`,
		ReceiveCount:  2,
		Reason:        "unauthorized: CRM not permitted to send TRANSACTION_INGESTION",
	})

	require.Len(t, sqsClient.sentMessages, 1)
	assert.Contains(t, sqsClient.sentMessages[0], "unauthorized: CRM not permitted to send TRANSACTION_INGESTION")
	assert.Contains(t, sqsClient.sentMessages[0], `"message_id":"msg-1"`)
}

func TestRejecter_Send_SendFailureDoesNotPanicOrBlock(t *testing.T) {
	sqsClient := &fakeSQS{sendErr: errors.New("dlq unavailable")}
	rejecter := NewRejecter(sqsClient, "dlq-url")

	// Must simply log and return — a failed DLQ send must never stall
	// processing of later messages the way the old Kafka DLQ writer did.
	rejecter.Send(context.Background(), Rejection{
		MessageID: "msg-2",
		Reason:    "unparseable payload",
	})

	assert.Empty(t, sqsClient.sentMessages)
}
