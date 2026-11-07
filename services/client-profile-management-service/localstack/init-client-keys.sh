#!/bin/sh
# Creates client-service's encryption resources in LocalStack (ADR-0030). Runs
# automatically on container startup via LocalStack's
# /etc/localstack/init/ready.d hook (see the localstack service in
# docker-compose.yaml).
#   alias/client-data         symmetric key that wraps each client's data key
#   alias/client-blind-index  HMAC key for the email and phone blind indexes
#   client-keys               table of wrapped data keys, keyed by client_id
#   alias/client-documents    symmetric key the documents bucket encrypts with
#   client-documents          private bucket for identity documents (ADR-0041),
#                             made last: make db-up waits for its encryption
set -e

DATA_KEY=$(awslocal kms create-key --description "client data keys (local)" \
	--query KeyMetadata.KeyId --output text)
awslocal kms create-alias --alias-name alias/client-data --target-key-id "$DATA_KEY"

INDEX_KEY=$(awslocal kms create-key --description "client blind indexes (local)" \
	--key-spec HMAC_256 --key-usage GENERATE_VERIFY_MAC \
	--query KeyMetadata.KeyId --output text)
awslocal kms create-alias --alias-name alias/client-blind-index --target-key-id "$INDEX_KEY"

awslocal dynamodb create-table --table-name client-keys \
	--attribute-definitions AttributeName=client_id,AttributeType=S \
	--key-schema AttributeName=client_id,KeyType=HASH \
	--billing-mode PAY_PER_REQUEST >/dev/null

DOCUMENTS_KEY=$(awslocal kms create-key --description "client identity documents bucket (local)" \
	--query KeyMetadata.KeyId --output text)
awslocal kms create-alias --alias-name alias/client-documents --target-key-id "$DOCUMENTS_KEY"
awslocal s3 mb s3://client-documents >/dev/null
awslocal s3api put-public-access-block --bucket client-documents \
	--public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
awslocal s3api put-bucket-encryption --bucket client-documents \
	--server-side-encryption-configuration "{\"Rules\":[{\"ApplyServerSideEncryptionByDefault\":{\"SSEAlgorithm\":\"aws:kms\",\"KMSMasterKeyID\":\"$DOCUMENTS_KEY\"},\"BucketKeyEnabled\":true}]}"

echo "client-data, client-blind-index and client-documents keys, the client-keys table and the client-documents bucket created in LocalStack"
