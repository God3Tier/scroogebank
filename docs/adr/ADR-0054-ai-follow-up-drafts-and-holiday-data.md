# ADR-0054: AI follow-up drafts with a placeholder privacy boundary, and a static holiday table

- Status: Accepted
- Decision date/time: 2026-10-21 11:04 +08:00 (Asia/Singapore)
- Decision owner(s): Neo Jia Wen
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/agent-engagement/plan`
- Related issue/PR: TBD
- Supersedes: N/A
- Superseded by: [ADR-0056](ADR-0056-drafts-edited-as-plain-text.md) in part (decision 6: agents edit plain text; drafts are still stored with placeholders); [ADR-0057](ADR-0057-holiday-sync-from-data-gov-sg.md) in part (decision 8: new Singapore years come from data.gov.sg); [ADR-0067](ADR-0067-nova-lite-asia-pacific-for-client-derived-wording.md) in part (decision 3's model: Nova Lite through its Asia-Pacific profile; and AI is on in prod rather than only for demo runs); [ADR-0068](ADR-0068-follow-ups-from-client-health-and-alerts-and-draft-retention.md), proposed, in part (decisions 2, 4 and 6: two follow-up purposes with an `{{agent_details}}` slot, the screen checks it by purpose, and a draft's text is cleared once it is sent or discarded)

## Context

Agents write every follow-up email to clients from scratch. They also miss easy reasons to get in touch, such as a public holiday coming up, a closure, or a good time for a review before year end. The [agent engagement feature](../feature-plans/agent-engagement_plan.md) drafts these emails for the agent to review and send through SES ([ADR-0053](ADR-0053-email-delivery-through-ses.md)).

Constraints:

- **Privacy.** The health monitor already set the precedent that no client identifiers, names or contact details go to a model ([ADR-0034](ADR-0034-xf3-health-monitor-rules-and-bedrock-boundary.md) decision 4). The project uses synthetic data, but the design must hold for real data.
- **Region.** ADR-0034 uses Bedrock in Singapore and rejects cross-region inference, because its banded facts would leave the region for no benefit. The alert service's model is a global profile invoked from us-east-1 on public data only ([ADR-0010](ADR-0010-alert-ai-provider-and-safety.md)). Bedrock's model availability page (checked 2026-10-21) shows that no small, current text model runs in-region in Singapore:
  - Claude 3 Haiku runs in-region but reaches end of life on 2026-11-13, around the demo;
  - the only other in-region text model is Claude Sonnet 5, a much larger model;
  - Amazon Nova Micro, Lite and Pro are offered in Singapore only through a geographic (Asia-Pacific) cross-region inference profile;
  - Claude Haiku 4.5 and 5.5 are offered there only through a global profile.
- **Compliance.** Bank emails to clients must not contain product, rate, investment or performance claims the agent has not made deliberately. The health monitor also lists product recommendations as out of scope.
- **Cost.** Bedrock spend counts toward the USD 300 limit.
- **Availability.** Bedrock may be disabled, slow or out of quota during the demo.
- **Holidays.** Which public holidays count depends on the client's country. Only Singapore is deployed ([ADR-0026](ADR-0026-one-stack-per-legal-entity.md)), and the client `country` field is free text.

## Decision Drivers

- No client personal data reaches the model.
- Nothing reaches a client without a person approving it.
- The output is safe by construction, and a fallback always works.
- Spend has a fixed cap and a kill switch, and the model is small and cheap.
- The model is still supported at the demo.
- Holiday facts are predictable and testable.
- The use of AI must be justified and explainable (`project_plan.md` §14).

## Options Considered

### How drafts are produced

- **Bedrock writes from placeholders and public facts only; the service fills in the placeholders (chosen).**
  - Pros: the model never sees who the client is. Its output can be checked mechanically, and the wording still varies with purpose, tone and holiday.
  - Cons: drafts are generic until the agent personalises them.
- **Templates only, no AI.**
  - Pros: fully predictable and free.
  - Cons: repetitive wording, no adaptation to the holiday or recency, and it no longer gives a reason to use AI. Kept as the fallback.
- **Bedrock with full client context (name, history, notes).**
  - Pros: more personal drafts.
  - Cons: personal data goes to the model, against ADR-0034's precedent.
- **The agent writes a free-text prompt.**
  - Pros: flexible.
  - Cons: agents would type names and circumstances into the model, and the input could not be screened reliably.

### Model and region

A cross-region inference profile is an ID passed in place of a model ID. The service still calls Bedrock Runtime in Singapore, and Bedrock runs the request in one of the profile's regions and returns the answer through Singapore. A geographic profile stays within one area, such as Asia-Pacific; a global profile may use any commercial region.

- **Amazon Nova Micro through its Asia-Pacific geographic profile (chosen).**
  - Pros: Amazon's lowest-priced text model on Bedrock, current, and enough for a short email. Requests stay in Asia-Pacific. AWS charges the source region's on-demand price, with no extra routing charge.
  - Cons: prompts and output can be processed outside Singapore, in Asia-Pacific regions that AWS chooses.
- **Claude Sonnet 5 in-region.**
  - Pros: nothing leaves Singapore, and the writing is strong.
  - Cons: a large model at a higher price per token than a 150-word email needs.
- **Claude 3 Haiku in-region.**
  - Cons: end of life on 2026-11-13, around the demo.
- **A global profile, for example Claude Haiku 4.5 or 5.5.**
  - Pros: more model choice.
  - Cons: requests may be processed in any commercial region worldwide, for no benefit over Asia-Pacific.

### Holiday data

- **A versioned table seeded by migrations from official published lists (chosen).**
  - Pros: predictable, testable, and no internet access needed at run time.
  - Cons: must be updated by hand each year.
- **A public holiday API at run time.**
  - Pros: always current.
  - Cons: a third-party dependency, its availability and licence, and calls through the NAT gateway for data that changes once a year.
- **A government open-data download at deploy time.**
  - Pros: official source.
  - Cons: adds a build-time dependency and a parser for a format that can change.

## Decision

1. **Draft request.** The agent calls `POST /api/engagement/drafts` with:
   - the client;
   - a purpose: `CHECK_IN`, `MEETING_FOLLOW_UP`, `HOLIDAY_GREETING` or `PRE_HOLIDAY_REVIEW`;
   - a tone: `FORMAL` or `WARM`;
   - optionally one of their own events.

   The service checks ownership through client-service with the agent's token ([ADR-0053](ADR-0053-email-delivery-through-ses.md) decision 5). That one read also gives it the client's first name and country.
2. **Model input.** Only the following go to the model:
   - the purpose and tone;
   - the language (`en-SG`);
   - up to 3 public holidays in the next 30 days for the client's country (name and date);
   - a banded time since the agent's last meeting with this client, taken from the agent's own events: `NONE`, `UNDER_1_MONTH`, `1_TO_3_MONTHS` or `OVER_3_MONTHS`;
   - the allowed placeholders: `{{client_first_name}}` and `{{agent_name}}`.

   **Never sent:** names, email addresses, phone numbers, client or account IDs, addresses, dates of birth, agent-typed titles, descriptions or notes, transactions, balances, and anything from the health monitor or alerts.
3. **Model and region.**
   - **Amazon Nova Micro**, through its **Asia-Pacific geographic cross-region inference profile**. The profile ID is `apac.amazon.nova-micro-v1:0`, confirmed by the decision owner on 2026-10-21.
   - The service always calls the Bedrock Runtime endpoint in ap-southeast-1 with the `Converse` API. Bedrock may run the request in another region of the profile, but never outside Asia-Pacific.
   - Global inference profiles are not allowed.
   - If Nova Micro passes fewer than 90% of the evaluation cases (Validation), Nova Lite through its Asia-Pacific profile replaces it without a new ADR. Any other model needs a new decision.
   - The ID comes from `BEDROCK_MODEL_ID`.
   - Settings: temperature 0.3, at most 600 output tokens, and a 15-second timeout.
   - The system prompt is versioned in code, and each draft stores its `prompt_version` and `model_id`.
4. **Safety screen.** The model must return JSON `{"subject": "...", "body": "..."}`. The draft is rejected unless:
   - the subject is 1–120 characters and the body 1–1,500;
   - the body contains `{{client_first_name}}`;
   - no placeholder other than the two allowed appears;
   - there are no URLs, email addresses or phone numbers;
   - there are no figures next to `%` or a currency symbol or code;
   - no term from the versioned block list appears: for example rate, interest, return, yield, invest, guarantee, promotion, offer, loan, credit, fund, stock or insurance;
   - accounts, balances and transactions are not mentioned.

   The screen extends event-alert-service's `AiSafetyScreen` and `SafeBriefingGenerator` pattern.
5. **Template fallback.**
   - There is one deterministic template per purpose and tone, with a holiday slot.
   - The template is used when AI is disabled, a cap is reached, the model times out or errors, or the screen rejects the output.
   - The draft records `source` (`AI` or `TEMPLATE`) and a reason code. The request still returns 200.
6. **A person approves every email.**
   - Drafts are stored with placeholders. The agent edits them, sees a preview with the placeholders filled in, and sends with `POST /drafts/{id}/send`. (Editing superseded by [ADR-0056](ADR-0056-drafts-edited-as-plain-text.md): the agent edits the text with the names filled in, and the service marks them as placeholders again when saving.)
   - Nothing is ever sent automatically or in bulk.
   - The UI labels AI drafts as AI-generated, to be reviewed before sending, and shows which holiday was used.
   - At send time, the only checks are for unresolved placeholders and an empty body. What the agent writes is the agent's responsibility.
7. **Controls.**
   - `ENGAGEMENT_AI_ENABLED` defaults to `false`. It is the kill switch.
   - Each agent has a daily draft cap (`ENGAGEMENT_AI_DAILY_DRAFTS_PER_AGENT`, default 20).
   - There is a monthly token quota (`ENGAGEMENT_AI_MONTHLY_TOKEN_QUOTA`, default 300,000), tracked in `ai_usage`.
   - Reaching a cap gives templates, never an error.
   - The existing Bedrock budget alert covers this spend ([ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md)).
8. **Holiday data.**
   - `holidays` rows hold the ISO 3166-1 alpha-2 country code, date, name, whether it is an observed (in-lieu) day, the source URL and a `dataset_version`.
   - They are seeded by Flyway migrations from official published lists, starting with Singapore 2026 and 2027 from the Ministry of Manpower. The owner checks each row against the source.
   - Corrections and new years come in new migrations, never edits to old ones. (New Singapore years superseded by [ADR-0057](ADR-0057-holiday-sync-from-data-gov-sg.md): a daily sync adds them from MOM's data.gov.sg dataset; corrections still come in migrations.)
   - The client's free-text `country` is mapped to a code through a fixed table (for example `Singapore` and `SG` map to `SG`). A country that cannot be mapped gets no holidays, and holiday purposes then return 422.
9. **Audit.** `DRAFT_GENERATE` records the source, reason code, `prompt_version`, `model_id` and holiday ID, and never any text. `EMAIL_SEND` records the draft and client IDs and the SES message ID ([ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md)).

## Rationale

Placeholders give the model everything it needs to write a good email (purpose, tone, timing and the holiday) and nothing that identifies the client. That makes the privacy boundary easy to state, test and defend, and it is the same boundary the health monitor uses. A strict screen, a template that always works and mandatory human review mean a bad model output costs nothing more than a template draft. A static holiday table is the simplest predictable source for data that changes once a year.

**Why the Asia-Pacific profile is acceptable:** the model input carries no personal data (decision 2). Processing in another Asia-Pacific region therefore exposes only the purpose, tone, public holidays and placeholders. Unlike the health monitor's case in ADR-0034, there is a benefit: the only in-region alternatives are a model at end of life or a much larger one.

**Why AI is justified here:** the value is in turning a few structured facts into natural, varied and tone-appropriate wording quickly, which templates do poorly. The value is not in deciding what to say about a client.

## Consequences

### Positive

- The model never processes client personal data, which a test can prove.
- Drafting works even with Bedrock off, so the demo cannot be blocked by the model.
- Every AI draft's model, prompt version and holiday are recorded, which explains where it came from.

### Negative / Trade-offs

- Drafts are generic and need the agent to personalise them.
- The block list may reject some harmless drafts, which then fall back to templates. The list will need tuning.
- Prompts and output may be processed outside Singapore, in Asia-Pacific regions that AWS chooses and may change. Today these are Sydney, Tokyo, Seoul, Osaka and Mumbai, as well as Singapore itself. This differs from the health monitor's in-region rule (ADR-0034) and must be explained as such.
- A small model writes plainer drafts than a large one.
- The holiday data needs a yearly update, and countries other than Singapore have no data until they are added.
- Agents can still type personal or advisory content into a draft themselves. Approval is a human control, not a technical one.

## Security & Privacy Impact

- No personal data, identifiers or agent-typed text goes to Bedrock (decision 2). A test asserts this on the captured request.
- Requests enter Bedrock in ap-southeast-1 and may be processed in other Asia-Pacific regions of the profile, never through a global profile. Only the fields in decision 2 travel.
- Drafts in `engagement-db` hold placeholders plus whatever the agent typed. Resolved text and addresses are not stored after sending.
- The safety screen blocks links and contact details, which limits the risk of a manipulated output being sent unnoticed. The model receives no untrusted text, so it has no route for prompt injection.
- The task role may invoke only the configured profile. It needs `bedrock:InvokeModel` on both of these, and fails with `AccessDeniedException` if either is missing:
  - the profile, `arn:aws:bedrock:ap-southeast-1:<account>:inference-profile/apac.amazon.nova-micro-v1:0`;
  - the model in each destination region, `arn:aws:bedrock:<region>::foundation-model/amazon.nova-micro-v1:0`, for ap-southeast-1, ap-southeast-2, ap-northeast-1, ap-northeast-2, ap-northeast-3 and ap-south-1. The policy lists these regions rather than using `*`, so a region AWS adds later is refused until reviewed.

## Cost Impact

Nova Micro is Amazon's lowest-priced text model on Bedrock. AWS charges cross-region inference at the source region's on-demand price, with no extra routing charge. Check both on the Bedrock pricing page, which could not be read when this was written.

- A draft uses about 700 input tokens and at most 600 output tokens.
- At demo volume (under 200 drafts) this is cents, at most around USD 1.
- The monthly quota (decision 7) caps the worst case. The kill switch stays off outside demo runs.

## Operational Impact

- **Configuration:** `ENGAGEMENT_AI_ENABLED`, `BEDROCK_REGION`, `BEDROCK_MODEL_ID` and the two caps.
- **Model access:** before the demo, confirm the account can use Nova Micro through the profile. `aws bedrock list-inference-profiles --region ap-southeast-1` and `get-inference-profile` show the profile ID and its destination regions.
- **Metrics:**
  - drafts by source and reason;
  - screen rejections by rule;
  - Bedrock latency and tokens.
- **Yearly task:** add next year's holidays in a new migration, checked against the official list.

## Validation

- Unit tests:
  - each screen rule, both passing and failing;
  - placeholder filling;
  - a template for every purpose and tone;
  - caps and the kill switch;
  - mapping countries to codes;
  - choosing holidays in the next 30 days, including observed days.
- A privacy test: the captured Bedrock request contains no client name, email, ID or agent-typed text for a seeded client with distinctive values.
- An evaluation run before enabling AI for the demo:
  - fixtures covering every purpose and tone, with and without a holiday (at least 24 cases), run against the chosen model;
  - at least 90% must pass the screen, and the results are recorded following `event-alert-service` `ai/evaluation`.
- The profile's destination regions are recorded in the revision history (2026-10-21). AWS can change them, so re-run `aws bedrock get-inference-profile --region ap-southeast-1 --inference-profile-identifier apac.amazon.nova-micro-v1:0` before the demo. Update the record and the IAM policy if the list has changed.

## References

- Amazon Bedrock User Guide: the Converse API; cross-region inference and inference profiles; model availability by Region (checked 2026-10-21)
- [Agent engagement feature plan](../feature-plans/agent-engagement_plan.md)
- Ministry of Manpower, Singapore: public holidays (official list for 2026 and 2027)
- [ADR-0010](ADR-0010-alert-ai-provider-and-safety.md), [ADR-0014](ADR-0014-alert-observability-cost-and-kill-switches.md), [ADR-0020](ADR-0020-audit-write-path-outbox-shared-sqs.md), [ADR-0026](ADR-0026-one-stack-per-legal-entity.md), [ADR-0029](ADR-0029-environment-lifecycle-and-cost-controls.md), [ADR-0034](ADR-0034-xf3-health-monitor-rules-and-bedrock-boundary.md), [ADR-0051](ADR-0051-engagement-service-and-mail-dispatcher.md), [ADR-0053](ADR-0053-email-delivery-through-ses.md)

## Revision history

- 2026-10-21 11:04 +08:00: proposed.
- 2026-10-21: accepted by the decision owner. Before acceptance, decision 3 and Validation were changed so that the model ID is recorded here once chosen, rather than being a condition of acceptance; the model must still run on demand in ap-southeast-1.
- 2026-10-21: amended before first commit, as decided by the decision owner. No small, current text model runs in-region in Singapore, so decision 3 now names Amazon Nova Micro through its Asia-Pacific geographic profile, with Nova Lite as the only allowed substitute and global profiles still forbidden. Context, Options, Rationale, Consequences, Security, Cost, Operational Impact and Validation changed to match.
- 2026-10-21: the decision owner confirmed the profile ID `apac.amazon.nova-micro-v1:0` with `aws bedrock get-inference-profile` in the project account. It is a system-defined, active profile named "APAC Nova Micro", last updated by AWS on 2025-07-08. It routes to Nova Micro in six regions: ap-southeast-1 (Singapore), ap-southeast-2 (Sydney), ap-northeast-1 (Tokyo), ap-northeast-2 (Seoul), ap-northeast-3 (Osaka) and ap-south-1 (Mumbai).
- 2026-10-21: decision 6 superseded in part by [ADR-0056](ADR-0056-drafts-edited-as-plain-text.md): agents edit plain text with the names filled in, and drafts are still stored with placeholders. This record is otherwise unchanged.
- 2026-10-21: decision 8 superseded in part by [ADR-0057](ADR-0057-holiday-sync-from-data-gov-sg.md): new Singapore years are added by a sync from MOM's data.gov.sg dataset; the migrations stay as the baseline. This record is otherwise unchanged.
- 2026-11-01: decision 3's model and the "kill switch stays off outside demo runs" guidance superseded in part by [ADR-0067](ADR-0067-nova-lite-asia-pacific-for-client-derived-wording.md), as decided by the decision owner: drafts use Nova Lite through `apac.amazon.nova-lite-v1:0` (same six regions), and AI is on in prod. This record is otherwise unchanged.
