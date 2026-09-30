# CRM Frontend
Frontend Tree
```
frontend/
├── public/
├── src/
│   ├── api/
│   │   ├── authApi.js
│   │   ├── clientApi.js
│   │   └── transactionApi.js
│   ├── components/
│   │   └── NavBar.jsx
│   ├── data/                   <--- Temporary Dataset
│   │   ├── admins.js
│   │   ├── agents.js
│   │   ├── clients.js
│   │   └── transactions.js
│   ├── pages/
│   │   ├── AdminDashboard.jsx
│   │   ├── AgentDashboard.jsx
│   │   ├── ClientTransactions.jsx
│   │   ├── Home.jsx
│   │   └── Login.jsx
│   ├── styles/
│   │   └── index.css
│   ├── App.jsx
│   ├── AppRouter.jsx
│   └── main.jsx
├── .env.example
├── .gitignore
├── package.json
├── package-lock.json
└── vite.config.js
```

## Starting up frontend

```
cd frontend
npm i
npm run dev
```

## Folder allocation

- All apis that calls the backend services should be placed in `frontend/src/api`.
- All components that are made for this project should be placed in `frontend/src/components`.
- All temporary dataset that we are only testing for frontend only should be placed in `frontend/src/data`
- All pages that are made should be placed in `frontend/src/pages`
- All CSS styles should be placed in `frontend/src/styles`

## Business dashboard mockup (demo only)

This exists only on the local branch `task/business-dashboard/frontend-mockup`, which is never pushed or merged. It mocks the business dashboard proposed on `feat/business-dashboard`.

- `/insights/admin`: bank-wide dashboard with KPI tiles, daily activity, transaction health, ingestion freshness, portfolio composition, agent performance and action items.
- `/insights/agent`: one agent's own book with KPI tiles, action queue, team comparison, portfolio and client transactions.
- Data: synthetic, from `src/data/dashboard.js`. It follows the response shape in `docs/api/dashboard-api.yaml` on `feat/business-dashboard`. The window selector (7, 30, 90 days) changes the figures.
- Styling: Stitch project "Scrooge Global Bank CRM" (design system: Institutional Precision). Dashboard styles are in `src/styles/dashboard.css`, chart colours in `src/styles/chartTheme.js`.
- Charts: Recharts.

## Future update
- Reallocate `pages` and have subset, such as `pages/admin`, `pages/agent`.

## Documentation (WIP)
Only accessible using SMU account,
https://docs.google.com/document/d/1wl43MPLxXKcpU2aMTF2B2nd0jSAcVojoHoeuVx3Rjes/edit?usp=sharing

## Expanding the ESLint configuration

If you are developing a production application, we recommend using TypeScript with type-aware lint rules enabled. Check out the [TS template](https://github.com/vitejs/vite/tree/main/packages/create-vite/template-react-ts) for information on how to integrate TypeScript and [`typescript-eslint`](https://typescript-eslint.io) in your project.
