// Seeds the data volume the report's load test specifies, through the real
// API: CLIENTS_PER_AGENT clients (default 100) for every agent in the tokens
// file, each with ACCOUNTS_PER_CLIENT accounts (default 3). With 100 agents
// that is 10,000 clients and 30,000 accounts; seed-transactions.sh then adds
// 50 transactions per account. All data is made up: example.com addresses and
// fake names.
//
// One iteration creates one client and its accounts, at RATE iterations per
// second (default 30, about 120 requests per second), under API Gateway's
// 200 per second. A request answered 429 or 5xx is retried; a client that
// already exists (409) is skipped, so a second run fills only the gaps.
import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { BASE_URL, AGENT_TOKENS, requireSettings, params, json, JSON_BODY } from './lib/config.js';

const PER_AGENT = parseInt(__ENV.CLIENTS_PER_AGENT || '100', 10);
const ACCOUNTS = parseInt(__ENV.ACCOUNTS_PER_CLIENT || '3', 10);
const RATE = parseInt(__ENV.RATE || '30', 10);
const TOTAL = AGENT_TOKENS.length * PER_AGENT;
const TYPES = ['SAVINGS', 'CHECKING', 'INVESTMENT'];
const FIRST_NAMES = ['Avery', 'Blake', 'Casey', 'Devon', 'Emery', 'Finley', 'Gray', 'Harper', 'Indigo', 'Jordan',
  'Kai', 'Logan', 'Morgan', 'Noel', 'Oakley', 'Parker', 'Quinn', 'Riley', 'Sage', 'Taylor', 'Umber', 'Vale',
  'Wren', 'Xen', 'Yael', 'Zion'];

export const options = {
  scenarios: {
    seed: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: `${Math.ceil(TOTAL / RATE) + 30}s`,
      preAllocatedVUs: 60,
      maxVUs: 200,
    },
  },
  thresholds: { checks: ['rate>0.999'] },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  requireSettings({ admin: false });
}

// Two letters per agent, since client names may hold letters only.
function letters(a) {
  return String.fromCharCode(65 + Math.floor(a / 26)) + String.fromCharCode(65 + (a % 26));
}

function post(path, body, token, name) {
  let res;
  for (let attempt = 0; attempt < 5; attempt++) {
    res = http.post(`${BASE_URL}${path}`, JSON.stringify(body), params(token, name, JSON_BODY));
    if (res.status !== 429 && res.status < 500) return res;
    sleep(0.5 * 2 ** attempt);
  }
  return res;
}

export default function () {
  const i = exec.scenario.iterationInTest;
  if (i >= TOTAL) return;
  const a = i % AGENT_TOKENS.length;
  const n = Math.floor(i / AGENT_TOKENS.length);
  const token = AGENT_TOKENS[a];

  const client = {
    first_name: FIRST_NAMES[n % FIRST_NAMES.length],
    last_name: `Loadtest ${letters(a)}`,
    date_of_birth: `${1950 + (n % 50)}-0${1 + (n % 9)}-15T00:00:00Z`,
    gender: n % 2 === 0 ? 'Female' : 'Male',
    email: `loadtest.a${a}.c${n}@example.com`,
    phone_number: `+659${String(a).padStart(3, '0')}${String(n).padStart(4, '0')}`,
    address: `${n + 1} Synthetic Street`,
    city: 'Singapore',
    state: 'Singapore',
    country: 'Singapore',
    postal_code: String(100000 + a * 1000 + n),
  };
  const created = post('/api/clients', client, token, 'POST /api/clients');
  check(created, { 'client created or already there': (r) => r.status === 201 || r.status === 409 });
  if (created.status !== 201) return;

  for (let k = 0; k < ACCOUNTS; k++) {
    const account = {
      client_id: json(created).client_id,
      account_type: TYPES[k % TYPES.length],
      status: 'PENDING', // the client is unverified (ADR-0050)
      initial_deposit: 500 + ((n * 37 + k * 211) % 9500),
      currency: 'SGD',
      branch_id: 'SG-LOADTEST',
    };
    const opened = post('/api/accounts', account, token, 'POST /api/accounts');
    check(opened, { 'account opened': (r) => r.status === 201 });
  }
}
