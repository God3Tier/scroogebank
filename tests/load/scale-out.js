// NFR 1: steady read load that should make the services scale out, then
// stop so they can scale back in. Watch the task counts with
// scripts/capture-scaling.sh while it runs, and for about 20 minutes after.
//
// The peak stays under API Gateway's stage limit (50 requests per second,
// stacks/crm/api.tf), so failures here are the services', not throttling;
// throttle.js tests the limit itself. The latency threshold is the API
// latency alarm's 2000 ms (stacks/crm/observability.tf): no NFR sets a number.
import { requireSettings, AGENT_TOKENS, ADMIN_TOKEN } from './lib/config.js';
import { discover, agentRequest, adminRequest } from './lib/journeys.js';

const PEAK = parseInt(__ENV.PEAK_RPS || '40', 10);
const RAMP = __ENV.RAMP || '3m';
const HOLD = __ENV.HOLD || '12m';

// Agents make about 80% of the requests, the admin the rest.
function profile(share) {
  const target = Math.max(1, Math.round(PEAK * share));
  return {
    executor: 'ramping-arrival-rate',
    startRate: 0,
    timeUnit: '1s',
    preAllocatedVUs: target * 2,
    maxVUs: target * 10,
    stages: [
      { target, duration: RAMP },
      { target, duration: HOLD },
      { target: 0, duration: '1m' },
    ],
  };
}

export const options = {
  scenarios: {
    agents: Object.assign(profile(0.8), { exec: 'agents' }),
    admin: Object.assign(profile(0.2), { exec: 'admin' }),
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<2000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  requireSettings();
  return { agents: discover(AGENT_TOKENS) };
}

export function agents(data) {
  agentRequest(data.agents);
}

export function admin() {
  adminRequest(ADMIN_TOKEN);
}
