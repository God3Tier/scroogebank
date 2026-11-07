// Creates synthetic clients, one account each, for every agent in
// AGENT_TOKENS, through the real API, so the load tests read real rows.
// Run it once per deployment. A second run skips clients that already exist
// (409). All data is made up: example.com addresses and fake names.
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, AGENT_TOKENS, requireSettings, params, json, JSON_BODY } from './lib/config.js';

const PER_AGENT = parseInt(__ENV.CLIENTS_PER_AGENT || '25', 10);
const FIRST_NAMES = ['Avery', 'Blake', 'Casey', 'Devon', 'Emery', 'Finley', 'Gray', 'Harper', 'Indigo', 'Jordan',
  'Kai', 'Logan', 'Morgan', 'Noel', 'Oakley', 'Parker', 'Quinn', 'Riley', 'Sage', 'Taylor', 'Umber', 'Vale',
  'Wren', 'Xen', 'Yael', 'Zion'];

export const options = {
  scenarios: { seed: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '10m' } },
  thresholds: { checks: ['rate==1'] },
};

export function setup() {
  requireSettings({ admin: false });
}

export default function () {
  AGENT_TOKENS.forEach((token, a) => {
    const agentLetter = String.fromCharCode(65 + a);
    for (let n = 0; n < PER_AGENT; n++) {
      const client = {
        first_name: FIRST_NAMES[n % FIRST_NAMES.length],
        last_name: `Loadtest ${agentLetter}`,
        date_of_birth: `${1960 + (n % 40)}-0${1 + (n % 9)}-15T00:00:00Z`,
        gender: n % 2 === 0 ? 'Female' : 'Male',
        email: `loadtest.agent${a}.client${n}@example.com`,
        phone_number: `+6590${a}${String(n).padStart(5, '0')}`,
        address: `${n + 1} Synthetic Street`,
        city: 'Singapore',
        state: 'Singapore',
        country: 'Singapore',
        postal_code: String(100000 + a * 1000 + n),
      };
      const created = http.post(`${BASE_URL}/api/clients`, JSON.stringify(client),
        params(token, 'POST /api/clients', JSON_BODY));
      check(created, { 'client created or already there': (r) => r.status === 201 || r.status === 409 });
      if (created.status !== 201) continue;

      const account = {
        client_id: json(created).client_id,
        account_type: 'SAVINGS',
        status: 'PENDING', // the client is unverified (ADR-0050)
        initial_deposit: 1000 + n * 50,
        currency: 'SGD',
        branch_id: 'SG-LOADTEST',
      };
      const opened = http.post(`${BASE_URL}/api/accounts`, JSON.stringify(account),
        params(token, 'POST /api/accounts', JSON_BODY));
      check(opened, { 'account opened': (r) => r.status === 201 });
    }
  });
}
