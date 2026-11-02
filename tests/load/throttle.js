// A short burst above API Gateway's stage limit (50 requests per second,
// burst 100; stacks/crm/api.tf), to show the limit answers 429 and nothing
// behind it fails. 429 counts as expected here; any 5xx fails the run.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, AGENT_TOKENS, requireSettings, params } from './lib/config.js';

const RATE = parseInt(__ENV.RATE || '80', 10);
const throttled = new Counter('throttled_429');
const serverErrors = new Counter('server_errors_5xx');

http.setResponseCallback(http.expectedStatuses(200, 429));

export const options = {
  scenarios: {
    burst: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: __ENV.DURATION || '60s', preAllocatedVUs: RATE, maxVUs: RATE * 4 },
  },
  thresholds: { server_errors_5xx: ['count==0'], http_req_failed: ['rate==0'] },
};

export function setup() {
  requireSettings({ admin: false });
}

export default function () {
  const token = AGENT_TOKENS[Math.floor(Math.random() * AGENT_TOKENS.length)];
  const res = http.get(`${BASE_URL}/api/clients?page=0&size=1`, params(token, 'GET /api/clients'));
  if (res.status === 429) throttled.add(1);
  if (res.status >= 500) serverErrors.add(1);
  check(res, { 'served or throttled': (r) => r.status === 200 || r.status === 429 });
}
