// Stand-in for user-service's GET /api/users/{id}, which client-service asks before reassigning clients.
// Synthetic users only; developer machines only.
//   node scripts/local-crm-stub/stub-user-service.mjs [port]
import http from 'node:http'

const users = {
  '00000000-0000-4000-8000-0000000000a0': { idpSub: 'agent-a', role: 'AGENT', enabled: true },
  '00000000-0000-4000-8000-0000000000b0': { idpSub: 'agent-b', role: 'AGENT', enabled: true },
}
http.createServer((request, response) => {
  const id = decodeURIComponent(new URL(request.url, 'http://stub').pathname.replace('/api/users/', ''))
  if (!request.headers.authorization) { response.writeHead(401); return response.end() }
  const user = users[id]
  response.writeHead(user ? 200 : 404, { 'Content-Type': 'application/json' })
  response.end(JSON.stringify(user ?? {}))
}).listen(Number(process.argv[2] || 9100), '0.0.0.0')
