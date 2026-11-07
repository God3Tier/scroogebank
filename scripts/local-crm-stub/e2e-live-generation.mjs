// LIVE alert generation: the worker runs once against the real Tavily search API with the maintained
// portfolios from the real account-service. The model stays off (rule fallback). The key is read from
// the TAVILY_API_KEY environment variable and is never written to disk or logged. Uses a few search credits.
//   TAVILY_API_KEY=... node scripts/local-crm-stub/e2e-live-generation.mjs
// (copy of the setup in e2e-real-account.mjs: Database holdings mode against the REAL account-service (its Docker image and PostgreSQL, the real
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
const DB_PORT = process.env.ALERT_E2E_DB_PORT || '55432'
const CLIENT = 'c0000000-0000-4000-8000-000000000001'
const INVEST = 'a0000000-0000-4000-8000-000000000001'
const SAVINGS = 'a0000000-0000-4000-8000-000000000002'
if (!process.env.TAVILY_API_KEY) throw new Error('set TAVILY_API_KEY in the environment')
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

function startJar(mode, extra = {}) {
  const env = {
    ...process.env, ...extra, ALERT_MODE: mode, SERVER_PORT: '18080',
    EVENT_ALERT_DB_URL: `jdbc:postgresql://localhost:${DB_PORT}/event_alert`, EVENT_ALERT_DB_USERNAME: 'event_alert', EVENT_ALERT_DB_PASSWORD: 'e2e',
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
  docker('run', '-d', '--name', 'e2e-alert-pg', '-e', 'POSTGRES_USER=event_alert', '-e', 'POSTGRES_PASSWORD=e2e', '-e', 'POSTGRES_DB=event_alert', '-p', `${DB_PORT}:5432`, 'postgres:16-alpine')
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

  acctSql(`INSERT INTO accounts (id, client_id, owner_agent_id, account_type, status, branch_id) VALUES
    ('${INVEST}', '${CLIENT}', 'agent-a', 'investment', 'active', 'branch-1'),
    ('${SAVINGS}', '${CLIENT}', 'agent-a', 'savings', 'active', 'branch-1')`)

  // --- record a real portfolio through the API, then run the worker live
  const agentA = await mint('agent-a', ['agent'], ISSUER)
  api = startJar('API')
  check('the alert service starts in database mode', await waitFor(healthy(api)), api.log().slice(-500))
  const dbs = await call(`${API}/api/alert-portfolios/${INVEST}`, { method: 'PUT', token: agentA, headers: { 'Idempotency-Key': 'live-key-00000001' },
    body: { expectedRevision: 0, sourceLabel: 'live-run', positions: [{ instrumentId: 'SGX-D05', quantity: 1000, unitPrice: 35, valuationCurrency: 'SGD', fxToSgd: 1, valuedAt: new Date(Date.now() - 3600_000).toISOString() }] } })
  check('the agent records a DBS holding through the API', dbs.status === 200, dbs.text)
  await stop(api)
  // The seeded DBS row has no official domains; set them for this throw-away database only.
  alertSql(`UPDATE instrument SET official_domains = '["dbs.com", "sgx.com", "businesstimes.com.sg", "straitstimes.com", "reuters.com"]'::jsonb WHERE id = 'SGX-D05'`)

  const to = new Date(Date.now() - 60_000)
  const from = new Date(to.getTime() - 14 * 24 * 3600_000)
  const worker = startJar('WORKER', {
    ALERT_INGESTION_ENABLED: 'true', ALERT_INGESTION_INITIAL_LOOKBACK: 'PT336H', ALERT_SEARCH_PROVIDER: 'tavily', ALERT_TAVILY_API_KEY: process.env.TAVILY_API_KEY,
    ALERT_WORKER_REPLAY_FROM: from.toISOString(), ALERT_WORKER_REPLAY_TO: to.toISOString(),
    ALERT_WORKER_REPLAY_OPERATOR: 'e2e-operator', ALERT_WORKER_REPLAY_CORRELATION_ID: 'e2e-live-0001',
  })
  const exit = await new Promise((resolve) => { worker.child.once('exit', resolve); setTimeout(() => resolve('timeout'), 240_000) })
  const log = worker.log()
  console.log(log.split('\n').filter((line) => /worker|ingestion|search|alert|source/i.test(line)).slice(-12).join('\n').replaceAll(process.env.TAVILY_API_KEY, '<key>'))
  check('the live worker run exits cleanly', exit === 0, String(exit))
  check('the Tavily key never appears in the worker log', !log.includes(process.env.TAVILY_API_KEY))
  const counts = alertSql(`SELECT (SELECT COUNT(*) FROM search_run WHERE status='SUCCEEDED') || ',' || (SELECT COUNT(*) FROM source_result) || ',' || (SELECT COUNT(*) FROM public_event) || ',' || (SELECT COUNT(*) FROM alert) || ',' || (SELECT COUNT(*) FROM portfolio_snapshot)`)
  console.log(`search runs succeeded, sources, public events, alerts, portfolio snapshots: ${counts}`)
  const [runs, sources, events, alerts] = counts.split(',').map(Number)
  check('a real search run succeeded against Tavily', runs >= 1, counts)
  // Live results depend on the news of the day and the service's own relevance filter, so this is reported, not asserted.
  console.log(`accepted sources: ${sources}; the run log above shows accepted/rejected counts. Alert generation needs the model, which stays off here (the pipeline skips with MODEL_DISABLED).`)
  if (alerts >= 1) {
    api = startJar('API')
    await waitFor(healthy(api))
    const cards = await call(`${API}/api/alerts/events`, { token: agentA })
    check('the owning agent sees the generated event briefing', cards.status === 200 && cards.json.items.length >= 1, cards.text)
    const detail = cards.json.items[0] && await call(`${API}/api/alerts/events/${cards.json.items[0].eventId}`, { token: agentA })
    check('its drill-down shows the client with the whole-account exposure', detail?.status === 200 && detail.json.clients.length >= 1, detail?.text)
    console.log('exposure shown:', JSON.stringify(detail?.json?.clients?.[0]?.portfolioPercentage))
  } else {
    console.log(`NOTE: ${sources} source(s) and ${events} public event(s) but no alert; the rule fallback (model off) may not classify these.`)
  }
} finally {
  if (api) killTree(api.child)
  cleanup()
  fs.rmSync(tls, { recursive: true, force: true })
}
console.log(failures === 0 ? '\nAll checks passed.' : `\n${failures} check(s) failed.`)
process.exit(failures === 0 ? 0 : 1)
