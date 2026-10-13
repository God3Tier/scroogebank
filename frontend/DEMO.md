# Business dashboard demo script

How to run the frontend and walk through the business dashboard mockup. The demo takes about 8 minutes.

The mockup lives on `feat/midterm-demo`, which combines this business dashboard with the event-alerts review workspace for presentation purposes. All dashboard figures are synthetic. They come from `src/data/dashboard.js`, which follows the response shape in `docs/api/dashboard-api.yaml` on `feat/business-dashboard`. No backend is needed.

## 1. Before the demo

You need Node.js 20.19 or newer (Vite 8 requires `^20.19.0 || >=22.12.0`), and an internet connection. Fonts and icons load from Google Fonts. Without internet, the icons show as plain words such as "groups" or "savings".

From the repository root:

```
git switch feat/midterm-demo
cd frontend
npm i
npm run dev
```

Open the URL that Vite prints, normally `http://localhost:5173`. If port 5173 is in use, Vite picks the next free port and prints it.

Use a browser window at least 1280 px wide, so the dashboards show their full two-column layout.

## 2. Credentials

| Role | Email | Password | Lands on |
|---|---|---|---|
| Admin | `alice.admin@example.com` | `admin123` | `/admin` (Admin Dashboard) |
| Agent | `sarah.agent@example.com` | `agent123` | `/agent` (Agent Dashboard) |

Only these two seed accounts have passwords. Login is a front-end check against `src/data/admins.js` and `src/data/agents.js`. There is no session, so every page is also reachable from the sidebar without logging in.

The agent dashboard always shows Sarah Lee's book, so log in as Sarah when you present the agent view.

These credentials work on `feat/midterm-demo` only. On `dev`, sign-in goes through Cognito managed login (README, "Sign-in"), and the seed data has no passwords: sign in as a Cognito test user in the `Admin` group for Part A and one in the `Agent` group for Part B. Each role sees only its own pages, and every page except Home needs a signed-in user. The figures stay synthetic, so the agent dashboard still shows Sarah Lee's book.

## 3. Demo flow

Figures depend on today's date, because each window ends today. The script therefore describes what to point at, not exact numbers.

### Part A: Admin view (about 4 minutes)

1. **Log in as admin.** Go to **Login** and sign in as Alice. You land on the existing **Admin Dashboard**. Point out that this page manages staff and lists transactions, but answers no management questions.
2. **Open Admin Insights** in the sidebar (`/insights/admin`).
3. **Header.**
   - The **Bank-wide** badge shows the scope. The dashboard service derives it from the caller's token, never from a request parameter.
   - The line under the window selector shows the date range and "Figures as of …, cached up to 60 s". Responses are cached for up to 60 seconds, so figures are not real-time.
4. **KPI tiles.**
   - Hover over **Booked initial deposits**. The tooltip explains that it is the sum of opening deposits, not a balance.
   - Hover over **Verified share** to show another definition.
5. **Window selector.** Click **7 days**, then **90 days**, then back to **30 days**. The tiles, the chart, the table and the date range all change.
6. **Daily activity.** Clients created (left axis) and transactions (right axis). Each line has its own dash pattern, so the chart reads without colour.
7. **Transaction health.** Failure rate, pending backlog (pending for more than 24 hours) and value, plus the split by status and by type. The value has no currency label because transactions carry no currency column.
8. **Ingestion freshness.** Shows how long ago the transaction import last synced. Today this signal is visible nowhere else in the UI.
9. **Portfolio composition.**
   - Accounts by type, status and branch; clients by verification status.
   - Account keys are lowercase and client keys uppercase. The dashboard mirrors what each database stores rather than re-casing it.
10. **Agent performance.**
    - Change **Sort by** to **Booked initial deposits**, then to **Verification rate**. The highlighted column follows the sort.
    - The table is always sorted by one explicit metric. It computes no composite score or rating.
    - Rachel Wong shows as **Disabled**.
11. **Action items.**
    - Four queues: stale unverified clients, clients without an account, rejected verifications, and failed or long-pending transactions. Each row names the responsible agent.
    - Click **Open client** on **Jane Smith**. The existing client page opens.
    - Use the browser back button to return.
12. **Metric definitions** at the bottom is collapsed by default. Click the bar to expand it. Every figure on the page has a written definition, and the caveats are highlighted.

### Part B: Agent view (about 3 minutes)

1. **Log in as the agent.** Go to **Login** and sign in as Sarah. You land on the existing **Agent Dashboard**.
2. **Open Agent Insights** in the sidebar (`/insights/agent`).
3. **Header.** The badge now says **My clients only**. Every figure covers only Sarah's clients, and there is no agent parameter she could change to see another agent's figures.
4. **KPI tiles.** The same metrics as the admin view, scoped to her book.
5. **My action queue.**
   - **Stale unverified** lists Jane Smith and two others.
   - **Rejected verification** shows an explicit empty state instead of disappearing.
   - **Failed or long-pending transactions** lists John Doe twice: transaction 1003 failed and 1002 is pending.
   - Click **Open client** on the **Txn 1003 · Failed** row. John Doe's page opens, and transaction 1003 shows status Failed. The queue matches the client record.
   - Go back.
6. **How I compare.** Sarah's onboarding count against the team median. It is anonymous: no other agent is named. It appears only when at least 5 agents are active, so a median cannot reveal one colleague's figure.
7. **Client verification, My client transactions, My accounts.** Her book by verification status, her clients' transactions with the most active clients by value, and her accounts by type and status.

### Part C: Optional extras (about 1 minute)

- **Phone width.** Narrow the browser window or use the browser's device toolbar. The sidebar moves to the top, the panels stack and nothing scrolls sideways.
- **Partial results.** Mention that every panel has its own status. If one source service is down, only that panel shows "Data unavailable" while the rest keep their figures. The mockup builds this state but does not show it, because all mocked panels return data.

## 4. Metrics shown

Admin figures cover the whole bank. Agent figures cover only the logged-in agent's own clients. A metric with the same name means the same thing on both sides; only the scope differs.

Most definitions come from `docs/feature-plans/business-dashboard_plan.md` §8.3 on `feat/business-dashboard`. The app shows them in the collapsed **Metric definitions** bar and as tooltips on the KPI tiles. Metrics that §8.3 does not define are defined in the table below for this demo.

### Which metrics each side shows

| Panel | Admin Insights (bank-wide) | Agent Insights (own clients only) |
|---|---|---|
| KPI tiles | Total clients, Clients added, Verified share, Total accounts (with accounts opened), Booked initial deposits, Active agents | My clients, Added in window, Verified share, My accounts (with accounts opened), Booked initial deposits |
| Activity over time | Daily activity: clients created per day and transactions per day | Transactions per day, inside My client transactions |
| Transactions | Transaction health: failure rate, pending backlog, transaction value, counts by status and by type | My client transactions: transaction count, transaction value, failure rate, pending backlog, counts by status, most active clients by value |
| Import health | Ingestion freshness | Not shown. It describes the whole import, not one agent's clients. |
| Portfolio | Portfolio composition: accounts by type, status and branch; clients by verification status; accounts per client; currency | Client verification: clients by verification status. My accounts: accounts by type and status; accounts per client |
| People | Agent performance, per agent: clients onboarded; verified, unverified and rejected counts; verification rate; accounts opened; booked initial deposits | How I compare: my clients onboarded against the team median |
| Work queue | Action items, each row naming the responsible agent: stale unverified clients, clients without an account, rejected verifications, failed or long-pending transactions | My action queue: the same four queues for the agent's own clients |

The agent performance table sorts by one of four metrics: clients onboarded, verification rate, accounts opened, or booked initial deposits. It never combines them into a score.

### Definitions

| Metric | Definition | Caveat |
|---|---|---|
| Total clients / My clients | All clients in scope, whatever their verification status. | — |
| Clients added / Clients onboarded | Clients created in the window, counted against the agent who created them. | — |
| Verified share | Verified clients as a share of all clients in scope. | Covers all clients, not only those created in the window. |
| Verification rate | Verified clients divided by all clients created in the window. | Excludes clients created before the window. |
| Verified / unverified / rejected | Clients created in the window, split by verification status. `PENDING` counts as unverified. | — |
| Total accounts / My accounts | All accounts in scope. | — |
| Accounts opened | Accounts whose opening date is in the window. | — |
| Booked initial deposits | Sum of opening deposits for accounts opened in the window. | Opening deposit only, not a current balance. Single currency (SGD). |
| Accounts per client | Accounts divided by clients over the same scope. | At most 6: a client can hold one account of each type. |
| Active agents | Agents whose user account is enabled. The tile caption shows the full roster. | Admin only. |
| Transaction count | Transactions dated in the window. | — |
| Transaction value | Sum of transaction amounts in the window. | Unlabelled: transactions carry no currency column. |
| Transaction failure rate | Failed transactions divided by all transactions dated in the window. | Status is as supplied by the external source. |
| Pending backlog | Pending transactions dated more than 24 hours ago. | — |
| Counts by status / by type | Transactions in the window by status (`COMPLETED`, `PENDING`, `FAILED`) and by type (`DEPOSIT`, `WITHDRAWAL`). | — |
| Daily series | The count for each calendar day in the window. | Weekends are lower in the mock data. |
| Portfolio breakdowns | Accounts by type, status and branch, and clients by verification status. | Keys are shown as each database stores them: account keys lowercase, client keys uppercase. |
| Ingestion freshness | Time since the transaction import last synced. | Admin only. Warns above 60 minutes. |
| Stale unverified clients | Unverified or pending clients created more than 7 days ago. | Measured against today, not the window. |
| Clients without an account | Clients with no account. | — |
| Rejected verifications | Clients whose verification was rejected. | — |
| Failed or long-pending transactions | Clients with a failed transaction, or a transaction pending for more than 24 hours. | — |
| Team median | Median clients onboarded across active agents. The agent's own figure is shown beside it. | Agent only. Anonymous, and shown only when at least 5 agents are active. |
| Most active clients by value | The agent's clients ranked by the sum of their transaction amounts in the window. | Agent only. Top 5. |

## 5. Questions you may get

| Question | Answer |
|---|---|
| Are these real numbers? | No. They are synthetic, and the same window always gives the same figures. The backend service exists on `feat/business-dashboard`, but most of its data sources are not built yet. |
| Why "booked initial deposits" and not "balance"? | The system stores only the opening deposit. No running balance exists anywhere. |
| Can an agent see other agents' figures? | No. Scope comes from the login token, and the service that owns the client records applies it. The agent view has no agent parameter. |
| Why does the team comparison need 5 agents? | With fewer agents, a median next to your own figure can reveal a colleague's figure. |
| Why can only two clients be opened? | Only the seed clients John Doe and Jane Smith have a client page in this demo. Other rows show a greyed-out link. |

## 6. Stopping

Press `Ctrl+C` in the terminal that runs `npm run dev`.
