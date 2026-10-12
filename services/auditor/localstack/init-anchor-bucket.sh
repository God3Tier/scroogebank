#!/bin/sh
# Creates the chain-verify anchor bucket in LocalStack, with Object Lock
# enabled (ADR-0022 decision 3). Runs automatically on container startup via
# LocalStack's /etc/localstack/init/ready.d hook (see the localstack service in
# compose.yaml).
set -e

awslocal s3api create-bucket --bucket chain-anchors --object-lock-enabled-for-bucket

echo "chain-anchors bucket created in LocalStack"
