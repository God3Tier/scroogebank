# ADR-0041: Client identity documents: uploaded through client-service, encrypted with the client's key, as evidence for a manual verification

- Status: Accepted
- Decision date/time: 2026-10-13 20:25 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/governance/adr-0041-client-documents` (accepted); implementation on `task/base-features/client-documents`
- Related issue/PR: TBD
- Supersedes: N/A
- Superseded by: N/A

## Context

The spec's "Verify Client Identity" (`POST /api/clients/{clientId}/verify`) has four steps: the system sends the client a verification request, the client provides identification documents (NRIC), the system validates the documents against the client's details, and the verification status is updated. The requirements Q&A narrows this:
- verification is manual: the agent checks the documents, and the system lets the agent mark the client verified (CPM04, CPM07, CPM23); automation such as SingPass or OCR is not expected (CPM04, CPM10);
- uploading identification documents "is not expected in the project. In the industry this would be expected" (CPM06);
- verification happens at onboarding (CPM13), and an unverified client is treated as inactive but stays visible to the agent (CPM24).

The target architecture already plans an upload: a private, KMS-encrypted S3 bucket for identity documents (synthetic only), uploaded with presigned URLs, where "the stored copy is the audit record of what the agent checked" ([system-overview](../architecture/system-overview.md)). [Open item 19](../architecture/open-items.md#19-client-verification-request-feature-2) asks for an ADR confirming that the agent collects and uploads the document and that no request is sent to the client.

On `dev`, verify sets `verification_status` to `verified` and takes no body. Nothing records what the agent checked, or who verified the client and when, except the audit event for the call. There is no document storage, and the frontend has no client page that calls client-service.

Identity documents are among the most sensitive personal data the system would hold. Client-service already encrypts each client's personal fields with that client's own data key, so that deleting the key erases the client, backups included ([ADR-0030](ADR-0030-client-data-envelope-encryption.md)).

## Decision Drivers

- A record of what the agent checked, and who verified the client and when
- The agent decides: uploading a document must not verify anyone
- Protection of identity documents at rest, erasure (NFR 8) and least privilege
- Every access audited ([ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md), [ADR-0022](ADR-0022-audit-tamper-resistance-and-crypto-shredding.md))
- Effort and cost

## Options Considered

### How documents are uploaded and read

#### Option A: presigned S3 URLs (the target architecture's plan)

client-service issues short-lived upload and download URLs, and the browser talks to S3 directly.
- Pros: files never pass through the WAF, API Gateway or the service; a common pattern.
- Cons:
  - the browser reaches the bucket over the internet, so it needs CORS and cannot be limited to the VPC endpoint;
  - S3 stores whatever is sent, so a confirmation step must check the size and type afterwards;
  - a download is audited when its URL is issued, not when it is used, and the URL works for anyone who has it until it expires;
  - the objects are encrypted only with the bucket's key, so deleting a client's data key does not erase them.

#### Option B: through client-service, encrypted with the client's data key (chosen)

The browser sends the file to client-service, which checks it, encrypts it and stores it; reads go through client-service too.
- Pros:
  - the type and size are checked before anything is stored;
  - deleting the client's data key erases their documents too ([ADR-0030](ADR-0030-client-data-envelope-encryption.md) decision 5);
  - only client-service's role can reach the bucket, through the S3 gateway endpoint;
  - every upload and every view is a request with a verified token, the ownership check and an audit event.
- Cons:
  - files pass through the WAF, API Gateway (whose HTTP API limit is 10 MB) and the service;
  - the WAF's Core rule set blocks request bodies over 8 KB (`SizeRestrictions_BODY`), so the upload route needs an exception (decision 9);
  - the service holds up to 5 MB in memory per upload.

#### Option C: no upload

Verify records only the agent's decision, as CPM06 and CPM23 allow.
- Pros: nothing to build.
- Cons: nothing shows what the agent checked; the target architecture and the spec's second step expect the documents.

### What Verify requires

- **Option D: documents optional.** A client could be verified with nothing behind it.
- **Option E: at least one of the client's documents, recorded (chosen).**

### The spec's request to the client

- **Option F: an SES e-mail asking the client for documents.** Clients never use the CRM, so it would need a client-facing upload, SES out of sandbox, and more personal data in transit.
- **Option G: the agent collects the document from the client and uploads it (chosen).**

## Decision

1. **Access.** An agent can upload, list, view and verify for their own clients; an admin for every client. This is the ownership rule every client route already uses (`RequireClientOwner`). Missing, deleted and other agents' clients return 404.
2. **Upload.** `POST /api/clients/{clientId}/documents`, `multipart/form-data` with one field, `file`.
   - At most 5 MB (5,242,880 bytes); an empty file is refused (400), a larger one gets 413.
   - JPEG, PNG or PDF, detected from the file's first bytes, never from its name or the type the browser states; anything else gets 415.
   - The original file name is not kept, because it may contain the client's name.
   - Uploading never changes the client's verification status.
3. **Storage.**
   - The file is encrypted with AES-256-GCM using the client's data key. Its additional authenticated data is the client ID and the document ID, so a stored file cannot be moved to another client or document without failing to decrypt.
   - The ciphertext is stored as the object `clients/{clientId}/{documentId}` in a private bucket, which also encrypts it with its own SSE-KMS key. The object key holds no personal data.
   - A row in clients-db's new `client_documents` table records the document ID, client ID, detected type, size, the uploading user's Cognito `sub` and the time.
   - The object is stored before the row is inserted. If the insert fails, the object is left unreferenced, still encrypted with the client's key, and the failure is logged.
   - The API never changes or deletes a document, because it is evidence.
4. **Reading.** `GET /api/clients/{clientId}/documents` lists the client's documents, newest first, without their content. `GET /api/clients/{clientId}/documents/{documentId}` returns one decrypted file with its stored type, `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`. Each list and each view is audited.
5. **Verify.** `POST /api/clients/{clientId}/verify` takes `{"document_ids": ["..."]}`: the documents the agent checked against the client's details.
   - At least one is required, and each must be one of this client's documents; otherwise 400.
   - A client that is already verified gets 409.
   - In one transaction, client-service sets `verification_status` to `VERIFIED`, records `verified_by_agent_id` (the caller's Cognito `sub`) and `verified_at`, and marks those documents as the verification's evidence.
   - The status is now stored in upper case, like `UNVERIFIED`. Clients verified before this change hold `verified`, so readers compare the status case-insensitively, as the dashboard figures already do ([ADR-0038](ADR-0038-dashboard-metric-definitions.md)).
6. **Audit.** The existing audit middleware records an upload as `CREATE`, a list or view as `GET`, and Verify as `UPDATE`, as for every client route. Their payload gains `document_ids`: the new document's ID, the viewed document's ID, or the IDs Verify was given. These are IDs only, never content or file names, so the hash chain records which documents a verification was based on. The auditor needs no change.
7. **Erasure.** Deleting a client's data key makes their documents unreadable, including copies in backups. When erasure runs ([open item 21](../architecture/open-items.md#21-erasing-soft-deleted-client-data)), it should also delete the objects under `clients/{clientId}/` and the `client_documents` rows. Deleting a client is still a soft delete that keeps both ([ADR-0036](ADR-0036-soft-deletion-of-clients-and-accounts.md)).
8. **No request is sent to the client.** The agent collects the document from the client and uploads it. SES stays deferred until verification is automated. This resolves open item 19.
9. **Infrastructure requirements** (for when the infrastructure is built):
   - a private bucket with Block Public Access, default encryption with its own customer-managed KMS key and S3 Bucket Keys, and a bucket policy that refuses every principal except client-service's task role and every request not made through the S3 gateway endpoint;
   - client-service's task role, and only it: `s3:PutObject` and `s3:GetObject` on `clients/*` in that bucket, and `kms:GenerateDataKey` and `kms:Decrypt` on the bucket's key;
   - a WAF exception for `POST /api/clients/*/documents` only: set the Core rule set's `SizeRestrictions_BODY` to count, and add a rule after it that blocks requests carrying that rule's label on every other path;
   - alarms on S3 errors from client-service ([ADR-0027](ADR-0027-observability-baseline.md)).

## Rationale

- Option B is the only upload option where the documents get the same protection as the client's other personal data: encrypted with the client's own key, erased with it, readable only by client-service, and audited on every view.
- Its costs are bounded: identity documents are small, the WAF exception is limited to one route, and the API Gateway limit is twice the file limit.
- Requiring and recording the documents (Option E) makes the verification a decision with evidence behind it, while the decision itself stays the agent's, as the Q&A requires.
- Option G matches how the Q&A describes verification (the agent deals with the client) and avoids a client-facing channel the CRM does not have.

## Consequences

### Positive

- Each verification records who verified the client, when, and which documents they checked, in clients-db and in the audit chain.
- Identity documents are encrypted twice at rest and erased with the client's data key.
- Uploading and verifying stay separate steps.

### Negative / Trade-offs

- The WAF's body-size rule no longer applies to the upload route; client-service's own 5 MB limit takes its place.
- Uploads pass through API Gateway and the service, so a much larger file limit would need a different design.
- `POST /api/clients/{clientId}/verify` now needs a body; a call without one gets 400.
- A document uploaded by mistake (for example another client's) cannot be removed through the API, and editing a verified client's details does not reset the verification. Both are [open item 22](../architecture/open-items.md#22-identity-documents-wrong-uploads-and-re-verification).

## Security & Privacy Impact

- Only synthetic identity documents are used in this project; never upload a real NRIC.
- Detecting the type from the content keeps HTML, SVG and scripts out of storage, and `nosniff` stops a browser reinterpreting what is served.
- Two layers of encryption at rest: the client's data key in client-service, and the bucket's KMS key in S3.
- Least privilege: only client-service's role, through the VPC endpoint, can read or write the bucket.
- Audit events and object keys carry IDs only.

## Cost Impact

Negligible: a few megabytes of S3 storage and requests, and one more KMS key (about USD 1/month) whose requests S3 Bucket Keys reduce.

## Operational Impact

- The bucket, its key, its policy and the WAF exception go in Terraform ([ADR-0028](ADR-0028-cicd-migrations-terraform.md)).
- client-service needs the bucket name (`CLIENT_DOCUMENTS_BUCKET`); locally, LocalStack provides S3 next to the existing KMS and DynamoDB.

## Validation

- A JPEG, PNG or PDF of up to 5 MB is accepted; an HTML or SVG file renamed `.png` gets 415, and a 6 MB file gets 413.
- The object in S3 is not readable as the uploaded file, and decrypting it as another client or document fails.
- After an upload the client is still unverified. Verify with no documents or with another client's document gets 400; a second Verify gets 409.
- After Verify, the client is `VERIFIED` with `verified_by_agent_id` and `verified_at`, the checked documents are marked as evidence, and the audit chain has the `UPDATE` with their IDs.
- Another agent gets 404 for the client's documents and for Verify.
- A role other than client-service's cannot read the bucket.

## Revision history

- 2026-10-13 20:25 +08:00: proposed and accepted by the decision owner. Joseph Yau, who owns the client-service code, is asked to agree on the implementation's pull request.

## References

- CS301 project specification v18.0, Feature 2 "Verify Client Identity"; requirements Q&A CPM04, CPM06, CPM07, CPM10, CPM13, CPM23 and CPM24
- [ADR-0030](ADR-0030-client-data-envelope-encryption.md) (client data encryption), [ADR-0036](ADR-0036-soft-deletion-of-clients-and-accounts.md) (soft deletion), [ADR-0019](ADR-0019-edge-entry-chain.md) (edge chain and WAF), [ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md) and [ADR-0022](ADR-0022-audit-tamper-resistance-and-crypto-shredding.md) (audit)
- [Open items 19, 21 and 22](../architecture/open-items.md)
- AWS documentation: AWS WAF Core rule set (`SizeRestrictions_BODY`) and label match rules; API Gateway HTTP API quotas; S3 Block Public Access, default encryption with SSE-KMS and S3 Bucket Keys, and bucket policies for gateway endpoints
