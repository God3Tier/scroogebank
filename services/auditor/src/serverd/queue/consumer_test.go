package queue

import (
	"context"
	"errors"
	"sync"
	"testing"

	"auditor/src/internal/models"
	"auditor/src/internal/repository/audit"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/aws/aws-sdk-go-v2/service/sqs/types"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// fakeSQS is a minimal in-memory SQSAPI used so these tests exercise the
// consumer/rejecter logic without talking to real AWS or LocalStack.
type fakeSQS struct {
	mu sync.Mutex

	receiveOut *sqs.ReceiveMessageOutput
	receiveErr error

	deletedReceiptHandles []string
	deleteErr             error

	sentMessages []string // raw MessageBody of every SendMessage call
	sendErr      error
}

func (f *fakeSQS) ReceiveMessage(ctx context.Context, params *sqs.ReceiveMessageInput, optFns ...func(*sqs.Options)) (*sqs.ReceiveMessageOutput, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.receiveErr != nil {
		return nil, f.receiveErr
	}
	out := f.receiveOut
	f.receiveOut = &sqs.ReceiveMessageOutput{} // only serve the queued batch once
	return out, nil
}

func (f *fakeSQS) DeleteMessage(ctx context.Context, params *sqs.DeleteMessageInput, optFns ...func(*sqs.Options)) (*sqs.DeleteMessageOutput, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.deleteErr != nil {
		return nil, f.deleteErr
	}
	f.deletedReceiptHandles = append(f.deletedReceiptHandles, aws.ToString(params.ReceiptHandle))
	return &sqs.DeleteMessageOutput{}, nil
}

func (f *fakeSQS) SendMessage(ctx context.Context, params *sqs.SendMessageInput, optFns ...func(*sqs.Options)) (*sqs.SendMessageOutput, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.sendErr != nil {
		return nil, f.sendErr
	}
	f.sentMessages = append(f.sentMessages, aws.ToString(params.MessageBody))
	return &sqs.SendMessageOutput{}, nil
}

func validEventBody(t *testing.T, eventID string) string {
	t.Helper()
	return `{"event_id":"` + eventID + `","event_type":"CRUD","action":"UPDATE","source_service":"CRM","actor_id":"actor-1","payload":{"agent_id":"AGT-1"}}`
}

func TestConsumer_Handle_SuccessfulStore_DeletesMessage(t *testing.T) {
	ctx := context.Background()
	sqsClient := &fakeSQS{}
	repo := audit.NewMockRepository(t)

	repo.On("StoreAuditChain", ctx, models.AuditLogInput{
		EventID:       "evt-1",
		EventType:     "CRUD",
		Action:        "UPDATE",
		SourceService: models.CRMServiceType,
		ActorId:       "actor-1",
		Payload:       `{"agent_id":"AGT-1"}`,
	}).Return(nil).Once()

	consumer := NewConsumer(sqsClient, "queue-url", repo, NewRejecter(sqsClient, "dlq-url"))
	consumer.handle(ctx, types.Message{
		MessageId:     aws.String("msg-1"),
		ReceiptHandle: aws.String("receipt-1"),
		Body:          aws.String(validEventBody(t, "evt-1")),
	})

	assert.Equal(t, []string{"receipt-1"}, sqsClient.deletedReceiptHandles)
	assert.Empty(t, sqsClient.sentMessages, "a successfully stored event must not be sent to the DLQ")
}

func TestConsumer_Handle_StoreFails_LeavesMessageForRetry(t *testing.T) {
	ctx := context.Background()
	sqsClient := &fakeSQS{}
	repo := audit.NewMockRepository(t)

	repo.On("StoreAuditChain", ctx, models.AuditLogInput{
		EventID:       "evt-2",
		EventType:     "CRUD",
		Action:        "UPDATE",
		SourceService: models.CRMServiceType,
		ActorId:       "actor-1",
		Payload:       `{"agent_id":"AGT-1"}`,
	}).Return(errors.New("db unavailable")).Once()

	consumer := NewConsumer(sqsClient, "queue-url", repo, NewRejecter(sqsClient, "dlq-url"))
	consumer.handle(ctx, types.Message{
		MessageId:     aws.String("msg-2"),
		ReceiptHandle: aws.String("receipt-2"),
		Body:          aws.String(validEventBody(t, "evt-2")),
	})

	assert.Empty(t, sqsClient.deletedReceiptHandles, "a message that failed to store must not be deleted, so SQS redelivers it")
}

func TestConsumer_Handle_DuplicateEvent_DeletesWithoutRewriting(t *testing.T) {
	ctx := context.Background()
	sqsClient := &fakeSQS{}
	repo := audit.NewMockRepository(t)

	repo.On("StoreAuditChain", ctx, models.AuditLogInput{
		EventID:       "evt-3",
		EventType:     "CRUD",
		Action:        "UPDATE",
		SourceService: models.CRMServiceType,
		ActorId:       "actor-1",
		Payload:       `{"agent_id":"AGT-1"}`,
	}).Return(audit.DuplicateEventError).Once()

	consumer := NewConsumer(sqsClient, "queue-url", repo, NewRejecter(sqsClient, "dlq-url"))
	consumer.handle(ctx, types.Message{
		MessageId:     aws.String("msg-3"),
		ReceiptHandle: aws.String("receipt-3"),
		Body:          aws.String(validEventBody(t, "evt-3")),
	})

	assert.Equal(t, []string{"receipt-3"}, sqsClient.deletedReceiptHandles, "a redelivered duplicate should still be deleted so it doesn't loop forever")
}

func TestConsumer_Handle_UnparseablePayload_RejectedToDLQAndDeleted(t *testing.T) {
	ctx := context.Background()
	sqsClient := &fakeSQS{}
	repo := audit.NewMockRepository(t) // no StoreAuditChain expectation: must not be called

	consumer := NewConsumer(sqsClient, "queue-url", repo, NewRejecter(sqsClient, "dlq-url"))
	consumer.handle(ctx, types.Message{
		MessageId:     aws.String("msg-4"),
		ReceiptHandle: aws.String("receipt-4"),
		Body:          aws.String("not json"),
	})

	assert.Equal(t, []string{"receipt-4"}, sqsClient.deletedReceiptHandles)
	require.Len(t, sqsClient.sentMessages, 1)
	assert.Contains(t, sqsClient.sentMessages[0], "unparseable payload")
}

func TestConsumer_Handle_UnauthorizedService_RejectedToDLQAndDeleted(t *testing.T) {
	ctx := context.Background()
	sqsClient := &fakeSQS{}
	repo := audit.NewMockRepository(t) // no StoreAuditChain expectation: must not be called

	body := `{"event_id":"evt-5","event_type":"TRANSACTION_INGESTION","action":"FILE_PROCESSING_COMPLETED","source_service":"CRM","actor_id":"actor-1","payload":{}}`

	consumer := NewConsumer(sqsClient, "queue-url", repo, NewRejecter(sqsClient, "dlq-url"))
	consumer.handle(ctx, types.Message{
		MessageId:     aws.String("msg-5"),
		ReceiptHandle: aws.String("receipt-5"),
		Body:          aws.String(body),
	})

	assert.Equal(t, []string{"receipt-5"}, sqsClient.deletedReceiptHandles)
	require.Len(t, sqsClient.sentMessages, 1)
	assert.Contains(t, sqsClient.sentMessages[0], "unauthorized")
}
