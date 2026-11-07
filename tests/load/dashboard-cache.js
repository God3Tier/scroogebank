// NFR 1: a repeat dashboard refresh within the cache TTL (3 minutes,
// ADR-0033) is answered from dashboard-service's cache, so it makes no new
// calls to the other services. Run it while dashboard-service has one task:
// the cache is per task.
//
// It asks for a reporting window no other script uses, so the first call is a
// miss. Calls inside the TTL must return the same `generatedAt`; a call after
// it must return a new one. Compare the downstream services' Service Connect
// request counts over the same minutes for the calls themselves.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, AGENT_TOKENS, requireSettings, params, json, sgDate } from './lib/config.js';

const REPEATS = parseInt(__ENV.REPEATS || '20', 10);
const GAP_SECONDS = parseFloat(__ENV.GAP_SECONDS || '6');
const TTL_SECONDS = parseInt(__ENV.TTL_SECONDS || '180', 10);

export const options = {
  scenarios: { cache: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '10m' } },
  thresholds: { checks: ['rate==1'] },
};

export function setup() {
  requireSettings({ admin: false });
}

function overview(token, query) {
  const res = http.get(`${BASE_URL}/api/dashboard/overview?${query}`, params(token, 'GET /api/dashboard/overview'));
  check(res, { 'status is 200': (r) => r.status === 200 });
  const body = json(res);
  return body ? body.generatedAt : null;
}

export default function () {
  const token = AGENT_TOKENS[0];
  const query = `from=${sgDate(-60)}&to=${sgDate(-31)}`;

  const first = overview(token, query);
  console.log(`miss at ${new Date().toISOString()}: generatedAt ${first}`);
  const repeats = [];
  for (let i = 0; i < REPEATS; i++) {
    sleep(GAP_SECONDS);
    repeats.push(overview(token, query));
  }
  console.log(`${REPEATS} repeats until ${new Date().toISOString()}: ${new Set(repeats).size} distinct generatedAt`);
  check(repeats, { 'every repeat inside the TTL came from the cache': (r) => r.every((g) => g === first) });

  sleep(TTL_SECONDS + 10 - REPEATS * GAP_SECONDS);
  const after = overview(token, query);
  console.log(`after the TTL at ${new Date().toISOString()}: generatedAt ${after}`);
  check(after, { 'a call after the TTL was generated again': (g) => g !== null && g !== first });
}
