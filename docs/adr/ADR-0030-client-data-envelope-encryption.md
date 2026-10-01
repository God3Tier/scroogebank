# ADR-0030: Client data encryption: field-level envelope encryption with per-client keys in DynamoDB wrapped by KMS

- Status: Proposed
- Decision date/time: 2026-10-01 21:50 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/target-architecture`
- Related issue/PR: TBD (pull request from `feat/target-architecture` to `dev`)
- Supersedes: N/A
- Superseded by: N/A

## Context

Feature 2 stores client personal data and requires encryption in transit and at rest. NFR 8 requires GDPR compliance, including the right to erasure. Deleting a client's rows does not remove copies already in database backups or audit payloads. RDS storage encryption protects the disks, but not a leaked SQL dump or snapshot restored by someone with access.

The work on `feat/client-profile-management-go` differs from this design:
- keys are stored in plaintext in a Postgres table;
- there is no key generation through KMS;
- search hashes are unkeyed SHA-256.

## Decision Drivers

- Protect client personal data even if the database or a snapshot leaks (NFR 8)
- GDPR erasure that also covers backups (crypto-shredding)
- Least privilege: only client-service can decrypt
- Cost

## Options Considered

### Option A: RDS storage encryption only

- Pros: nothing to build.
- Cons: anyone who can query the database or restore a snapshot reads every field; erasure cannot reach backups.

### Option B: application encryption with keys stored in plaintext

- Pros: simple.
- Cons: anyone who reads the key table can decrypt all client data.

### Option C: per-client keys stored in clients-db, wrapped by KMS

- Pros: keys are protected by KMS.
- Cons: one leaked snapshot holds both the ciphertext and the wrapped keys; access to the KMS key is the only remaining barrier.

### Option D: per-client keys in a separate DynamoDB table, wrapped by a symmetric KMS key (chosen)

- Pros:
  - a leaked clients-db snapshot is useless without both the client-keys table and permission to use the KMS key;
  - deleting one item crypto-shreds one client;
  - DynamoDB is regional, multi-AZ and has point-in-time recovery.
- Cons:
  - encrypted fields cannot be searched or sorted in SQL;
  - KMS calls are added to reads and writes;
  - there is one more data store.

## Decision

1. client-service encrypts every client field except the client UUID, owning agent ID, status and timestamps. It uses **AES-256-GCM** in the application.
2. **One data key per client.** On client creation, client-service:
   - calls KMS `GenerateDataKey` on a symmetric customer-managed key, passing the client ID as encryption context;
   - encrypts with the plaintext key;
   - stores only the **wrapped** key in the DynamoDB `client-keys` table, keyed by client UUID.
3. **Reads:** client-service fetches the wrapped key and calls KMS `Decrypt`. It caches the plaintext key in memory briefly to limit KMS calls.
4. **Access:**
   - only client-service's IAM role can read the table and use the key;
   - the table is reached through the DynamoDB gateway endpoint;
   - the table policy can require that endpoint.
5. **Erasure:** deleting the client's item from client-keys crypto-shreds all of their encrypted data, including copies in backups. Erasure is complete once the point-in-time recovery window (up to 35 days) and the backup retention have passed.
6. **Search:** each field the UI must search gets a blind index, a **keyed** hash stored next to the ciphertext, or the field stays plaintext ([open item 12](../architecture/open-items.md#12-searching-encrypted-client-fields)).

## Rationale

- Option D is the only option where neither a database leak nor a key-table leak alone exposes data.
- It makes erasure reach backups.
- Its cost is negligible: DynamoDB on demand is well under USD 1/month, and the KMS key is about USD 1/month.

## Consequences

### Positive

- Strong protection of client personal data at rest.
- Erasure works without rewriting backups or the audit chain ([ADR-0022](ADR-0022-audit-tamper-resistance-and-crypto-shredding.md)).

### Negative / Trade-offs

- No SQL search or sort on encrypted fields without blind indexes.
- client-service depends on KMS and DynamoDB being available. Short-lived key caching reduces this.
- Losing the client-keys table makes all client data unreadable, so it is in the AWS Backup plan with point-in-time recovery.
- The current branch implementation must change: KMS-wrapped keys in DynamoDB, key generation, and keyed hashes.

## Security & Privacy Impact

- Encryption context binds each wrapped key to its own client.
- KMS key policy and IAM restrict use to client-service.
- Unkeyed hashes of low-entropy fields such as NRIC can be reversed by brute force, so blind indexes must use a secret key.
- Whether audit payloads use client-keys or a separate auditor key is [open item 11](../architecture/open-items.md#11-audit-payloads-with-client-personal-data).

## Cost Impact

DynamoDB on demand is well under USD 1/month at this scale. The KMS key costs about USD 1/month, plus request charges reduced by caching.

## Operational Impact

- client-keys in the AWS Backup plan with point-in-time recovery.
- KMS key rotation enabled.
- Alarms on KMS and DynamoDB errors in client-service.

## Validation

- A clients-db dump shows ciphertext only for personal fields.
- Deleting a client's key makes their data and backups unreadable, while other clients still decrypt.
- A role other than client-service's cannot read client-keys or use the KMS key.

## References

- [Client data encryption (envelope encryption)](../security/security-model.md#client-data-encryption-envelope-encryption)
- [Right to erasure](../security/security-model.md#right-to-erasure-nfr-8-gdpr)
- [Open items 11](../architecture/open-items.md#11-audit-payloads-with-client-personal-data) and [12](../architecture/open-items.md#12-searching-encrypted-client-fields)
- AWS documentation: KMS `GenerateDataKey` and encryption context; DynamoDB point-in-time recovery
