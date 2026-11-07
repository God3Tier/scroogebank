# Event-driven alerts: AWS deployment test, 2026-10-31

Run by Hayyun's session on `feat/event-driven-alerts` at `f224233` (PR #165 merged), AWS account 727816232662,
`ap-southeast-1`, 2026-10-30 23:30 to about 02:00 Singapore time. Dummy clients and holdings only. Every service,
Cognito pool, database, Tavily call and Bedrock call below was real; nothing was mocked or stubbed.
The two bug fixes and the logging change described below are committed with this report; the relevance-filter loosening is **not** (see
"Changes made during the test"). The whole deployment was torn down afterwards (see the end).

## Result against the five items

| # | Item | Result |
|---|---|---|
| 1 | Independent retrieval benchmark review and publisher-rights verification | **Not done here.** Needs the separate blind-labelled benchmark and a human sign-off. This run adds a small before/after data point for the filter (below). Publisher rights for Reuters, CNBC and FT remain unverified; every story in this test came from CNBC. |
| 2 | Deploy Terraform and services to AWS | **Done.** Bootstrap (14 resources), CRM stack (365), alert-worker stack (12), 7 images pushed, frontend uploaded. Six services healthy. |
| 3 | Real Cognito permissions, holdings access and client reassignment | **Done: 22 of 22 checks passed** on the deployed system, plus the machine-credential checks. |
| 4 | Tavily → Bedrock → scored alerts → cited summaries end to end | **Done after two fixes** (below). 16 real stories, 16 events classified by Bedrock, 15 AI-written cited briefings, 18 alerts, one card per event per agent. |
| 5 | Enable scheduled ingestion after live checks pass | **Tested, not left enabled.** The schedule fired 14 times at `rate(1 minute)`, all `SUCCEEDED`. It was then switched off and reset to `rate(4 hours)`. A real 4-hour firing was not waited for. The deployment was torn down. |

## What was verified, with evidence

**Deployment health.** Six ECS services reached `COMPLETED`. The alert service applied all 10 Flyway migrations on the real
database (V10 current holdings included) and started in `database` mode with ingestion and the model off. The Tavily
and CRM client secrets reach the service from Secrets Manager, not from the environment.

**Exposure.** `/api/alerts`, `/api/alerts/events`, `/api/alert-portfolios/*` and `/api/accounts` return 401 without a token.
`/internal/*` on the public domain returns only the frontend page, so the private account routes are not exposed.

**Machine credentials (real Cognito token endpoint).** The alert-directory client gets a valid access token with only
`crm-accounts/alert-directory`; asking for the transaction-import scope is refused (`invalid_grant`); a wrong secret is
refused (`invalid_client`). The import client still holds only `crm-accounts/lookup`; the client-health client holds only
`crm-transactions/read`.

**Real users (22 of 22).** Three real users signed in with password plus an authenticator code (the pool has MFA on).
Agents created, verified and opened investment accounts for three clients through the real client and account services.
Holdings were recorded through the real API. Agent B could neither read nor write agent A's client's holdings (404). The admin
could read (200) but not write (403). No token and a tampered token were refused (401). The same idempotency key replayed
the first answer; a stale revision was refused (409). The admin then reassigned a client through the real client-service
(which called user-service and account-service): agent A lost access (404) and agent B gained it, and could replace the
holdings (revision 2).

**Search, model and alerts.** The worker ran as an ECS task with real Tavily search. Stories were classified by real
Bedrock (`global.amazon.nova-2-lite-v1:0`), scored against the whole account, and shown per agent. Final state: 16 sources,
16 events, 15 briefings (all `ai_generated`), 18 alerts, 6 portfolio snapshots; audit events for each step were written
(for example 18 `alert.generated`, 15 `alert.briefing-generated`, 10 `alert.portfolio-replaced`, 1 `alert.event-action-recorded`).
Usage: 39,600 model tokens and 67 search queries. Agent A ended with 8 event cards (7 with a written summary) and agent B with 5
(4 with a summary); the card without a summary is the one created before the citation fix. Each agent saw only their own clients.
Inform on one client was recorded (200, applied, nothing sent) and replayed idempotently; the admin could not act (403).
The briefing text contained no client, account or agent identifier.

**Schedule.** 14 scheduled runs in about 15 minutes at `rate(1 minute)`, all `SUCCEEDED`, none refused by the run lock.
Each moved its window forward as designed. They accepted no stories because the windows were minutes long.

## Two bugs found by the real run, and the fixes

Both are committed together with this report; `event-alert-aws-test-2026-10-31-fixes.patch` is a copy of the same change for reference.

1. **The worker task crashed on startup** (`permission denied for table flyway_schema_history`). The API sets
   `SPRING_FLYWAY_ENABLED=false` because the db-migrate step applies migrations; the worker stack did not, so the worker ran
   Flyway with a login that has no rights on the history table. Fix: one line in `stacks/alert-worker/main.tf`.
2. **No AI summary was ever saved.** Real Bedrock wrote a good summary but cited the source by its URL, while
   `SafeBriefingGenerator` accepted only the internal source ID, so both attempts failed with `MISSING_OR_UNKNOWN_CITATION`
   and nothing was recorded about why. Fix: accept the URL of a supplied source and store its ID; a source that was not
   supplied is still rejected. Two unit tests added. Reproduced locally against real Bedrock first (stored article in, rejected
   output captured), then confirmed on AWS.

## Changes made during the test

- **Relevance filter loosened during the test, then held back.** On Hayyun's instruction the filter (`IssuerSearchQuery`) was loosened on the
  deployed test only: the issuer still had to be in the title, but the event word could come from the snippet. Before, only about 1 in 15
  real results passed (for example 1 of about 60 across the first five windows). In the same two windows, late July to 1 Aug admitted 5
  before and 3 additional after, and 1 to 15 Aug admitted 0 before and 3 additional after: 6 more stories and 9 more alerts. The headlines
  read looked on-topic, but the sample is unlabelled and says nothing about precision or recall. **This change is not in this commit.** It
  is kept aside until a controlled comparison (control, loosened filter, multiple searches, both) shows which approach is better.
- **Rejection reasons are now logged** (`SourceResultProcessor`): fixed reason codes only, never article text. No
  "briefing not generated" line appeared after the fixes, so every briefing succeeded.
- **Test-only auth change, approved by Hayyun and reverted:** `ALLOW_ADMIN_USER_PASSWORD_AUTH` was added to the web app client so
  a script could obtain real tokens (the pool otherwise allows only the browser flow, with MFA on). It is not in the patch and was
  destroyed with the deployment.
- Not a bug, withdrawn: a headline apostrophe looked broken only because the Windows console could not print U+2019. The stored
  text is correct.

## Not exercised or still open

- `alert.routing-changed` was never emitted on AWS, because the alerts were created after the reassignment, so there was no earlier
  owner to compare with. It is covered by the integration tests.
- No independent labels, no precision or recall measurement, no publisher-rights decision. The filter change should be judged by the
  blind benchmark, not by this run.
- A real 4-hour scheduled firing was not observed. The 1-minute run shows the scheduler and worker mechanism, not the 4-hour timing.
- The Tavily key was pasted into chat earlier in the session. It was stored only in Secrets Manager and an environment variable. Rotate it.
- The old secret `crm-sg-prod/event-alert-worker/tavily-api-key` (from a 2026-10-23 deployment, already scheduled for deletion) was
  left alone; the current worker stack names its secret with a random suffix, so it did not conflict.
- Generated Go code and local images are git-ignored and were built from the committed schemas. `make` is not installed on this machine,
  so the equivalent Docker commands were run.

## Teardown result (2026-10-31, about 01:45)

Done in the runbook's order: alert-worker (13 resources), then the CRM stack (368), then the Terraform state bucket's stored
versions (76) and `bootstrap` (14). Both stacks' state was empty before bootstrap was destroyed. The empty-account check then found no
buckets, clusters, databases, SES identities, container repositories, CloudFront distributions, user pools, NAT gateways, VPCs, load
balancers, DynamoDB tables or customer keys. Two Secrets Manager entries remain, both already scheduled for deletion and costing
nothing: `crm-sg-prod/event-alert-worker/tavily-api-key` (from 2026-10-23) and `crm-sg-prod-alert-worker-tavily-...` (this test; AWS
removes it after its 7-day window). The safety check would not let the session delete them early, and neither blocks a new deployment.
The domain and its Route 53 zone stay, as they are outside Terraform. The test-only Cognito change was reverted in the working copy.

## Cost

Tavily: 67 search queries in this deployment (a few more from earlier local runs). Bedrock: 39,600 model tokens, about 16 briefings
and classifications. Infrastructure: about USD 0.50 an hour while running, roughly 2.5 hours.

## Suggested next steps for the team

1. Review the two fixes and the logging change in this commit.
2. Decide how to raise the number of usable stories (looser filter, multiple searches, or both) after the controlled comparison and the blind benchmark.
3. Obtain the publisher-rights decision for Reuters, CNBC and FT before enabling scheduled ingestion anywhere shared.
4. Rotate the Tavily key.
5. Re-run this test when the above is merged, including one real 4-hour firing.
