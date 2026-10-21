#!/bin/sh
# Creates the audit queue the service publishes to locally, as the auditor's
# LocalStack does (services/auditor/localstack/init-queues.sh).
awslocal sqs create-queue --queue-name audit-insert
