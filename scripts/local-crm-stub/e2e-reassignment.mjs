// REAL client-service reassignment (ADR-0060) -> REAL account-service -> alert service visibility.
// Only Cognito and user-service are stand-ins. Synthetic data only.
//   node scripts/local-crm-stub/e2e-reassignment.mjs   # needs Docker; ports 9099, 18080, 18083, 18084, 55432 free
// (setup copied from e2e-real-account.mjs)
// Database holdings mode against the REAL account-service (its Docker image and PostgreSQL, the real
// /internal/alerts/accounts/lookup route, scope and client allow-list, and the real ownership
// route). Only Cognito is a stand-in: stub-crm.mjs mints the tokens and serves the keys over HTTPS under
// the cognito-idp hostname the Go service insists on. Synthetic data only.
//
//   mvn -pl services/event-alert-service -DskipTests package
//   node scripts/local-crm-stub/e2e-real-account.mjs        # needs Docker; ports 9099, 18080, 18083, 55432 free
import { execFileSync, spawn, spawnSync } from 'node:child_process'
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
const CLIENT_SVC = 'http://127.0.0.1:18084'
const CLIENT = 'c0000000-0000-4000-8000-000000000001'
const INVEST = 'a0000000-0000-4000-8000-000000000001'
const SAVINGS = 'a0000000-0000-4000-8000-000000000002'
const NAMES = ['e2e-stub', 'e2e-account', 'e2e-account-pg', 'e2e-alert-pg', 'e2e-client', 'e2e-client-pg', 'e2e-users']

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

// The alert API must accept the same user tokens the Go services do, because it forwards the agent's own
// token to account-service. So it trusts the https cognito-idp issuer too: a throw-away truststore, and a
// hosts-file override that points that name at this machine, where the stand-in publishes port 443.
function keytoolExe() {
  const settings = spawnSync('java', ['-XshowSettings:properties', '-version'], { encoding: 'utf8' })
  const home = /java\.home = (.+)/.exec(`${settings.stderr}${settings.stdout}`)?.[1]?.trim()
  return home ? path.join(home, 'bin', process.platform === 'win32' ? 'keytool.exe' : 'keytool') : 'keytool'
}
function javaTlsOptions() {
  const hosts = path.join(tls, 'hosts.txt')
  fs.writeFileSync(hosts, `127.0.0.1 ${HOST}
`)
  const store = path.join(tls, 'trust.jks')
  if (!fs.existsSync(store)) execFileSync(keytoolExe(), ['-importcert', '-alias', 'stub', '-file', path.join(tls, 'c.pem'), '-keystore', store, '-storepass', 'changeit', '-noprompt'], { stdio: 'ignore' })
  const slash = (p) => p.replaceAll('\\', '/')
  return `-Djdk.net.hosts.file=${slash(hosts)} -Djavax.net.ssl.trustStore=${slash(store)} -Djavax.net.ssl.trustStorePassword=changeit`
}

function startJar(mode) {
  const env = {
    ...process.env, ALERT_MODE: mode, SERVER_PORT: '18080',
    EVENT_ALERT_DB_URL: 'jdbc:postgresql://localhost:55432/event_alert', EVENT_ALERT_DB_USERNAME: 'event_alert', EVENT_ALERT_DB_PASSWORD: 'e2e',
    COGNITO_ISSUER_URI: ISSUER, JAVA_TOOL_OPTIONS: mode === 'API' ? javaTlsOptions() : '', COGNITO_APP_CLIENT_ID: 'local-app-client', ALERT_HOLDINGS_MODE: 'database',
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

  docker('run', '-d', '--name', 'e2e-stub', '--network', NET, '--network-alias', HOST, '-p', '9099:9099', '-p', '443:443',
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


  // --- the real client-service, with its own database, beside the real account-service
  docker('run', '-d', '--name', 'e2e-client-pg', '--network', NET, '-e', 'POSTGRES_USER=postgres', '-e', 'POSTGRES_PASSWORD=postgres', '-e', 'POSTGRES_DB=clients', 'postgres:16')
  const clientSql = (sql) => docker('exec', 'e2e-client-pg', 'psql', '-U', 'postgres', '-d', 'clients', '-tAc', sql)
  check('PostgreSQL for client-service starts', await waitFor(async () => clientSql('select 1') === '1', 40))
  execFileSync('docker', ['exec', '-i', 'e2e-client-pg', 'psql', '-U', 'postgres', '-d', 'clients'], { input: fs.readFileSync(path.join(root, 'services/client-profile-management-service/data/V1_init.sql')), stdio: ['pipe', 'ignore', 'ignore'] })
  docker('run', '-d', '--name', 'e2e-users', '--network', NET, '-v', `${path.join(root, 'scripts/local-crm-stub')}:/stub:ro`, 'node:24-alpine', 'node', '/stub/stub-user-service.mjs', '9100')
  docker('build', '-q', '-t', 'alert-e2e-client', path.join(root, 'services/client-profile-management-service'))
  docker('run', '-d', '--name', 'e2e-client', '--network', NET, '-p', '18084:8080', '-v', `${tls}:/tls:ro`,
    '-e', 'SSL_CERT_FILE=/tls/c.pem', '-e', 'APP_ENV=local', '-e', 'DB_HOST=e2e-client-pg', '-e', 'DB_PORT=5432', '-e', 'DB_USER=postgres', '-e', 'DB_PASSWORD=postgres', '-e', 'DB_NAME=clients',
    '-e', `COGNITO_ISSUER_URI=${ISSUER}`, '-e', 'COGNITO_APP_CLIENT_ID=local-app-client',
    '-e', 'ACCOUNT_SERVICE_BASE_URL=http://e2e-account:8083', '-e', 'USER_SERVICE_BASE_URL=http://e2e-users:9100',
    '-e', 'AUDIT_QUEUE_URL=http://nowhere.invalid/queue', '-e', 'AWS_REGION=ap-southeast-1', '-e', 'AWS_ENDPOINT_URL=http://nowhere.invalid:4566',
    '-e', 'AWS_ACCESS_KEY_ID=x', '-e', 'AWS_SECRET_ACCESS_KEY=y',
    '-e', 'CLIENT_KEYS_TABLE=client-keys', '-e', 'CLIENT_DATA_KEY_ID=alias/client-data', '-e', 'CLIENT_INDEX_KEY_ID=alias/client-blind-index', '-e', 'CLIENT_DOCUMENTS_BUCKET=client-documents',
    'alert-e2e-client')
  const clientUp = async () => (await fetch(`${CLIENT_SVC}/health`)).ok
  check('the real client-service starts', await waitFor(clientUp, 60), tryDocker('logs', '--tail', '15', 'e2e-client'))

  clientSql(`INSERT INTO clients (client_id, first_name, last_name, date_of_birth, gender, email, email_hash, phone_number, phone_number_hash, address, city, state, country, postal_code, assigned_agent_id, created_at, updated_at)
    VALUES ('${CLIENT}', 'x', 'x', 'x', 'x', 'x', 'h-email', 'x', 'h-phone', 'x', 'x', 'x', 'x', 'x', 'agent-a', now(), now())`)
  acctSql(`INSERT INTO accounts (id, client_id, owner_agent_id, account_type, status, branch_id) VALUES
    ('${INVEST}', '${CLIENT}', 'agent-a', 'investment', 'active', 'branch-1'),
    ('${SAVINGS}', '${CLIENT}', 'agent-a', 'savings', 'active', 'branch-1')`)

  // --- the alert service: agent-a records holdings
  const [agentA, agentB] = [await mint('agent-a', ['agent'], ISSUER), await mint('agent-b', ['agent'], ISSUER)]
  const adminOnIssuer = await mint('admin-1', ['admin'], ISSUER)
  api = startJar('API')
  check('the alert service starts in database mode', await waitFor(healthy(api)), api.log().slice(-500))
  const url = `${API}/api/alert-portfolios/${INVEST}`
  const recorded = await call(url, { method: 'PUT', token: agentA, headers: { 'Idempotency-Key': 'reassign-key-0001' }, body: portfolio })
  check("agent-a records a portfolio on the client's investment account", recorded.status === 200, recorded.text)
  check('agent-a can read it and agent-b cannot', (await call(url, { token: agentA })).status === 200 && (await call(url, { token: agentB })).status === 404)

  // --- an admin reassigns through the REAL client-service
  const AGENT_B_USER = '00000000-0000-4000-8000-0000000000b0'
  const moved = await call(`${CLIENT_SVC}/api/clients/reassignments`, { method: 'POST', token: adminOnIssuer, body: { to_user_id: AGENT_B_USER, client_ids: [CLIENT] } })
  check('client-service reassigns the client (200, 1 moved)', moved.status === 200 && moved.json?.reassigned === 1, `${moved.status} ${moved.text}`)
  check('client-service recorded the new agent', clientSql(`SELECT assigned_agent_id FROM clients WHERE client_id = '${CLIENT}'`) === 'agent-b')
  check('account-service moved both accounts in the same step', acctSql(`SELECT string_agg(DISTINCT owner_agent_id, ',') FROM accounts WHERE client_id = '${CLIENT}'`) === 'agent-b')
  check('the alert service now admits agent-b and refuses agent-a, with no change on its side',
    (await call(url, { token: agentB })).status === 200 && (await call(url, { token: agentA })).status === 404)
  check('agent-a can no longer replace the portfolio (404)', (await call(url, { method: 'PUT', token: agentA, headers: { 'Idempotency-Key': 'reassign-key-0002' }, body: { ...portfolio, expectedRevision: 1 } })).status === 404)
  const afterB = await call(url, { method: 'PUT', token: agentB, headers: { 'Idempotency-Key': 'reassign-key-0003' }, body: { ...portfolio, expectedRevision: 1 } })
  check('agent-b can replace it (revision 2)', afterB.status === 200 && afterB.json.revision === 2, afterB.text)

  // --- a refused reassignment moves nothing anywhere
  const refused = await call(`${CLIENT_SVC}/api/clients/reassignments`, { method: 'POST', token: adminOnIssuer, body: { to_user_id: '00000000-0000-4000-8000-0000000000ff', client_ids: [CLIENT] } })
  check('an unknown receiving user is refused (404) and nothing moves', refused.status === 404 && acctSql(`SELECT string_agg(DISTINCT owner_agent_id, ',') FROM accounts WHERE client_id = '${CLIENT}'`) === 'agent-b', `${refused.status} ${refused.text}`)
} finally {
  if (api) killTree(api.child)
  cleanup()
  fs.rmSync(tls, { recursive: true, force: true })
}
console.log(failures === 0 ? '\nAll checks passed.' : `\n${failures} check(s) failed.`)
process.exit(failures === 0 ? 0 : 1)
