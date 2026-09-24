package queue

import (
	"context"

	"github.com/aws/aws-sdk-go-v2/service/sqs"
)

// SQSAPI is the subset of the SQS client the auditor depends on. Depending
// on this interface instead of *sqs.Client directly lets tests substitute a
// fake implementation instead of talking to real AWS or LocalStack.
type SQSAPI interface {
	ReceiveMessage(ctx context.Context, params *sqs.ReceiveMessageInput, optFns ...func(*sqs.Options)) (*sqs.ReceiveMessageOutput, error)
	DeleteMessage(ctx context.Context, params *sqs.DeleteMessageInput, optFns ...func(*sqs.Options)) (*sqs.DeleteMessageOutput, error)
	SendMessage(ctx context.Context, params *sqs.SendMessageInput, optFns ...func(*sqs.Options)) (*sqs.SendMessageOutput, error)
}
