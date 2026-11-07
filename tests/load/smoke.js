// One call to every route in both journeys. Run it before any load: every
// check must pass, or the load results would measure errors, not the system.
import { requireSettings, AGENT_TOKENS, ADMIN_TOKEN } from './lib/config.js';
import { discover, agentRoutes, adminRoutes, call } from './lib/journeys.js';

export const options = {
  scenarios: { smoke: { executor: 'shared-iterations', vus: 1, iterations: 1 } },
  thresholds: { checks: ['rate==1'] },
};

export function setup() {
  requireSettings();
  return { agents: discover(AGENT_TOKENS) };
}

export default function ({ agents }) {
  for (const agent of agents) {
    agentRoutes.forEach((route) => call(route, AGENT_TOKENS[agent.index], agent));
  }
  adminRoutes.forEach((route) => call(route, ADMIN_TOKEN));
}
