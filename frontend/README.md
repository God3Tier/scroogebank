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
proxies them to `http://localhost:8080`, avoiding a separate CORS exception.
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

### Checking PKCE

Open the browser's network tab and click **Sign in**. The `/oauth2/authorize` request carries `code_challenge` and `code_challenge_method=S256`. `src/auth/config.test.js` checks the same on the request the library builds, including that the challenge is the SHA-256 of the verifier.

## Synthetic alerts

For an offline event-alert UI review, enable the synthetic fixture adapter:

```powershell
$env:VITE_USE_SYNTHETIC_ALERTS='true'
npm run dev
```

Then open `/agent/alerts` for the decision workflow or `/admin/alerts` for the
read-only oversight view. Synthetic mode uses only local example data and must
remain disabled in deployed environments.

## Business dashboard mockup (demo only)

Mocks the business dashboard proposed on `feat/business-dashboard`.

- `/insights/admin`: bank-wide dashboard with KPI tiles, daily activity,
  transaction health, ingestion freshness, portfolio composition, agent
  performance and action items.
- `/insights/agent`: one agent's own book with KPI tiles, action queue, team
  comparison, portfolio and client transactions.
- Data: synthetic, from `src/data/dashboard.js`. The window selector (7, 30,
  90 days) changes the figures.
- Styling: Stitch project "Scrooge Global Bank CRM" (design system:
  Institutional Precision) — the same tokens the rest of the app uses.
  Dashboard-specific styles are in `src/styles/dashboard.css`, chart colours
  in `src/styles/chartTheme.js`.
- Charts: Recharts.

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
role, the callback page and returning to the page asked for.

## Conventions

- Keep backend calls in `src/api`; do not put actor identity in request URLs.
- Send the access token with `authorizationHeader()`; never read, copy or store tokens yourself.
- Use `src/data` only for synthetic frontend fixtures.
- Keep reusable UI in `src/components` and routed screens in `src/pages`.
- Never store access tokens, client data, or other secrets in committed files.
