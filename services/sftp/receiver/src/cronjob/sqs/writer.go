package sqs

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"time"

	"receiver/src/cronjob/poller"

	"github.com/aws/aws-sdk-go-v2/aws"
	awssqs "github.com/aws/aws-sdk-go-v2/service/sqs"
)

const (
	transactionEventType = "TRANSACTION_INGESTION"
	transactionSource    = "TRANSACTION_SERVICE"
	transactionActor     = "sftp-poller"
)

type API interface {
	SendMessage(context.Context, *awssqs.SendMessageInput, ...func(*awssqs.Options)) (*awssqs.SendMessageOutput, error)
}

type Writer struct {
	client   API
	queueURL string
}

func NewWriter(client API, queueURL string) *Writer {
	return &Writer{client: client, queueURL: queueURL}
}

type event struct {
	EventID       string          `json:"event_id"`
	EventType     string          `json:"event_type"`
	Action        string          `json:"action"`
	SourceService string          `json:"source_service"`
	ActorID       string          `json:"actor_id"`
	Payload       json.RawMessage `json:"payload"`
}

type completedPayload struct {
	FileName         string    `json:"file_name"`
	ProcessingStart  time.Time `json:"processing_start"`
	ProcessingEnd    time.Time `json:"processing_end"`
	Status           string    `json:"status"`
	RecordsProcessed int       `json:"records_processed"`
	RecordsAccepted  int       `json:"records_accepted"`
	RecordsRejected  int       `json:"records_rejected"`
	ParseFailures    []failure `json:"parse_failures,omitempty"`
}

type failure struct {
	RecordRef string `json:"record_ref"`
	Reason    string `json:"reason"`
}

type processingErrorPayload struct {
	FileName string `json:"file_name"`
	Stage    string `json:"stage"`
	Error    string `json:"error"`
}

func (w *Writer) WriteFileCompleted(ctx context.Context, result poller.FileCompleted) error {
	failures := make([]failure, 0, len(result.ParseFailures))
	for _, item := range result.ParseFailures {
		failures = append(failures, failure{RecordRef: item.RecordRef, Reason: item.Reason})
	}
	payload := completedPayload{
		FileName: result.FileName, ProcessingStart: result.ProcessingStart,
		ProcessingEnd: result.ProcessingEnd,
		Status:        result.Status, RecordsProcessed: result.RecordsProcessed, RecordsAccepted: result.RecordsAccepted,
		RecordsRejected: result.RecordsRejected, ParseFailures: failures,
	}
	return w.send(ctx, "FILE_PROCESSING_COMPLETED", result.FileName, payload)
}

func (w *Writer) WriteProcessingError(ctx context.Context, item poller.ProcessingError) error {
	payload := processingErrorPayload{FileName: item.FileName, Stage: item.Stage, Error: item.Error}
	return w.send(ctx, item.Action, item.FileName, payload)
}

func (w *Writer) send(ctx context.Context, action, identity string, payload any) error {
	encodedPayload, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("marshal audit payload: %w", err)
	}
	message := event{
		EventID: eventID(action, identity, encodedPayload), EventType: transactionEventType, Action: action,
		SourceService: transactionSource, ActorID: transactionActor, Payload: encodedPayload,
	}
	body, err := json.Marshal(message)
	if err != nil {
		return fmt.Errorf("marshal audit event: %w", err)
	}
	if _, err := w.client.SendMessage(ctx, &awssqs.SendMessageInput{QueueUrl: aws.String(w.queueURL), MessageBody: aws.String(string(body))}); err != nil {
		return fmt.Errorf("send %s audit event: %w", action, err)
	}
	return nil
}

func eventID(action, identity string, payload []byte) string {
	hash := sha256.New()
	_, _ = hash.Write([]byte(transactionSource + "\x00" + action + "\x00" + identity + "\x00"))
	_, _ = hash.Write(payload)
	return hex.EncodeToString(hash.Sum(nil))
}
