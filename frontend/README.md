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
empty/error states.

## Conventions

- Keep backend calls in `src/api`; do not put actor identity in request URLs.
- Use `src/data` only for synthetic frontend fixtures.
- Keep reusable UI in `src/components` and routed screens in `src/pages`.
- Never store access tokens, client data, or other secrets in committed files.
