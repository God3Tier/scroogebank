# ADR-0056: Agents edit drafts as plain text; names are stored as placeholders

- Status: Accepted
- Decision date/time: 2026-10-21 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `feat/agent-engagement`
- Related issue/PR: TBD
- Supersedes: [ADR-0054](ADR-0054-ai-follow-up-drafts-and-holiday-data.md) decision 6 in part (how the agent edits a draft)
- Superseded by: N/A

## Context

[ADR-0054](ADR-0054-ai-follow-up-drafts-and-holiday-data.md) decision 2 has the model write from two placeholders, `{{client_first_name}}` and `{{agent_name}}`, so it never sees who the client is. Decision 6 stores drafts with those placeholders: the agent edits the placeholder text and sees a separate preview with the names filled in.

In the first deployment, the decision owner found this hard to use. Agents edited text full of `{{...}}` syntax, had to type placeholders to mention a name, and read the email twice, once in the editor and once in the preview.

Two things must still hold:

- **No names in `engagement-db`.** ADR-0054 Security says drafts hold placeholders plus what the agent typed. The client's first name is one of the fields client-service encrypts and erases by deleting the client's key ([ADR-0030](ADR-0030-client-data-envelope-encryption.md)). A copy in another database would survive that erasure.
- **The model still sees no names** (ADR-0054 decision 2).

## Decision Drivers

- Agents never see or type placeholder syntax.
- Stored drafts hold no client or agent names.
- No change to what the model sees, or to sending.

## Options Considered

- **Fill on read, mark on save (chosen).** The service fills in the names when it returns a draft. When the agent saves, it turns each whole-word, exact occurrence of the client's first name and the agent's name back into its placeholder, then stores the result.
  - Pros: agents work in plain text; storage is unchanged; erasure still holds.
  - Cons: saving needs client-service and Cognito; a first name that is also an everyday word is marked wherever it appears (the sent text reads the same); if a name changes between saving and sending, every marked occurrence changes with it.
- **Fill once when the draft is created, and store the filled text.**
  - Pros: simplest; editing needs no other service.
  - Cons: names stored in `engagement-db` and not erased with the client; supersedes ADR-0054's storage rule.
- **Keep placeholders, but show them as chips in a rich-text editor.**
  - Pros: no name handling on the server.
  - Cons: a new editor dependency; agents still see placeholders, only styled.

## Decision

1. **Reading.** `GET /api/engagement/drafts/{draftId}`, create, save and send return `subject` and `body` with the names filled in. They are null when client-service or Cognito cannot supply the names, and in lists. `subjectTemplate` and `bodyTemplate` remain in the response as the stored form.
2. **Saving.** `PUT /api/engagement/drafts/{draftId}` takes `subject` and `body` as plain text.
   - The service looks up the client's first name (client-service, with the agent's token) and the agent's name (Cognito).
   - It replaces each whole-word, exact occurrence with its placeholder, the longer name first, and stores the result.
   - Without either name it saves nothing and returns 503. A client no longer the agent's returns 404.
   - Text that becomes longer than the stored columns (120 and 5,000 characters) returns 422.
   - The lookups run before the write's transaction, which checks the draft's status and version again.
3. **Unchanged.** The model's input (ADR-0054 decision 2), the safety screen, the send path, its checks, and the rule that nothing is sent without the agent pressing Send.
4. **Frontend.** The editor shows `subject` and `body`. The placeholder hint and the separate preview are removed; the To and Reply-To note stays. Without the names, the editor says so, and only Discard is offered.

## Rationale

Filling on read and marking on save gives agents plain text without moving any name into storage, so ADR-0054's privacy boundary and ADR-0030's erasure are untouched. The replacement is a round trip for the values in use: filling a marked text gives back exactly what the agent saved. The cost is one client-service call and one cached Cognito lookup per save, which the preview already made.

## Consequences

### Positive

- Agents never deal with placeholder syntax.
- `engagement-db` holds no names, as before.

### Negative / Trade-offs

- Saving fails with 503 while client-service or Cognito is unavailable.
- A client's first name that is also a word in the text is stored as a placeholder there too. The sent text is unchanged, unless the client's name changes before sending.
- An agent who types `{{client_first_name}}` by hand still gets it filled in. Other double-brace text still blocks sending (ADR-0054 decision 6).

## Security & Privacy Impact

- No new data is stored. The names are held in memory for one request.
- Lookups use the agent's own token for client-service, as the preview and send already did.

## Cost Impact

None.

## Operational Impact

None beyond the 503 above. The contract `docs/api/engagement-api.yaml` changes: `DraftUpdate` takes `subject` and `body`, and `Draft` replaces `preview` with `subject` and `body`. The frontend and the service change together.

## Validation

- `PlaceholdersTest`: marking is whole-word and literal, the longer name goes first, and filling the marked text gives the original.
- `DraftApiTest`: a saved plain-text draft is stored with placeholders only; without either name nothing is saved; lists carry no filled text.
- `AgentDrafts.test.jsx`: the editor shows the filled text, saves plain text, shows no `{{`, and offers only Discard without the names.

## References

- [ADR-0030](ADR-0030-client-data-envelope-encryption.md), [ADR-0054](ADR-0054-ai-follow-up-drafts-and-holiday-data.md)
- [Agent engagement API](../api/engagement-api.yaml)

## Revision history

- 2026-10-21: proposed and accepted; the decision owner chose this option over storing filled text.
