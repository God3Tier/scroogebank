// The two journeys the report's load test names, one per architecturally
// significant requirement (ASR), as a user clicks through them:
//
// - Agent ASR: the agent's clients, one client, its accounts, and one
//   account's transactions (4 requests).
// - Admin ASR: the users list and the activity feed (2 requests).
import http from 'k6/http';
import { check, group } from 'k6';
import { BASE_URL, params, json } from './config.js';

const ok = { 'status is 200': (r) => r.status === 200 };

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

export function agentJourney(token) {
  group('Agent ASR', () => {
    const clients = http.get(`${BASE_URL}/api/clients?page=0&size=20`, params(token, 'GET /api/clients'));
    check(clients, ok);
    const page = json(clients);
    if (!page || !page.content || page.content.length === 0) return;
    const clientId = pick(page.content).client_id;

    check(http.get(`${BASE_URL}/api/clients/${clientId}`, params(token, 'GET /api/clients/{clientId}')), ok);

    const accounts = http.get(`${BASE_URL}/api/accounts?clientId=${clientId}`, params(token, 'GET /api/accounts'));
    check(accounts, ok);
    const list = json(accounts);
    if (!Array.isArray(list) || list.length === 0) return;

    check(http.get(`${BASE_URL}/api/accounts/${pick(list).id}/transactions`,
      params(token, 'GET /api/accounts/{accountId}/transactions')), ok);
  });
}

export function adminJourney(token) {
  group('Admin ASR', () => {
    check(http.get(`${BASE_URL}/api/users?page=0&size=20`, params(token, 'GET /api/users')), ok);
    check(http.get(`${BASE_URL}/api/activities`, params(token, 'GET /api/activities')), ok);
  });
}
