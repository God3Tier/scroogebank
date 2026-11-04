// The availability drills' probe (test-strategy.md, NFR 2 and 5): one agent
// lists its clients once a second for DURATION (default 10m), or requests
// PROBE_PATH instead, for a drill on another service. Run it with
// --out csv: each request's time and status is a row, so the outage is the run
// of non-200 rows, and the recovery time is its length.
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, AGENT_TOKENS, requireSettings, params } from './lib/config.js';

const PATH = __ENV.PROBE_PATH || '/api/clients?page=0&size=1';

export const options = {
  scenarios: {
    probe: {
      executor: 'constant-arrival-rate',
      rate: 1,
      timeUnit: '1s',
      duration: __ENV.DURATION || '10m',
      preAllocatedVUs: 5,
      maxVUs: 30,
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  requireSettings({ admin: false });
}

export default function () {
  const res = http.get(`${BASE_URL}${PATH}`,
    Object.assign(params(AGENT_TOKENS[0], `GET ${PATH.split('?')[0]}`), { timeout: '10s' }));
  check(res, { 'status is 200': (r) => r.status === 200 });
}
