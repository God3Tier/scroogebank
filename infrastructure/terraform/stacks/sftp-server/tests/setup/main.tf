# Throwaway host keys for host_key.tftest.hcl, written under .terraform/ (git
# ignored) so no private key is committed. terraform test deletes them when it
# finishes.

terraform {
  required_providers {
    local = {
      source  = "hashicorp/local"
      version = "~> 2.5"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }
}

resource "tls_private_key" "ed25519" {
  algorithm = "ED25519"
}

resource "tls_private_key" "rsa" {
  algorithm = "RSA"
  rsa_bits  = 2048
}

resource "local_sensitive_file" "ed25519" {
  filename = ".terraform/test-host-keys/ed25519"
  content  = tls_private_key.ed25519.private_key_openssh
}

# With CRLF line endings, as a key saved on Windows can have.
resource "local_sensitive_file" "rsa_crlf" {
  filename = ".terraform/test-host-keys/rsa_crlf"
  content  = replace(tls_private_key.rsa.private_key_openssh, "\n", "\r\n")
}

output "ed25519_file" {
  value = local_sensitive_file.ed25519.filename
}

output "ed25519_key" {
  value     = tls_private_key.ed25519.private_key_openssh
  sensitive = true
}

output "ed25519_fingerprint" {
  value = tls_private_key.ed25519.public_key_fingerprint_sha256
}

output "rsa_crlf_file" {
  value = local_sensitive_file.rsa_crlf.filename
}

output "rsa_key" {
  value     = tls_private_key.rsa.private_key_openssh
  sensitive = true
}

output "rsa_fingerprint" {
  value = tls_private_key.rsa.public_key_fingerprint_sha256
}
