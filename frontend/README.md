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

## Future update
- Reallocate `pages` and have subset, such as `pages/admin`, `pages/agent`.

## Documentation (WIP)
Only accessible using SMU account,
https://docs.google.com/document/d/1wl43MPLxXKcpU2aMTF2B2nd0jSAcVojoHoeuVx3Rjes/edit?usp=sharing

## Expanding the ESLint configuration

If you are developing a production application, we recommend using TypeScript with type-aware lint rules enabled. Check out the [TS template](https://github.com/vitejs/vite/tree/main/packages/create-vite/template-react-ts) for information on how to integrate TypeScript and [`typescript-eslint`](https://typescript-eslint.io) in your project.
