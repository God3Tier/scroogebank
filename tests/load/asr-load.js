// The load test the report specifies: 100 concurrent agents and 5 concurrent
// admins, a ramp-up of 1 second and a loop count of 3, as a JMeter thread
// group would run it. Each virtual user signs in as its own user, from the
// tokens file, and runs its ASR's journey (lib/asr.js) 3 times with no think
// time. Before its first loop each waits (its index / users) x 1 s, which
// spreads the starts over 1 second like JMeter's ramp-up. Results come out per
// scenario (agents, admins) and per route.
import exec from 'k6/execution';
import { sleep } from 'k6';
import { AGENT_TOKENS, ADMIN_TOKENS, requireSettings } from './lib/config.js';
import { agentJourney, adminJourney } from './lib/asr.js';

const AGENTS = parseInt(__ENV.AGENTS || '100', 10);
const ADMINS = parseInt(__ENV.ADMINS || '5', 10);
const LOOPS = parseInt(__ENV.LOOPS || '3', 10);
const RAMP_SECONDS = parseFloat(__ENV.RAMP_SECONDS || '1');

export const options = {
  scenarios: {
    agents: { executor: 'per-vu-iterations', vus: AGENTS, iterations: LOOPS, exec: 'agent', maxDuration: '5m' },
    admins: { executor: 'per-vu-iterations', vus: ADMINS, iterations: LOOPS, exec: 'admin', maxDuration: '5m' },
  },
  // The working limits from the API latency alarm (stacks/crm/observability.tf);
  // no NFR sets a number. The tagged entries make k6 report each ASR apart.
  thresholds: {
    'http_req_duration{scenario:agents}': ['p(95)<2000'],
    'http_req_duration{scenario:admins}': ['p(95)<2000'],
    'http_req_failed{scenario:agents}': ['rate<0.01'],
    'http_req_failed{scenario:admins}': ['rate<0.01'],
    'iteration_duration{scenario:agents}': ['p(95)>=0'],
    'iteration_duration{scenario:admins}': ['p(95)>=0'],
    'http_reqs{scenario:agents}': ['count>=0'],
    'http_reqs{scenario:admins}': ['count>=0'],
    'http_reqs{status:429}': ['count>=0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  requireSettings();
  if (AGENT_TOKENS.length < AGENTS || ADMIN_TOKENS.length < ADMINS) {
    throw new Error(`need ${AGENTS} agent and ${ADMINS} admin tokens, have ${AGENT_TOKENS.length} and ${ADMIN_TOKENS.length}`);
  }
}

// A VU's place among its scenario's VUs, for its token and its start offset.
function rampUp(count) {
  const index = (exec.vu.idInTest - 1) % count;
  if (exec.vu.iterationInScenario === 0) sleep((index / count) * RAMP_SECONDS);
  return index;
}

export function agent() {
  agentJourney(AGENT_TOKENS[rampUp(AGENTS)]);
}

export function admin() {
  adminJourney(ADMIN_TOKENS[rampUp(ADMINS)]);
}
