#!/bin/sh
# Creates the auditor's SQS queues in LocalStack. Runs automatically on
# container startup via LocalStack's /etc/localstack/init/ready.d hook (see
# the localstack service in compose.yaml).
set -e

DLQ_URL=$(awslocal sqs create-queue --queue-name audit-insert-dlq \
	--attributes '{"MessageRetentionPeriod":"1209600"}' \
	--query 'QueueUrl' --output text)

DLQ_ARN=$(awslocal sqs get-queue-attributes --queue-url "$DLQ_URL" \
	--attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue --queue-name audit-insert \
	--attributes "{
		\"VisibilityTimeout\": \"60\",
		\"ReceiveMessageWaitTimeSeconds\": \"20\",
		\"RedrivePolicy\": \"{\\\"deadLetterTargetArn\\\":\\\"$DLQ_ARN\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\"
	}"

echo "audit-insert and audit-insert-dlq queues created in LocalStack"
