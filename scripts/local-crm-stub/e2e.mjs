// Real-process check of database holdings mode: the packaged jar, PostgreSQL in Docker
// and the stub CRM. Synthetic data only. See scripts/local-crm-stub/README.md.
//
//   mvn -pl services/event-alert-service -DskipTests package
//   docker run -d --name alert-e2e-pg -e POSTGRES_USER=event_alert -e POSTGRES_PASSWORD=e2e \
//     -e POSTGRES_DB=event_alert -p 55432:5432 postgres:16-alpine
//   node scripts/local-crm-stub/e2e.mjs
import { execFileSync, spawn } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const jar = path.join(root, 'services/event-alert-service/target/event-alert-service.jar')
const STUB = 'http://127.0.0.1:9099'
const API = 'http://127.0.0.1:18080'
const CLIENT = 'c0000000-0000-4000-8000-000000000001'
const ACCOUNT = 'a0000000-0000-4000-8000-000000000001'
const SAVINGS = 'a0000000-0000-4000-8000-000000000002'

const env = (mode) => ({
  ...process.env,
  ALERT_MODE: mode, SERVER_PORT: '18080', MANAGEMENT_SERVER_PORT: '',
  EVENT_ALERT_DB_URL: 'jdbc:postgresql://localhost:55432/event_alert',
  EVENT_ALERT_DB_USERNAME: 'event_alert', EVENT_ALERT_DB_PASSWORD: 'e2e',
  COGNITO_ISSUER_URI: STUB, COGNITO_APP_CLIENT_ID: 'local-app-client',
  ALERT_HOLDINGS_MODE: 'database',
  ALERT_CRM_ACCOUNT_DIRECTORY_URL: `${STUB}/accounts`, ALERT_CRM_CLIENT_DIRECTORY_URL: `${STUB}/clients`,
  ALERT_CRM_TOKEN_ENDPOINT: `${STUB}/token`, ALERT_CRM_CLIENT_ID: 'alert-directory',
  ALERT_CRM_CLIENT_SECRET: 'local-only-not-a-secret', ALERT_CRM_SCOPE: 'crm-alerts/directory-read',
})

let failures = 0
function check(name, ok, detail = '') {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${ok || !detail ? '' : `  -> ${detail}`}`)
  if (!ok) failures += 1
}
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
async function call(url, { method = 'GET', token, headers = {}, body } = {}) {
  const response = await fetch(url, {
    method,
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}), ...headers },
    body: body ? JSON.stringify(body) : undefined,
  })
  const text = await response.text()
  let json = null
  try { json = JSON.parse(text) } catch { /* not JSON */ }
  return { status: response.status, json }
}
const stubPost = (route, body) => call(`${STUB}${route}`, { method: 'POST', body })
const mint = async (sub, groups) => (await stubPost('/_mint', { sub, groups })).json.token
const psql = (sql) => execFileSync('docker', ['exec', 'alert-e2e-pg', 'psql', '-U', 'event_alert', '-d', 'event_alert', '-tAc', sql]).toString().trim()

function startJar(mode) {
  const child = spawn('java', ['-jar', jar], { env: env(mode), stdio: ['ignore', 'pipe', 'pipe'] })
  let log = ''
  child.stdout.on('data', (d) => { log += d })
  child.stderr.on('data', (d) => { log += d })
  return { child, log: () => log }
}
async function waitHealthy(app) {
  for (let i = 0; i < 90; i += 1) {
    try { if ((await fetch(`${API}/actuator/health`)).ok) return true } catch { /* not up yet */ }
    if (app.child.exitCode !== null) return false
    await sleep(1000)
  }
  return false
}
const stop = (app) => new Promise((resolve) => { app.child.once('exit', resolve); app.child.kill() })

const crmRecords = (agent) => ({
  accounts: [
    { id: ACCOUNT, clientId: CLIENT, type: 'INVESTMENT', status: 'ACTIVE', currency: 'SGD' },
    { id: SAVINGS, clientId: CLIENT, type: 'SAVINGS', status: 'ACTIVE', currency: 'SGD' },
  ],
  clients: [{ id: CLIENT, assignedAgentSubject: agent }],
})
const portfolio = {
  expectedRevision: 0, sourceLabel: 'e2e-entry',
  positions: [{ instrumentId: 'SYN-SGX-001', quantity: 10, unitPrice: 1.5, valuationCurrency: 'SGD', fxToSgd: 1, valuedAt: new Date(Date.now() - 3600_000).toISOString() }],
}

const stub = spawn('node', [path.join(root, 'scripts/local-crm-stub/stub-crm.mjs'), '9099'], { stdio: 'ignore' })
let api = null
try {
  // A leftover process on the port would answer with an old stub's signing key.
  const stray = await fetch(`${API}/actuator/health`).then(() => true, () => false)
  if (stray) throw new Error(`port 18080 is already in use; stop the leftover event-alert-service process`)
  await sleep(1000)
  await stubPost('/_set', crmRecords('agent-a'))
  const [agentA, agentB, admin] = [await mint('agent-a', ['agent']), await mint('agent-b', ['agent']), await mint('admin-1', ['admin'])]

  api = startJar('API')
  check('API starts in database mode against the migrated database', await waitHealthy(api), api.log().slice(-600))
  const url = `${API}/api/alert-portfolios/${ACCOUNT}`
  const put = (token, key, extra = {}, account = ACCOUNT) => call(`${API}/api/alert-portfolios/${account}`, { method: 'PUT', token, headers: { 'Idempotency-Key': key }, body: { ...portfolio, ...extra } })

  check('no token is refused (401)', (await call(url)).status === 401)
  check('nothing recorded yet is 404', (await call(url, { token: agentA })).status === 404)
  const first = await put(agentA, 'e2e-key-00000001')
  check('agent records a portfolio (revision 1)', first.status === 200 && first.json.revision === 1, JSON.stringify(first.json))
  const replay = await put(agentA, 'e2e-key-00000001')
  check('the same request replays without a new revision', replay.status === 200 && replay.json.revision === 1)
  check('a stale revision is refused (409)', (await put(agentA, 'e2e-key-00000002')).status === 409)
  check('another agent cannot see or write it (404)', (await call(url, { token: agentB })).status === 404 && (await put(agentB, 'e2e-key-00000003', { expectedRevision: 1 })).status === 404)
  check('admin can read (200) but not write (403)', (await call(url, { token: admin })).status === 200 && (await put(admin, 'e2e-key-00000004', { expectedRevision: 1 })).status === 403)
  check('a savings account cannot hold positions (409)', (await put(agentA, 'e2e-key-00000005', {}, SAVINGS)).status === 409)
  check('an unknown instrument is refused (422)', (await put(agentA, 'e2e-key-00000006', { expectedRevision: 1, positions: [{ ...portfolio.positions[0], instrumentId: 'NOPE' }] })).status === 422)

  await stop(api)
  api = startJar('API')
  check('API restarts', await waitHealthy(api), api.log().slice(-600))
  const afterRestart = await call(url, { token: agentA })
  check('the portfolio survives a restart', afterRestart.status === 200 && afterRestart.json.revision === 1 && afterRestart.json.positions.length === 1 && afterRestart.json.totalSgd === 15, JSON.stringify(afterRestart.json))

  await stubPost('/_set', crmRecords('agent-b'))
  check('after reassignment the old agent is refused and the new agent admitted',
    (await call(url, { token: agentA })).status === 404 && (await call(url, { token: agentB })).status === 200)

  await stubPost('/_down', { down: true })
  check('an unreachable CRM fails closed (503), not empty', (await call(url, { token: agentB })).status === 503)
  await stubPost('/_down', { down: false })
  check('and recovers when the CRM returns', (await call(url, { token: agentB })).status === 200)

  const audit = psql("SELECT event_type FROM audit_outbox WHERE event_type LIKE 'alert.portfolio%'")
  check('one audit event was written for the one real change', audit === 'alert.portfolio-replaced', audit)
  check('the audit payload holds no quantities or valuations', psql("SELECT COUNT(*) FROM audit_outbox WHERE payload::text ILIKE '%quantity%' OR payload::text ILIKE '%unitPrice%'") === '0')
  check('exactly one revision is stored', psql(`SELECT revision FROM current_portfolio WHERE account_id = '${ACCOUNT}'`) === '1')

  await stop(api)
  api = startJar('WORKER')
  const exit = await new Promise((resolve) => { api.child.once('exit', resolve); setTimeout(() => resolve('timeout'), 90_000) })
  check('the worker runs once in database mode and exits cleanly with outbound calls off', exit === 0, `${exit}\n${api.log().slice(-600)}`)
} finally {
  if (api && api.child.exitCode === null) api.child.kill()
  stub.kill()
}
console.log(failures === 0 ? '\nAll checks passed.' : `\n${failures} check(s) failed.`)
process.exit(failures === 0 ? 0 : 1)
