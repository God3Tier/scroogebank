// The read traffic an agent and an admin make in the app, one request per
// call, so an arrival rate in iterations per second is a rate in requests per
// second. Weights roughly follow the pages each role opens most.
import http from 'k6/http';
import { check, fail } from 'k6';
import { BASE_URL, AGENT_TOKENS, params, json, sgDate } from './config.js';

// Lists each agent's clients and their accounts once, in setup(), so the
// journeys can ask for real IDs. It returns each agent's index, not its token:
// setup()'s result is written into k6's summary export.
export function discover(tokens, maxClients = 100) {
  return tokens.map((token, i) => {
    const res = http.get(`${BASE_URL}/api/clients?page=0&size=${maxClients}`, params(token, 'GET /api/clients'));
    const page = json(res);
    if (res.status !== 200 || !page) fail(`agent ${i}: GET /api/clients answered ${res.status}`);
    const clients = page.content.map((c) => c.client_id);
    const accounts = [];
    for (const id of clients) {
      const accs = json(http.get(`${BASE_URL}/api/accounts?clientId=${id}`, params(token, 'GET /api/accounts')));
      if (Array.isArray(accs)) accs.forEach((a) => accounts.push(a.id));
    }
    if (clients.length === 0 || accounts.length === 0) fail(`agent ${i} has no clients or accounts: run seed.js first`);
    return { index: i, clients, accounts };
  });
}

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

function weighted(routes) {
  const total = routes.reduce((sum, r) => sum + r.weight, 0);
  let n = Math.random() * total;
  for (const r of routes) {
    n -= r.weight;
    if (n < 0) return r;
  }
  return routes[routes.length - 1];
}

const ok = { 'status is 200': (r) => r.status === 200 };

export const agentRoutes = [
  { name: 'GET /api/dashboard/overview', weight: 15, url: () => '/api/dashboard/overview' },
  { name: 'GET /api/clients', weight: 20, url: () => '/api/clients?page=0&size=20' },
  { name: 'GET /api/clients/{clientId}', weight: 20, url: (a) => `/api/clients/${pick(a.clients)}` },
  { name: 'GET /api/accounts', weight: 15, url: (a) => `/api/accounts?clientId=${pick(a.clients)}` },
  { name: 'GET /api/accounts/{accountId}/transactions', weight: 15, url: (a) => `/api/accounts/${pick(a.accounts)}/transactions` },
  { name: 'GET /api/alerts', weight: 10, url: () => '/api/alerts' },
  {
    name: 'GET /api/engagement/events',
    weight: 5,
    url: () => `/api/engagement/events?from=${sgDate(-7)}T00:00:00%2B08:00&to=${sgDate(23)}T00:00:00%2B08:00`,
  },
];

export const adminRoutes = [
  { name: 'GET /api/users', weight: 25, url: () => '/api/users?page=0&size=20' },
  { name: 'GET /api/activities', weight: 25, url: () => '/api/activities' },
  { name: 'GET /api/transactions', weight: 25, url: () => '/api/transactions?size=20' },
  { name: 'GET /api/dashboard/agents', weight: 25, url: () => '/api/dashboard/agents' },
];

export function call(route, token, agent) {
  const res = http.get(`${BASE_URL}${route.url(agent)}`, params(token, route.name));
  check(res, ok);
  return res;
}

export function agentRequest(agents) {
  const agent = pick(agents);
  return call(weighted(agentRoutes), AGENT_TOKENS[agent.index], agent);
}

export function adminRequest(token) {
  return call(weighted(adminRoutes), token);
}
