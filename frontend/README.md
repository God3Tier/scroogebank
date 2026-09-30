# CRM frontend

React 19 and Vite frontend for the CRM application. API modules live in
`src/api`, shared components in `src/components`, route pages in `src/pages`,
and frontend-only synthetic fixtures in `src/data`.

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
