# ADR-0030: Client data encryption: field-level envelope encryption with per-client keys in DynamoDB wrapped by KMS

- Status: Accepted
- Decision date/time: 2026-10-09 19:09 +08:00 (Asia/Singapore); first proposed 2026-10-01 21:50 +08:00
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/target-architecture` (proposed); `task/governance/resolve-adrs` (amended, accepted); implementation continues on `feat/client-profile-management-go`
- Related issue/PR: TBD
- Supersedes: N/A
- Superseded by: N/A

## Context

Feature 2 stores client personal data and requires encryption in transit and at rest. NFR 8 requires GDPR compliance, including the right to erasure. Deleting a client's rows does not remove copies already in database backups or audit payloads. RDS storage encryption protects the disks, but not a leaked SQL dump or snapshot restored by someone with access.

On `dev`, client-service (`client-profile-management-service`) stores every personal field as a plain column, and email and phone number are unique through plain indexes. Earlier work on `feat/client-profile-management-go` (commits 01f4f4e and 6193bf3, 2026-09-24) encrypted the fields but differed from this design:
- keys were stored in plaintext in a Postgres table in the same database as the data;
- no code created a key, so creating a client could not succeed;
- search hashes were unkeyed SHA-256, which can be reversed by trying every phone number;
- the service had no running server.

That code was dropped from `dev` in merge e4f5028 (2026-09-30), which resolved conflicts in favour of a separate rewrite of the service; its commits are still in `dev`'s history. The implementation continues on that branch, brought up to date with `dev`, and builds on the rewritten service: it keeps the branch's approach (AES-GCM in the repository, the same fields, a hash column for each unique field) and replaces its key storage and hashing as decided below.

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

1. **Encrypted fields.** client-service encrypts the 11 personal fields: first name, last name, date of birth, gender, email, phone number, address, city, state, country and postal code. The client UUID, owning agent ID, verification status and timestamps stay plaintext, because lookups, ownership checks and the dashboard figures need them. Encryption is **AES-256-GCM** in the application, with a random nonce per value. Each value's additional authenticated data is the client ID and the column name, so a ciphertext cannot be moved to another client or another field without failing to decrypt.
2. **One data key per client.** On client creation, client-service:
   - computes the blind indexes and checks for a duplicate (decision 6), before any key exists;
   - calls KMS `GenerateDataKey` on a symmetric customer-managed key, passing the client ID as encryption context;
   - stores only the **wrapped** key in the DynamoDB `client-keys` table, keyed by client UUID, with a condition that refuses to overwrite an existing item;
   - encrypts the fields with the plaintext key and inserts the row.

   The key is stored before the row, so a row never exists without its key. If the insert fails, client-service deletes the new key; a failed delete leaves an unused key, which is harmless.
3. **Reads:** client-service fetches the wrapped key and calls KMS `Decrypt` with the same encryption context. It caches the plaintext key in memory for up to 5 minutes to limit KMS calls. [ADR-0072](ADR-0072-client-key-cache-sized-for-the-working-set.md) sizes that cache for every client a task serves and decrypts a page's rows in parallel; the 5-minute limit is unchanged.
4. **Access:**
   - only client-service's IAM role can read the table and use the keys;
   - the table is reached through the DynamoDB gateway endpoint;
   - the table policy can require that endpoint.
5. **Erasure:** deleting the client's item from client-keys crypto-shreds all of their encrypted data, including copies in backups. Erasure must also clear the client's blind-index columns (decision 6), because the shared index key is not deleted with the client. Erasure is complete once the point-in-time recovery window (up to 35 days) and the backup retention have passed. When erasure runs is [open item 21](../architecture/open-items.md#21-erasing-soft-deleted-client-data).
6. **Blind indexes for email and phone only.** client-service has no search endpoint, so no other field needs one ([open item 12](../architecture/open-items.md#12-searching-encrypted-client-fields)). Email and phone number must stay unique among clients that are not deleted, so each gets a blind index column next to its ciphertext:
   - **Key:** one **shared** KMS HMAC key (`HMAC_256`), used through KMS `GenerateMac` with `HMAC_SHA_256`. The key never leaves KMS, so it cannot be copied out and used offline.
   - **Why shared:** a duplicate check compares a new value with every existing client before the new client exists, so every row's index must use the same key.
   - **Normalisation before hashing:** an email is trimmed and lowercased; a phone number is trimmed and has spaces, hyphens, dots and brackets removed, keeping a leading `+`. No country code is assumed. The stored value is the one the agent entered.
   - **Uniqueness:** partial unique indexes on the two index columns, `WHERE deleted_at IS NULL`, so a deleted client's email and phone can be reused ([ADR-0036](ADR-0036-soft-deletion-of-clients-and-accounts.md)).
   - **What sharing means:** the key covers every client's email and phone, so it needs the strongest protection of any key here. Rotating it means recomputing every row's indexes in a batch, which decrypts each client's email and phone.

## Rationale

- Option D is the only option where neither a database leak nor a key-table leak alone exposes data.
- It makes erasure reach backups.
- A keyed index keeps uniqueness without letting anyone with a database copy test or recover emails and phone numbers. Keeping that key in KMS makes every guess an audited, throttled KMS call.
- Its cost is negligible: DynamoDB on demand is well under USD 1/month, and each KMS key is about USD 1/month.

## Consequences

### Positive

- Strong protection of client personal data at rest.
- Erasure works without rewriting backups or the audit chain ([ADR-0022](ADR-0022-audit-tamper-resistance-and-crypto-shredding.md)).
- Duplicate emails and phone numbers are caught regardless of case or formatting.

### Negative / Trade-offs

- No SQL search or sort on encrypted fields without further blind indexes.
- client-service depends on KMS and DynamoDB being available. Short-lived key caching reduces this for reads; creating or updating a client always needs two `GenerateMac` calls.
- The database no longer checks field lengths, because it stores ciphertext; client-service's validation does.
- The business dashboard's action items include client names, so client-service decrypts them for each row it returns.
- Losing the client-keys table makes all client data unreadable, so it is in the AWS Backup plan with point-in-time recovery.

## Security & Privacy Impact

- Encryption context binds each wrapped key to its own client; additional authenticated data binds each value to its client and field.
- KMS key policies and IAM restrict use of both keys to client-service.
- Unkeyed hashes of low-entropy fields such as phone numbers can be reversed by brute force, so the blind indexes use the shared KMS HMAC key.
- Whether audit payloads use client-keys or a separate auditor key is [open item 11](../architecture/open-items.md#11-audit-payloads-with-client-personal-data). Client-service's audit events carry IDs only, so the question does not arise yet.

## Cost Impact

DynamoDB on demand is well under USD 1/month at this scale. The two KMS keys cost about USD 1/month each, plus request charges, reduced for reads by caching.

## Operational Impact

- client-keys in the AWS Backup plan with point-in-time recovery.
- Automatic rotation enabled on the data-key KMS key. The HMAC key is rotated only by the batch recompute in decision 6.
- Alarms on KMS and DynamoDB errors in client-service.

## Validation

- A clients-db dump shows ciphertext only for personal fields, and 64-character hex blind indexes for email and phone.
- Deleting a client's key makes their data and backups unreadable, while other clients still decrypt.
- A ciphertext copied to another client or field fails to decrypt.
- Two emails that differ only in case, or two phone numbers that differ only in formatting, count as duplicates; a deleted client's email and phone can be reused.
- A role other than client-service's cannot read client-keys or use either KMS key.

## Revision history

- 2026-10-01 21:50 +08:00: proposed.
- 2026-10-09 19:09 +08:00: amended before acceptance, then accepted by the decision owner. The context now describes `dev`, which has no field encryption, and records what is kept from the earlier branch work and what is replaced. Decision 1 lists the 11 fields and adds additional authenticated data. Decision 2 states the creation order. Decision 5 adds clearing the blind indexes. Decision 6 is rewritten: blind indexes for email and phone only, with one shared KMS HMAC key, normalisation, and partial unique indexes. Joseph Yau, who owns the client-service code, is asked to agree on the implementation's pull request.

## References

- [Client data encryption (envelope encryption)](../security/security-model.md#client-data-encryption-envelope-encryption)
- [Right to erasure](../security/security-model.md#right-to-erasure-nfr-8-gdpr)
- [Open items 11](../architecture/open-items.md#11-audit-payloads-with-client-personal-data), [12](../architecture/open-items.md#12-searching-encrypted-client-fields) and [21](../architecture/open-items.md#21-erasing-soft-deleted-client-data)
- [ADR-0036](ADR-0036-soft-deletion-of-clients-and-accounts.md) (soft deletion)
- AWS documentation: KMS `GenerateDataKey`, encryption context and HMAC keys (`GenerateMac`); DynamoDB conditional writes and point-in-time recovery
