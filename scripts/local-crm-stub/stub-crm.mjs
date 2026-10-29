// Local stand-in for Cognito, and for the CRM account directory when the real account-service is
// not running, for trying the alert service in ALERT_HOLDINGS_MODE=database. Synthetic data only.
// Never run this anywhere but a developer machine.
//
//   node scripts/local-crm-stub/stub-crm.mjs [port]
//     TLS_KEY / TLS_CERT / HTTPS_PORT   also serve HTTPS (the real Go services insist on an
//                                       https://cognito-idp.*.amazonaws.com issuer)
//     MACHINE_ISSUER                    issuer put in machine and minted tokens when none is given
//
// Serves   GET  /.well-known/jwks.json        signing keys for the tokens it mints
//          POST /token                         client-credentials token. The app client is the HTTP Basic
//                                              user and the scope the form's `scope` field
//          POST /accounts                      the account directory contract (`{ids}` -> `{items}`)
// Controls POST /_mint {sub, groups, issuer}   a user access token
//          POST /_set  {accounts}              replace the stub's accounts (reassign by changing owner_agent_id)
//          POST /_down {down: true|false}      make the stub's directory answer 503
import crypto from 'node:crypto'
import fs from 'node:fs'
import http from 'node:http'
import https from 'node:https'

const port = Number(process.argv[2] || 9099)
const defaultIssuer = process.env.MACHINE_ISSUER || `http://127.0.0.1:${port}`
const appClientId = 'local-app-client'
const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 })
const jwk = { ...publicKey.export({ format: 'jwk' }), kid: 'local-1', use: 'sig', alg: 'RS256' }

let state = { accounts: [], down: false }

const b64 = (value) => Buffer.from(typeof value === 'string' ? value : JSON.stringify(value)).toString('base64url')

function sign(claims) {
  const head = b64({ alg: 'RS256', kid: 'local-1', typ: 'JWT' })
  const now = Math.floor(Date.now() / 1000)
  const body = b64({ iss: defaultIssuer, iat: now, exp: now + 3600, ...claims })
  const signature = crypto.sign('RSA-SHA256', Buffer.from(`${head}.${body}`), privateKey).toString('base64url')
  return `${head}.${body}.${signature}`
}

function readBody(request) {
  return new Promise((resolve) => {
    let data = ''
    request.on('data', (chunk) => { data += chunk })
    request.on('end', () => resolve(data))
  })
}

function send(response, status, body) {
  response.writeHead(status, { 'Content-Type': 'application/json' })
  response.end(JSON.stringify(body))
}

async function handle(request, response) {
  const path = new URL(request.url, 'http://stub').pathname
  if (path === '/.well-known/jwks.json' || path.endsWith('/.well-known/jwks.json')) return send(response, 200, { keys: [jwk] })
  const raw = request.method === 'POST' ? await readBody(request) : ''
  if (path === '/token') {
    const basic = (request.headers.authorization || '').replace(/^Basic /i, '')
    const clientId = Buffer.from(basic, 'base64').toString().split(':')[0] || 'alert-directory'
    const scope = new URLSearchParams(raw).get('scope') || ''
    return send(response, 200, { access_token: sign({ sub: 'machine', client_id: clientId, token_use: 'access', scope }), expires_in: 3600 })
  }
  let body = {}
  try { body = JSON.parse(raw || '{}') } catch { /* not JSON */ }
  if (path === '/_mint') {
    return send(response, 200, { token: sign({ ...(body.issuer ? { iss: body.issuer } : {}), sub: body.sub, client_id: appClientId, token_use: 'access', 'cognito:groups': body.groups || ['agent'] }) })
  }
  if (path === '/_set') { state = { ...state, accounts: body.accounts || [] }; return send(response, 200, { ok: true }) }
  if (path === '/_down') { state.down = Boolean(body.down); return send(response, 200, { ok: true }) }
  if (path === '/accounts') {
    if (state.down) return send(response, 503, { error: 'down' })
    const wanted = new Set(body.ids || [])
    return send(response, 200, { items: state.accounts.filter((item) => wanted.has(item.id)) })
  }
  return send(response, 404, { error: 'not found' })
}

const host = process.env.STUB_HOST || '127.0.0.1'
http.createServer(handle).listen(port, host, () => console.log(`stub listening on http://${host}:${port} (app client ${appClientId})`))
if (process.env.TLS_KEY && process.env.TLS_CERT) {
  const httpsPort = Number(process.env.HTTPS_PORT || 443)
  https.createServer({ key: fs.readFileSync(process.env.TLS_KEY), cert: fs.readFileSync(process.env.TLS_CERT) }, handle)
    .listen(httpsPort, host, () => console.log(`stub listening on https port ${httpsPort}`))
}
