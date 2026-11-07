// A stress test beyond the required load: the agent ASR's journey (see
// asr-load.js) with virtual agents stepping up from 100 to STEPS x 100,
// STEP_HOLD (default 3m) at each level, to find where response times and
// errors start to rise and to watch the services scale out. Each virtual
// agent waits THINK seconds (default 1) between journeys, and they share the
// tokens file's agents, so 400 virtual agents are 4 per real one.
//
// API Gateway's 200 requests per second would throttle this first, so the
// stage limit is raised for the run only (see README.md).
import { sleep } from 'k6';
import exec from 'k6/execution';
import { AGENT_TOKENS, requireSettings } from './lib/config.js';
import { agentJourney } from './lib/asr.js';

const STEPS = parseInt(__ENV.STEPS || '4', 10);
const HOLD = __ENV.STEP_HOLD || '3m';
const THINK = parseFloat(__ENV.THINK || '1');

const stages = [];
for (let s = 1; s <= STEPS; s++) {
  stages.push({ duration: '30s', target: s * 100 }, { duration: HOLD, target: s * 100 });
}
stages.push({ duration: '30s', target: 0 });

export const options = {
  scenarios: { agents: { executor: 'ramping-vus', startVUs: 0, stages, gracefulRampDown: '10s' } },
  thresholds: {
    http_req_duration: ['p(95)<2000'],
    http_req_failed: ['rate<0.01'],
    'http_reqs{status:429}': ['count>=0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  requireSettings({ admin: false });
}

export default function () {
  agentJourney(AGENT_TOKENS[(exec.vu.idInTest - 1) % AGENT_TOKENS.length]);
  sleep(THINK);
}
