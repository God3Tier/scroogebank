// Settings shared by every k6 script, read from the environment so that no
// token or address is written into the repository (see ../README.md).
import { fail } from 'k6';

export const BASE_URL = (__ENV.BASE_URL || '').replace(/\/+$/, '');
export const AGENT_TOKENS = (__ENV.AGENT_TOKENS || '').split(',').map((t) => t.trim()).filter(Boolean);
export const ADMIN_TOKEN = (__ENV.ADMIN_TOKEN || '').trim();

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
