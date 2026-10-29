// Database holdings mode against the REAL account-service (its Docker image and PostgreSQL, the real
// /internal/alerts/accounts/lookup route, scope and client allow-list, and the real ownership
// route). Only Cognito is a stand-in: stub-crm.mjs mints the tokens and serves the keys over HTTPS under
// the cognito-idp hostname the Go service insists on. Synthetic data only.
//
//   mvn -pl services/event-alert-service -DskipTests package
//   node scripts/local-crm-stub/e2e-real-account.mjs        # needs Docker; ports 9099, 18080, 18083, 55432 free
import { execFileSync, spawn } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const jar = path.join(root, 'services/event-alert-service/target/event-alert-service.jar')
const NET = 'alert-real-e2e'
const HOST = 'cognito-idp.ap-southeast-1.amazonaws.com'
const ISSUER = `https://${HOST}/ap-southeast-1_E2E`
const STUB = 'http://127.0.0.1:9099'
const ACCOUNT_SVC = 'http://127.0.0.1:18083'
const API = 'http://127.0.0.1:18080'
const CLIENT = 'c0000000-0000-4000-8000-000000000001'
const INVEST = 'a0000000-0000-4000-8000-000000000001'
const SAVINGS = 'a0000000-0000-4000-8000-000000000002'
const NAMES = ['e2e-stub', 'e2e-account', 'e2e-account-pg', 'e2e-alert-pg']

// On Windows the `java` launcher starts the real JVM as a child, so kill the whole tree.
function killTree(child) {
  if (child.exitCode !== null) return
  if (process.platform === 'win32') {
    try { execFileSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore' }) } catch { /* already gone */ }
  } else {
    child.kill()
  }
}

let failures = 0
function check(name, ok, detail = '') {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${ok || !detail ? '' : `  -> ${detail}`}`)
  if (!ok) failures += 1
}
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
const docker = (...args) => execFileSync('docker', args, { stdio: ['ignore', 'pipe', 'pipe'] }).toString().trim()
const tryDocker = (...args) => { try { return docker(...args) } catch { return '' } }

async function call(url, { method = 'GET', token, headers = {}, body } = {}) {
  const response = await fetch(url, {
    method,
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}), ...headers },
    body: body ? JSON.stringify(body) : undefined,
  })
  const text = await response.text()
  let json = null
  try { json = JSON.parse(text) } catch { /* not JSON */ }
  return { status: response.status, json, text }
}
const mint = async (sub, groups, issuer) => (await call(`${STUB}/_mint`, { method: 'POST', body: { sub, groups, issuer } })).json.token
async function machineToken(clientId, scope) {
  const basic = Buffer.from(`${clientId}:not-a-real-secret`).toString('base64')
  const response = await fetch(`${STUB}/token`, { method: 'POST', headers: { Authorization: `Basic ${basic}`, 'Content-Type': 'application/x-www-form-urlencoded' }, body: `grant_type=client_credentials&scope=${encodeURIComponent(scope)}` })
  return (await response.json()).access_token
}
const acctSql = (sql) => docker('exec', 'e2e-account-pg', 'psql', '-U', 'postgres', '-d', 'accounts', '-tAc', sql)
const alertSql = (sql) => docker('exec', 'e2e-alert-pg', 'psql', '-U', 'event_alert', '-d', 'event_alert', '-tAc', sql)

function openssl(...args) {
  for (const exe of ['openssl', 'C:/Program Files/Git/mingw64/bin/openssl.exe']) {
    try { return execFileSync(exe, args, { stdio: 'ignore' }) } catch (error) { if (error.code !== 'ENOENT') throw error }
  }
  throw new Error('openssl is required to make the throw-away test certificate')
}

function startJar(mode) {
  const env = {
    ...process.env, ALERT_MODE: mode, SERVER_PORT: '18080',
    EVENT_ALERT_DB_URL: 'jdbc:postgresql://localhost:55432/event_alert', EVENT_ALERT_DB_USERNAME: 'event_alert', EVENT_ALERT_DB_PASSWORD: 'e2e',
    COGNITO_ISSUER_URI: STUB, COGNITO_APP_CLIENT_ID: 'local-app-client', ALERT_HOLDINGS_MODE: 'database',
    ALERT_CRM_ACCOUNT_DIRECTORY_URL: `${ACCOUNT_SVC}/internal/alerts/accounts/lookup`,
    ALERT_CRM_TOKEN_ENDPOINT: `${STUB}/token`, ALERT_CRM_CLIENT_ID: 'alert-directory',
    ALERT_CRM_CLIENT_SECRET: 'not-a-real-secret', ALERT_CRM_SCOPE: 'crm-accounts/alert-directory',
  }
  const child = spawn('java', ['-jar', jar], { env, stdio: ['ignore', 'pipe', 'pipe'] })
  let log = ''
  child.stdout.on('data', (d) => { log += d })
  child.stderr.on('data', (d) => { log += d })
  return { child, log: () => log }
}
async function waitFor(fn, tries = 90) {
  for (let i = 0; i < tries; i += 1) { if (await fn().catch(() => false)) return true; await sleep(1000) }
  return false
}
const healthy = (app) => async () => app.child.exitCode === null && (await fetch(`${API}/actuator/health`)).ok
const stop = (app) => new Promise((resolve) => { app.child.once('exit', resolve); killTree(app.child) })
function cleanup() { for (const name of NAMES) tryDocker('rm', '-f', name); tryDocker('network', 'rm', NET) }

const portfolio = {
  expectedRevision: 0, sourceLabel: 'e2e-entry',
  positions: [{ instrumentId: 'SYN-SGX-001', quantity: 10, unitPrice: 1.5, valuationCurrency: 'SGD', fxToSgd: 1, valuedAt: new Date(Date.now() - 3600_000).toISOString() }],
}

let api = null
const tls = fs.mkdtempSync(path.join(os.tmpdir(), 'alert-e2e-tls-'))
try {
  if (await fetch(`${API}/actuator/health`).then(() => true, () => false)) throw new Error('port 18080 is in use; stop the leftover event-alert-service process')
  cleanup()
  openssl('req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', path.join(tls, 'k.pem'), '-out', path.join(tls, 'c.pem'), '-days', '1', '-subj', `/CN=${HOST}`, '-addext', `subjectAltName=DNS:${HOST}`)
  docker('network', 'create', NET)

  docker('run', '-d', '--name', 'e2e-account-pg', '--network', NET, '-e', 'POSTGRES_USER=postgres', '-e', 'POSTGRES_PASSWORD=postgres', '-e', 'POSTGRES_DB=accounts', 'postgres:16')
  docker('run', '-d', '--name', 'e2e-alert-pg', '-e', 'POSTGRES_USER=event_alert', '-e', 'POSTGRES_PASSWORD=e2e', '-e', 'POSTGRES_DB=event_alert', '-p', '55432:5432', 'postgres:16-alpine')
  check('PostgreSQL for account-service starts', await waitFor(async () => acctSql('select 1') === '1', 40))
  execFileSync('docker', ['exec', '-i', 'e2e-account-pg', 'psql', '-U', 'postgres', '-d', 'accounts'], { input: fs.readFileSync(path.join(root, 'services/account-service/data/V1_init.sql')), stdio: ['pipe', 'ignore', 'ignore'] })

  docker('run', '-d', '--name', 'e2e-stub', '--network', NET, '--network-alias', HOST, '-p', '9099:9099',
    '-v', `${path.join(root, 'scripts/local-crm-stub')}:/stub:ro`, '-v', `${tls}:/tls:ro`,
    '-e', `MACHINE_ISSUER=${ISSUER}`, '-e', 'STUB_HOST=0.0.0.0', '-e', 'TLS_KEY=/tls/k.pem', '-e', 'TLS_CERT=/tls/c.pem', '-e', 'HTTPS_PORT=443',
    'node:24-alpine', 'node', '/stub/stub-crm.mjs', '9099')
  check('Cognito stand-in starts', await waitFor(async () => (await fetch(`${STUB}/.well-known/jwks.json`)).ok, 30))

  docker('build', '-q', '-t', 'alert-e2e-account', path.join(root, 'services/account-service'))
  docker('run', '-d', '--name', 'e2e-account', '--network', NET, '-p', '18083:8083', '-v', `${tls}:/tls:ro`,
    '-e', 'SSL_CERT_FILE=/tls/c.pem', '-e', 'APP_ENV=local', '-e', 'DB_HOST=e2e-account-pg', '-e', 'DB_PORT=5432', '-e', 'DB_USER=postgres', '-e', 'DB_PASSWORD=postgres', '-e', 'DB_NAME=accounts',
    '-e', `COGNITO_ISSUER_URI=${ISSUER}`, '-e', 'COGNITO_APP_CLIENT_ID=local-app-client',
    '-e', 'COGNITO_SERVICE_CLIENT_IDS=import-client', '-e', 'COGNITO_ALERT_DIRECTORY_CLIENT_IDS=alert-directory',
    '-e', 'CLIENT_SERVICE_BASE_URL=http://nowhere.invalid:8080', '-e', 'AUDIT_QUEUE_URL=http://nowhere.invalid/queue', '-e', 'AWS_REGION=ap-southeast-1',
    '-e', 'AWS_ENDPOINT_URL=http://nowhere.invalid:4566', '-e', 'AWS_ACCESS_KEY_ID=x', '-e', 'AWS_SECRET_ACCESS_KEY=y',
    'alert-e2e-account')
  const accountUp = async () => (await fetch(`${ACCOUNT_SVC}/health`)).ok
  check('the real account-service starts', await waitFor(accountUp, 60), tryDocker('logs', '--tail', '15', 'e2e-account'))

  acctSql(`INSERT INTO accounts (id, client_id, owner_agent_id, account_type, status, branch_id) VALUES
    ('${INVEST}', '${CLIENT}', 'agent-a', 'investment', 'active', 'branch-1'),
    ('${SAVINGS}', '${CLIENT}', 'agent-a', 'savings', 'active', 'branch-1')`)

  // --- the new route and who may call it
  const lookup = '/internal/alerts/accounts/lookup'
  const alertToken = await machineToken('alert-directory', 'crm-accounts/alert-directory')
  const importToken = await machineToken('import-client', 'crm-accounts/lookup')
  const wrongScope = await machineToken('alert-directory', 'crm-accounts/lookup')
  const userToken = await mint('agent-a', ['agent'], ISSUER)
  const ask = (token, ids = [INVEST]) => call(`${ACCOUNT_SVC}${lookup}`, { method: 'POST', token, body: { ids } })

  const ok = await ask(alertToken, [INVEST, SAVINGS, 'a0000000-0000-4000-8000-0000000000ff'])
  check('the alert service token reads accounts, with the current owner, and unknown ids are left out',
    ok.status === 200 && ok.json.items.length === 2 && ok.json.items.some((i) => i.id === INVEST && i.type === 'investment' && i.status === 'active' && i.owner_agent_id === 'agent-a' && i.client_id === CLIENT), ok.text)
  check('no token is refused (401)', (await ask(undefined)).status === 401)
  check("the transaction import's token is refused on the alert route (403)", (await ask(importToken)).status === 403)
  check('the alert client with the wrong scope is refused (403)', (await ask(wrongScope)).status === 403)
  check('a user token is refused (403)', (await ask(userToken)).status === 403)
  check("the alert token is refused on the import's route (403)", (await call(`${ACCOUNT_SVC}/internal/accounts/lookup`, { method: 'POST', token: alertToken, body: { ids: [INVEST] } })).status === 403)
  check('more than 200 distinct ids is refused (400)', (await ask(alertToken, Array.from({ length: 201 }, (_, i) => `a0000000-0000-4000-8000-${String(i).padStart(12, '0')}`))).status === 400)

  // --- the alert service against it
  // The alert API trusts the plain-HTTP issuer; the Go services trust the https cognito-idp one.
  const [agentA, agentB, admin] = [await mint('agent-a', ['agent'], STUB), await mint('agent-b', ['agent'], STUB), await mint('admin-1', ['admin'], STUB)]
  api = startJar('API')
  check('the alert service starts in database mode against the real account-service', await waitFor(healthy(api)), api.log().slice(-500))
  const url = `${API}/api/alert-portfolios/${INVEST}`
  const put = (token, key, extra = {}, account = INVEST) => call(`${API}/api/alert-portfolios/${account}`, { method: 'PUT', token, headers: { 'Idempotency-Key': key }, body: { ...portfolio, ...extra } })

  check('nothing recorded yet is 404', (await call(url, { token: agentA })).status === 404)
  const first = await put(agentA, 'e2e-key-00000001')
  check("the owning agent records a portfolio on the real account (revision 1)", first.status === 200 && first.json.revision === 1, first.text)
  check('another agent cannot see or write it (404)', (await call(url, { token: agentB })).status === 404 && (await put(agentB, 'e2e-key-00000002', { expectedRevision: 1 })).status === 404)
  check('admin can read (200) but not write (403)', (await call(url, { token: admin })).status === 200 && (await put(admin, 'e2e-key-00000003', { expectedRevision: 1 })).status === 403)
  check('a savings account cannot hold positions (409)', (await put(agentA, 'e2e-key-00000004', {}, SAVINGS)).status === 409)
  check('an account the CRM does not know is 404', (await put(agentA, 'e2e-key-00000005', {}, 'a0000000-0000-4000-8000-0000000000fe')).status === 404)

  await stop(api)
  api = startJar('API')
  check('the alert service restarts and the portfolio survives', (await waitFor(healthy(api))) && (await call(url, { token: agentA })).json?.revision === 1)

  // --- reassignment through the real CRM route (the call client-service makes, ADR-0060)
  const adminOnIssuer = await mint('admin-1', ['admin'], ISSUER)
  const moved = await call(`${ACCOUNT_SVC}/ownership/accounts`, { method: 'PUT', token: adminOnIssuer, body: { client_ids: [CLIENT], owner_agent_id: 'agent-b' } })
  check('the real account-service moves the client\'s accounts to agent-b', moved.status === 200 && moved.json.accounts === 2, moved.text)
  check('after reassignment the old agent is refused and the new agent admitted',
    (await call(url, { token: agentA })).status === 404 && (await call(url, { token: agentB })).status === 200)

  // --- account state in the CRM
  acctSql(`UPDATE accounts SET status = 'closed' WHERE id = '${INVEST}'`)
  check('a closed account can no longer take new holdings (409)', (await put(agentB, 'e2e-key-00000006', { expectedRevision: 1 })).status === 409)
  acctSql(`UPDATE accounts SET status = 'active' WHERE id = '${INVEST}'`)
  acctSql(`UPDATE accounts SET deleted_at = now() WHERE id = '${INVEST}'`)
  check('a deleted account looks missing (404)', (await call(url, { token: agentB })).status === 404)
  acctSql(`UPDATE accounts SET deleted_at = NULL WHERE id = '${INVEST}'`)

  // --- outage
  docker('stop', 'e2e-account')
  check('an unreachable account-service fails closed (503), not empty', (await call(url, { token: agentB })).status === 503)
  docker('start', 'e2e-account')
  check('and recovers when it returns', (await waitFor(accountUp, 60)) && (await call(url, { token: agentB })).status === 200)

  check('one audit event was written for the one real change', alertSql("SELECT string_agg(event_type, ',') FROM audit_outbox WHERE event_type LIKE 'alert.portfolio%'") === 'alert.portfolio-replaced')

  await stop(api)
  api = startJar('WORKER')
  const exit = await new Promise((resolve) => { api.child.once('exit', resolve); setTimeout(() => resolve('timeout'), 90_000) })
  check('the worker runs once in database mode and exits cleanly with outbound calls off', exit === 0, `${exit}\n${api.log().slice(-500)}`)
} finally {
  if (api) killTree(api.child)
  cleanup()
  fs.rmSync(tls, { recursive: true, force: true })
}
console.log(failures === 0 ? '\nAll checks passed.' : `\n${failures} check(s) failed.`)
process.exit(failures === 0 ? 0 : 1)
