# Customer-managed keys (about USD 1 per key per month). Their key policies are
# the default one, so IAM policies decide who may use each key; only the roles
# named in services.tf get access.

# RDS storage and the audit-db backups.
resource "aws_kms_key" "platform" {
  description             = "${local.name}: RDS storage and backups"
  enable_key_rotation     = true
  deletion_window_in_days = 7
}

resource "aws_kms_alias" "platform" {
  name          = "alias/${local.name}-platform"
  target_key_id = aws_kms_key.platform.key_id
}

# Wraps each client's data key (ADR-0030). client-service calls
# GenerateDataKey and Decrypt with the client ID as encryption context.
resource "aws_kms_key" "client_data" {
  description             = "${local.name}: wraps per-client data keys"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}

resource "aws_kms_alias" "client_data" {
  name          = "alias/${local.name}-client-data"
  target_key_id = aws_kms_key.client_data.key_id
}

# HMAC key for the email and phone blind indexes (ADR-0030 decision 6). It
# covers every client, so it is the most sensitive key in the design. HMAC
# keys cannot rotate automatically.
resource "aws_kms_key" "client_index" {
  description              = "${local.name}: blind indexes for client email and phone"
  customer_master_key_spec = "HMAC_256"
  key_usage                = "GENERATE_VERIFY_MAC"
  deletion_window_in_days  = 30
}

resource "aws_kms_alias" "client_index" {
  name          = "alias/${local.name}-client-index"
  target_key_id = aws_kms_key.client_index.key_id
}

# The ID-documents bucket's own key (ADR-0041 decision 9).
resource "aws_kms_key" "documents" {
  description             = "${local.name}: client identity documents bucket"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}

resource "aws_kms_alias" "documents" {
  name          = "alias/${local.name}-documents"
  target_key_id = aws_kms_key.documents.key_id
}
