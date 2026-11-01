# CRM frontend

React 19 and Vite frontend for the CRM application. API modules live in
`src/api`, shared components in `src/components`, route pages in `src/pages`,
and frontend-only synthetic fixtures in `src/data`.

This branch (`feat/midterm-demo`) combines two features for presentation
purposes: the event-alerts review workspace (`feat/event-driven-alerts`) and
the business dashboard mockup (`task/business-dashboard/frontend-mockup`).

## Local development

```powershell
cd frontend
npm install
Copy-Item .env.example .env
npm run dev
```

The browser uses same-origin `/api` requests. During local development Vite
proxies each path to its owning service: users/activities on port 8081,
transactions on 8082, accounts on 8083, dashboards on 8085, engagement on
8086, Client Health on 8087, and remaining client-profile paths on 8080. This
avoids separate CORS exceptions.
Set `VITE_API_URL` in `.env` only when the deployed API has a different origin.

## Sign-in

Users sign in through Cognito managed login, with the authorization-code flow and PKCE ([ADR-0025](../docs/adr/ADR-0025-identity-cognito-mfa-pkce-service-tokens.md) decision 2):

1. The **Login** page sends the browser to Cognito, which asks for the password and MFA.
2. Cognito returns it to `/auth/callback`, where `oidc-client-ts` (through `react-oidc-context`) exchanges the code and the PKCE verifier for tokens.
3. The tokens stay in the tab's `sessionStorage`, so a session lasts only as long as the tab.

Every page except Home needs a signed-in user, and admin and agent pages need the matching Cognito group (`cognito:groups`). The services check the token themselves too, so this only decides what the UI shows. API modules add the access token with `authorizationHeader()` from `src/auth/session.js`.

Set these in `.env`. None of them is a secret:

| Variable | Value |
|---|---|
| `VITE_COGNITO_AUTHORITY` | The user pool's issuer, `https://cognito-idp.<region>.amazonaws.com/<pool-id>`: the same value as the services' `COGNITO_ISSUER_URI` |
| `VITE_COGNITO_CLIENT_ID` | The CRM app client, the same one the services check as `COGNITO_APP_CLIENT_ID` |
| `VITE_COGNITO_DOMAIN` | The user pool domain, `https://<prefix>.auth.<region>.amazoncognito.com` |

Without them, the Login page says sign-in is not configured.

### App client settings

Cognito accepts a sign-in without PKCE, so the protection depends on the app client being set up as ADR-0025 says. Someone with admin rights on the shared user pool checks it once:

```sh
aws cognito-idp describe-user-pool-client --user-pool-id <pool-id> --client-id <client-id> \
  --query 'UserPoolClient.{Secret:ClientSecret,Flows:AllowedOAuthFlows,Scopes:AllowedOAuthScopes,Callbacks:CallbackURLs,SignOut:LogoutURLs,Revocation:EnableTokenRevocation}'
```

It should show:

- `Secret`: `null`. A client with a secret cannot be used from a browser. If it has one, create a public client and give its ID to every service's `COGNITO_APP_CLIENT_ID` as well.
- `Flows`: `code` only, without `implicit`.
- `Scopes`: `openid` and `email`.
- `Callbacks`: `http://localhost:5173/auth/callback`, plus `/auth/callback` on each deployed origin.
- `SignOut`: `http://localhost:5173/`, plus each deployed origin.
- `Revocation`: `true`.

Change them in the Cognito console, on the app client's login pages settings. `aws cognito-idp update-user-pool-client` resets every setting you leave out of the command to its default, so use it only with the full current configuration. The settings move to Terraform when infrastructure is built.

### Signing out

Cognito has no sign-out trigger, so a logout reaches the audit log only because the frontend reports it ([ADR-0025](../docs/adr/ADR-0025-identity-cognito-mfa-pkce-service-tokens.md) decision 5). **Sign out** in the sidebar runs `signOut()` from `src/auth/signOut.js`, which:

1. calls user-service's `POST /api/users/me/logout` with the access token, audited as `LOGOUT`;
2. revokes the refresh token at Cognito's `/oauth2/revoke`;
3. drops the tokens and sends the browser to Cognito's `/logout`, which ends the managed login session and returns to `/`. Without this step, the next sign-in in that browser would skip the password and MFA.

Each step runs even if the one before fails or takes more than 5 seconds, so an audit failure never stops a sign-out. A failure is only logged to the browser console, and that logout is then missing from the audit log. Locally, Vite sends `/api/users` to user-service on port 8081; start it with `make -C services/user-service run`.

### Checking PKCE

Open the browser's network tab and click **Sign in**. The `/oauth2/authorize` request carries `code_challenge` and `code_challenge_method=S256`. `src/auth/config.test.js` checks the same on the request the library builds, including that the challenge is the SHA-256 of the verifier.

## Client verification

`/clients/:clientId` is one client's page: the profile, the client's identity documents and the verification ([ADR-0041](../docs/adr/ADR-0041-client-identity-documents-and-manual-verification.md)). It is open to admins and agents; client-service decides which clients each may see, and answers 404 for another agent's client. An agent:

1. creates the client with **Create Client Profile** on Agent Home, which opens the new client's page;
2. uploads the identity document the client gave them, if any: JPEG, PNG or PDF, up to 5 MB. Use synthetic documents only, never a real NRIC;
3. opens it with **View**, compares it with the profile and ticks **Checked**. A document can be ticked only once it has been opened;
4. confirms that they checked the documents and clicks **Verify client**.

Uploading never verifies anyone. Verify sends the checked documents' IDs, and client-service records who verified, when and which documents; the page then shows that, and marks the documents used.

An admin sees the same page read only ([ADR-0059](../docs/adr/ADR-0059-admins-read-only-on-client-data.md)): the profile, the documents (which they can open with **View**), the verification and **View accounts and transactions**, under a note that only the client's agent can change them. There is no Edit, Open account, Upload or Verify control, and client-service and account-service refuse those calls from an admin with 403 anyway.

An admin's one write is moving clients to another agent ([ADR-0060](../docs/adr/ADR-0060-client-reassignment.md)). The client page's **Agent** panel names the client's agent and has **Reassign**, which moves that client and its accounts to another enabled agent. On Admin Home, **Manage Agents** gives every agent, enabled or disabled, **Reassign clients**, which moves all of theirs. Both send `POST /api/clients/reassignments` (`reassignClients` in `src/api/clients.js`) and say how many clients moved; if a service is down, nothing moves and the page says so. The activity feed shows each moved client as "reassigned a client to another agent".

Until the client is verified, **Open account** offers only Pending, and account-service refuses any other status with 403 ([ADR-0050](../docs/adr/ADR-0050-accounts-pending-until-client-verified.md)). After a verification, the page asks an agent whether to make the client's Pending accounts Active. On **Manage accounts and transactions** (`/agent/client/:clientId/transactions`), an agent can change a verified client's account to Active, Inactive, Pending or Closed with `PATCH /api/accounts/{id}`; admins see the status only. The calls are in `src/api/accounts.js`.

Documents are optional, because the requirements Q&A does not expect an upload (CPM06). With no document checked, the agent confirms "I have checked this client's identity without an uploaded document" and clicks **Verify without a document**. That verification is never hidden: the page shows a **Without a document** badge and says the client was verified without an uploaded document, client-service returns `verified_with_documents: false`, and the audit event says so. The calls are in `src/api/clients.js`. Locally, Vite's `/api` rule sends them to client-profile-management-service on port 8080, which `make run` starts with its LocalStack bucket (see its README).

Agent Home's **Manage Client Profiles and Accounts** list comes from `GET /api/clients`; **Edit Profile, Verify and Open Accounts** on a row opens this page, where **Open account** opens a bank account for the client through account-service.

The page uses the existing tokens and components from the Stitch project "Scrooge Global Bank CRM" (design system: Institutional Precision), in `src/styles/index.css`.

## Activity panels

Admin Home's **Recent Activities** and Agent Home's **My Recent Activities** read user-service's activity feed, `GET /api/activities` ([ADR-0021](../docs/adr/ADR-0021-audit-read-path-grpc-service-connect.md)). Both are `src/components/ActivityFeed.jsx`; the call is in `src/api/activities.js`.

- **Who sees what.** The spec says "Agents can only see their own client activities. Admins can see the activities of all agents." The panel never sends who the caller is: user-service and the auditor take it from the access token, so an agent gets only their own changes whatever the page asks for. The agent panel therefore shows no names.
- **Changes only.** Creating, updating, deleting or verifying a client, uploading an identity document, opening or closing an account, and user changes. Views and sign-ins stay in the audit log and are not listed.
- **IDs, not names.** Audit events hold no client personal data ([ADR-0030](../docs/adr/ADR-0030-client-data-envelope-encryption.md)), so an entry shows the first 8 characters of the client, account or user ID (the full ID on hover) and **Open client** where the client still exists. The acting user's name comes from user-service; a user it does not know shows as "Unknown user", and system work as "System". `src/components/activityText.js` turns each event's payload into words.
- **Verification.** A verification shows how many documents it used, or a **Without a document** badge.
- **Filters.** **Period** limits the feed to the last 24 hours, 7 or 30 days; the window is fixed when chosen, so every page covers the same one. An admin can select a name to see only that user's changes, and remove the chip to see everyone's again.
- **Pages.** The panel shows the newest 10; when the filtered feed has more, **View all activities** opens it on its own page (`/admin/activities`, `/agent/activities`) with the same period and user filters, 10 entries a page with first, previous, next and last. The feed has no count, so that page fetches 100 entries at a time with the previous chunk's cursor, and shows "of N" once the last has been reached.
- **Failures.** If the auditor is down, user-service answers `503` and the panel says "Activity is temporarily unavailable." with **Try again**. A failed later page keeps the page already shown.

Locally, Vite sends `/api/activities` to user-service on port 8081, like `/api/users`. The feed also needs the auditor's gRPC server (`AUDITOR_GRPC_TARGET`, see the user-service README). The design is the "Admin Dashboard" screen with the Recent Activities card in the Stitch project "Scrooge Global Bank CRM".

## Synthetic alerts

For an offline event-alert UI review, enable the synthetic fixture adapter:

```powershell
$env:VITE_USE_SYNTHETIC_ALERTS='true'
npm run dev
```

Then open `/agent/alerts` for the decision workflow or `/admin/alerts` for the
read-only oversight view. Synthetic mode uses only local example data and must
remain disabled in deployed environments.

## Client Health Monitor

`/agent/client-health` loads only the authenticated Agent's currently assigned
tasks. `/admin/client-health` provides read-only oversight and an optional
assigned-Agent-subject filter. Both use cursor pagination, status/severity
filters, evidence/history, and persisted Bedrock/template explanations.

Agents may accept OPEN tasks, complete IN_REVIEW tasks, or dismiss OPEN/IN_REVIEW
tasks. Every action sends the displayed `expectedVersion`; a `409` reloads the
latest version. Admins never see mutation controls. The UI distinguishes
expired sessions, denied scope, hidden tasks, conflicts, and assignment-service
outages.

The UI does not fabricate health scores, confidence percentages, client names,
or raw transaction evidence that Contract D does not provide.

## Where each page's data comes from

Every page reads deployed services except alerts when synthetic mode is enabled
(above).

| Page | Data |
|---|---|
| `/agent` | My Clients and the client list (the newest 10) from `GET /api/clients` ([ADR-0043](../docs/adr/ADR-0043-client-and-transaction-list-endpoints.md)); transaction counts for the last 90 days from `GET /api/dashboard/transactions`; Create, Edit and Verify (on the client page) and Delete through client-service; Upcoming meetings (the next 7 days) from `GET /api/engagement/events`, with client names from `GET /api/clients`, hidden when engagement-service is unavailable |
| `/admin` | Admin and agent totals and lists from `GET /api/users`; Create Admin / Agent with `POST /api/users` (Cognito e-mails the temporary password); Disable with `PATCH /api/users/{id}/disable` (users are never deleted, and the root admin cannot be disabled); All Transactions from `GET /api/transactions`. Each list shows its first 10 |
| `/admin/admins`, `/admin/agents`, `/admin/transactions`, `/admin/activities`, `/agent/clients`, `/agent/activities` | The whole of a Home list, 10 rows a page, opened with its **View all** link. Users come alphabetically by last name; the rest newest first. Lists paged by cursor (transactions, activities) are fetched 100 rows at a time (`src/components/paging.js`) |
| `/clients/:id` | client-service: profile, edit, documents, verification. Open account with `POST /api/accounts` on account-service |
| `/agent/client/:id/transactions` | The client's accounts from `GET /api/accounts?clientId=`, then each account's transactions from `GET /api/accounts/{id}/transactions`; each account shows its newest 10 |
| `/agent/client/:id/accounts/:accountId/transactions` | All of one account's transactions, 10 a page, opened with the account's **View all transactions**. The account must be one of the client's |
| `/insights/admin`, `/insights/agent` | dashboard-service (`/api/dashboard/*`, [`docs/api/dashboard-api.yaml`](../docs/api/dashboard-api.yaml)). Agent performance shows the top 10 |
| `/insights/admin/agents` | Every agent's figures from `GET /api/dashboard/agents`, 10 a page, opened with **View all agents**; the link carries the window and sort |
| `/agent/client-health`, `/admin/client-health` | client-health-monitor-service `GET/POST /api/relationship-health/tasks*`; authenticated Agent actions and Admin read-only oversight. An agent's open or in-review task links to a `HEALTH_CHECK_IN` follow-up email ([ADR-0068](../docs/adr/ADR-0068-follow-ups-from-client-health-and-alerts-and-draft-retention.md)) |
| `/agent/calendar` | engagement-service ([`docs/api/engagement-api.yaml`](../docs/api/engagement-api.yaml)): the month's meetings and Singapore public holidays, up to the last month the holiday list covers ([ADR-0057](../docs/adr/ADR-0057-holiday-sync-from-data-gov-sg.md)); create, edit and cancel meetings, each emailed to the agent as a calendar invite ([ADR-0052](../docs/adr/ADR-0052-calendar-invites-by-email.md)); notification preferences. Times are shown and entered in Singapore time |
| `/agent/drafts` | engagement-service: AI or template follow-up drafts ([ADR-0054](../docs/adr/ADR-0054-ai-follow-up-drafts-and-holiday-data.md)), edited as plain text with the names filled in ([ADR-0056](../docs/adr/ADR-0056-drafts-edited-as-plain-text.md)), sent after a confirmation, or discarded. The list pages 10 at a time through the newest 100, the most engagement-service sends. Client names come from `GET /api/clients`. Opened from a Client Health task or an alert (`?purpose=…&clientId=…&healthTaskId=…` or `&alertId=…`), the form is filled in and offers that follow-up, whose text has an `[Add your details here]` slot to replace before sending. Sent and discarded drafts show no text: it is not kept, and the agent's copy is the Bcc ([ADR-0068](../docs/adr/ADR-0068-follow-ups-from-client-health-and-alerts-and-draft-retention.md)) |

Locally, `vite.config.js` sends each `/api` path to the service that owns it, in the same order as the deployed load balancer's rules.

The calendar, follow-up emails and upcoming-meetings layouts follow the Stitch screens "Agent Calendar", "Follow-up Emails" and "Agent Dashboard - Upcoming Meetings" in the project "Scrooge Global Bank CRM" (design system: Institutional Precision). Their styles are in `src/styles/engagement.css`. Stitch's sample data, search bar and status badges are not part of the pages.

## Business dashboards

`/insights/admin` is the bank-wide dashboard: KPI tiles, daily activity, transaction health, portfolio composition, agent performance and stale unverified clients. `/insights/agent` is one agent's own book: KPI tiles, stale unverified clients, team comparison, client verification, transactions and accounts. The window selector (7, 30 or 90 days, ending today in Singapore) reloads every panel.

- Each panel shows its own state. An unavailable panel says why (`reason` in the response), and a request that fails marks only its panels as unavailable.
- A figure the API leaves out, such as a rate with nothing to divide or a transaction value in more than one currency, shows as "—", with the currencies listed. Booked initial deposits are shown per currency, and the agent table ranks them in one currency at a time ([ADR-0062](../docs/adr/ADR-0062-dashboard-account-figures-per-currency.md)).
- The mockup also showed most active clients, ingestion freshness, clients without an account, rejected verifications and flagged transactions. dashboard-service does not serve these yet, so they are not shown ([open item 25](../docs/architecture/open-items.md#25-business-dashboard-panels-not-served-yet)).
- Styling: Stitch project "Scrooge Global Bank CRM" (design system: Institutional Precision) — the same tokens the rest of the app uses. Dashboard-specific styles are in `src/styles/dashboard.css`, chart colours in `src/styles/chartTheme.js`. Charts: Recharts.

## Verification

```powershell
npm test
npm run lint
npm run build
```

The alert tests cover scoped API requests, problem responses, evidence and
exposure rendering, optimistic-version decisions, administrator read-only
behavior, latest-decision presentation, terminal-state action guards, and
empty/error states. The sign-in tests cover the PKCE challenge, page access by
role, the callback page and returning to the page asked for; the sign-out tests
cover the order of the steps and that a failed step never stops the rest. The
client tests cover the client API calls, creating a client, that uploading never
verifies, the 5 MB check and the service's refusals, that a document can be
checked only once opened, that Verify needs a confirmation that matches what was
checked, verifying without a document and how that is shown, and the verified
state. The activity tests cover the feed's query (no caller identity, filters,
cursor), how each kind of event is described, that agents see no names and
admins can narrow the feed to one user, paging, the unavailable and empty
states, and that a slow reply for a replaced filter is dropped.

## Conventions

- Keep backend calls in `src/api`; do not put actor identity in request URLs.
- Send the access token with `authorizationHeader()`; never read, copy or store tokens yourself.
- Use `src/data` only for synthetic frontend fixtures.
- Keep reusable UI in `src/components` and routed screens in `src/pages`.
- Never store access tokens, client data, or other secrets in committed files.
