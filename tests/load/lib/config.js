// Settings shared by every k6 script, read from the environment so that no
// token or address is written into the repository (see ../README.md).
import { fail } from 'k6';

export const BASE_URL = (__ENV.BASE_URL || '').replace(/\/+$/, '');

// TOKENS_FILE names a JSON file, {"agents": [...], "admins": [...]}, mounted
// into the container; a hundred tokens are too long for one variable. Without
// it, AGENT_TOKENS (comma-separated) and ADMIN_TOKEN are read instead.
const FILE = __ENV.TOKENS_FILE ? JSON.parse(open(__ENV.TOKENS_FILE)) : null;
const list = (s) => (s || '').split(',').map((t) => t.trim()).filter(Boolean);
export const AGENT_TOKENS = FILE ? FILE.agents || [] : list(__ENV.AGENT_TOKENS);
export const ADMIN_TOKENS = FILE ? FILE.admins || [] : list(__ENV.ADMIN_TOKEN);
export const ADMIN_TOKEN = ADMIN_TOKENS[0] || '';

export function requireSettings({ admin = true } = {}) {
  if (!BASE_URL.startsWith('https://')) fail('BASE_URL must be the deployment\'s https:// address');
  if (AGENT_TOKENS.length === 0) fail('AGENT_TOKENS must hold at least one agent access token');
  if (admin && !ADMIN_TOKEN) fail('ADMIN_TOKEN must hold an admin access token');
}

// Each request is tagged with its route template, so results group by route
// rather than by client or account ID.
export function params(token, name, headers = {}) {
  return { headers: Object.assign({ Authorization: `Bearer ${token}` }, headers), tags: { name } };
}

export const JSON_BODY = { 'Content-Type': 'application/json' };

export function json(res) {
  try {
    return res.json();
  } catch (e) {
    return null;
  }
}

// A Singapore calendar date, `days` from today, as the dashboard's `from` and `to` expect.
export function sgDate(days) {
  const sg = new Date(Date.now() + 8 * 3600 * 1000 + days * 86400 * 1000);
  return sg.toISOString().slice(0, 10);
}
