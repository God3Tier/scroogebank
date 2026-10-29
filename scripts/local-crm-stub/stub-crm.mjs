// Local stand-in for the CRM directory and Cognito, for trying the alert service in
// ALERT_HOLDINGS_MODE=database before the real CRM directory routes exist (CH-01).
// Synthetic data only. Never run this anywhere but a developer machine.
//
//   node scripts/local-crm-stub/stub-crm.mjs [port]
//
// Serves   GET  /.well-known/jwks.json        signing keys for the tokens it mints
//          POST /token                         machine token (client credentials)
//          POST /accounts, POST /clients       the directory contract (`{ids}` -> `{items}`)
// Controls POST /_mint {sub, groups}           a user access token for the alert API
//          POST /_set  {accounts, clients}     replace the CRM records (e.g. reassign a client)
//          POST /_down {down: true|false}      make the directory answer 503
import crypto from 'node:crypto'
import http from 'node:http'

const port = Number(process.argv[2] || 9099)
const issuer = `http://127.0.0.1:${port}`
const appClientId = 'local-app-client'
const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 })
const jwk = { ...publicKey.export({ format: 'jwk' }), kid: 'local-1', use: 'sig', alg: 'RS256' }

let state = { accounts: [], clients: [], down: false }

const b64 = (value) => Buffer.from(typeof value === 'string' ? value : JSON.stringify(value)).toString('base64url')

function sign(claims) {
  const head = b64({ alg: 'RS256', kid: 'local-1', typ: 'JWT' })
  const body = b64({ iss: issuer, iat: Math.floor(Date.now() / 1000), exp: Math.floor(Date.now() / 1000) + 3600, ...claims })
  const signature = crypto.sign('RSA-SHA256', Buffer.from(`${head}.${body}`), privateKey).toString('base64url')
  return `${head}.${body}.${signature}`
}

function readJson(request) {
  return new Promise((resolve) => {
    let data = ''
    request.on('data', (chunk) => { data += chunk })
    request.on('end', () => { try { resolve(JSON.parse(data || '{}')) } catch { resolve({}) } })
  })
}

function send(response, status, body) {
  response.writeHead(status, { 'Content-Type': 'application/json' })
  response.end(JSON.stringify(body))
}

http.createServer(async (request, response) => {
  const path = new URL(request.url, issuer).pathname
  if (path === '/.well-known/jwks.json') return send(response, 200, { keys: [jwk] })
  const body = request.method === 'POST' ? await readJson(request) : {}
  if (path === '/token') {
    return send(response, 200, { access_token: sign({ sub: 'machine', client_id: 'alert-directory', token_use: 'access', scope: 'crm-alerts/directory-read' }), expires_in: 3600 })
  }
  if (path === '/_mint') {
    return send(response, 200, { token: sign({ sub: body.sub, client_id: appClientId, token_use: 'access', 'cognito:groups': body.groups || ['agent'] }) })
  }
  if (path === '/_set') { state = { ...state, accounts: body.accounts || [], clients: body.clients || [] }; return send(response, 200, { ok: true }) }
  if (path === '/_down') { state.down = Boolean(body.down); return send(response, 200, { ok: true }) }
  if (path === '/accounts' || path === '/clients') {
    if (state.down) return send(response, 503, { error: 'down' })
    const wanted = new Set(body.ids || [])
    return send(response, 200, { items: (path === '/accounts' ? state.accounts : state.clients).filter((item) => wanted.has(item.id)) })
  }
  return send(response, 404, { error: 'not found' })
}).listen(port, '127.0.0.1', () => console.log(`stub CRM listening on ${issuer} (app client ${appClientId})`))
