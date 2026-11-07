// TEMPORARY local preview of the whole app with made-up data. A switch at the bottom-right
// changes between a fake Agent and a fake Admin. CRM pages (clients, accounts, holdings) are
// mocked in memory; nothing here talks to a real service. Not committed; deleted after the check.
import { useMemo, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { AuthContext } from 'react-oidc-context'
import { MemoryRouter } from 'react-router-dom'
import '@fontsource-variable/inter'
import '@fontsource-variable/material-symbols-outlined'
import './styles/index.css'
import './styles/dashboard.css'
import './styles/engagement.css'

import App from './App.jsx'
import NavBar from './components/NavBar.jsx'
import { syntheticAlerts, syntheticDetail } from './data/alerts.js'

// ---- made-up data (in memory; a reload resets it) -----------------------------------
const BLOCKED = ['reddit.com', 'x.com', 'facebook.com', 'youtube.com', 'medium.com']
let seq = 100
const nextId = (prefix) => `${prefix}${seq++}`

const companies = [
  { instrumentId: 'SGX-D05', ticker: 'D05', issuerName: 'DBS Group Holdings Ltd',
    official: ['sgx.com', 'dbs.com'], shared: ['reuters.com', 'cnbc.com', 'ft.com'],
    custom: [
      { id: 'd1', domain: 'businesstimes.com.sg', addedAt: '2026-11-04T04:00:00Z', mine: true },
      { id: 'd2', domain: 'theedgesingapore.com', addedAt: '2026-11-03T04:00:00Z', mine: false },
    ] },
  { instrumentId: 'SGX-Z74', ticker: 'Z74', issuerName: 'Singapore Telecommunications Ltd',
    official: ['singtel.com'], shared: ['reuters.com', 'cnbc.com', 'ft.com'], custom: [] },
]
const clients = [
  { client_id: 'c1', first_name: 'Alice', last_name: 'Tan', email: 'alice.tan@example.test', gender: 'female', date_of_birth: '1990-02-01', phone_number: '+6581110001', address: '1 Example Road', city: 'Singapore', state: 'Singapore', country: 'Singapore', postal_code: '123456', verification_status: 'VERIFIED' },
  { client_id: 'c2', first_name: 'Ben', last_name: 'Lim', email: 'ben.lim@example.test', gender: 'male', date_of_birth: '1985-07-19', phone_number: '+6581110002', address: '2 Example Road', city: 'Singapore', state: 'Singapore', country: 'Singapore', postal_code: '234567', verification_status: 'NOT_VERIFIED' },
  { client_id: 'c3', first_name: 'Chloe', last_name: 'Ng', email: 'chloe.ng@example.test', gender: 'female', date_of_birth: '1998-11-23', phone_number: '+6581110003', address: '3 Example Road', city: 'Singapore', state: 'Singapore', country: 'Singapore', postal_code: '345678', verification_status: 'VERIFIED' },
]
const accounts = [
  { id: '2e09c8ce-1111-4111-8111-111111111111', client_id: 'c1', account_type: 'INVESTMENT', status: 'ACTIVE', currency: 'SGD', balance: 1000, branch_id: 'b1' },
  { id: '7a1b22cd-2222-4222-8222-222222222222', client_id: 'c1', account_type: 'SAVINGS', status: 'ACTIVE', currency: 'SGD', balance: 5200, branch_id: 'b1' },
  { id: '9c3d44ef-3333-4333-8333-333333333333', client_id: 'c3', account_type: 'INVESTMENT', status: 'ACTIVE', currency: 'SGD', balance: 2500, branch_id: 'b1' },
]
const transactions = {
  '7a1b22cd-2222-4222-8222-222222222222': [
    { transaction_id: 'T-0001', account_id: '7a1b22cd-2222-4222-8222-222222222222', amount: 200, currency: 'SGD', status: 'COMPLETED', transaction_type: 'DEPOSIT', transaction_date: '2026-11-02T03:00:00Z' },
  ],
}
const portfolios = {}
const users = [
  { id: 'u1', firstName: 'Fake', lastName: 'Agent', email: 'agent@example.test', role: 'AGENT', enabled: true },
  { id: 'u2', firstName: 'Other', lastName: 'Agent', email: 'agent2@example.test', role: 'AGENT', enabled: true },
  { id: 'u3', firstName: 'Fake', lastName: 'Admin', email: 'admin@example.test', role: 'ADMIN', enabled: true },
]
const CARD = {
  eventId: 'event-1', headline: 'DBS reports higher quarterly profit (made-up)', summary: 'A made-up summary of a made-up event, for the preview only.',
  aiGenerated: true, uncertainty: 'Made-up fixture.', affectedClientCount: 1, openClientCount: 1,
  evidence: { url: 'https://example.invalid/a', sourceDomain: 'example.invalid', publishedAt: '2026-11-03T00:00:00Z', retrievedAt: '2026-11-03T01:00:00Z' },
}
const EVENT_DETAIL = {
  card: CARD,
  clients: [{ alertId: 'alert-aaaaaaaa', clientRef: 'client-11111111', instrumentId: 'SGX-D05', accountRef: 'a1111111-1111-4111-8111-111111111111', status: 'NEW', portfolioPercentage: 90, version: 0 }],
  latestAction: null,
}

// ---- the fake network ---------------------------------------------------------------
let role = 'AGENT'
const reply = (status, body) => new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const problem = (status, detail) => reply(status, { title: 'Refused', detail })
const page = (items) => ({ content: items, items, page: 0, size: 20, totalElements: items.length, totalPages: 1 })

window.fetch = async (url, options = {}) => {
  const method = options.method || 'GET'
  const path = String(url)
  const body = () => (options.body ? JSON.parse(options.body) : {})
  await new Promise((resolve) => setTimeout(resolve, 150))
  let m

  // Search sources
  if (path.startsWith('/api/alerts/search-sources')) {
    if (method === 'GET') {
      return reply(200, companies.map((c) => ({ ...c, maxCustom: 10, canAdd: role === 'AGENT',
        custom: c.custom.map((d) => ({ id: d.id, domain: d.domain, addedAt: d.addedAt, addedByYou: role === 'AGENT' && d.mine })) })))
    }
    if ((m = path.match(/search-sources\/([^/]+)\/domains$/)) && method === 'POST') {
      if (role !== 'AGENT') return problem(403, 'only an agent can add a site')
      const company = companies.find((c) => c.instrumentId === decodeURIComponent(m[1]))
      const domain = String(body().domain).trim().toLowerCase().replace(/^www\./, '')
      if (!/^([a-z0-9-]+\.)+[a-z]{2,}$/.test(domain)) return problem(400, 'enter a plain site name such as example.com')
      if (BLOCKED.includes(domain)) return problem(400, 'social, forum and blog platforms cannot be used as news sources')
      if (company.custom.length >= 10) return problem(409, 'this company already has 10 added sites')
      const taken = [...company.official, ...company.shared, ...company.custom.map((d) => d.domain)]
      if (taken.some((d) => domain === d || domain.endsWith(`.${d}`))) return problem(409, 'that site is already searched for this company')
      const entry = { id: nextId('d'), domain, addedAt: new Date().toISOString(), mine: true }
      company.custom.push(entry)
      return reply(201, { id: entry.id, domain, addedAt: entry.addedAt, addedByYou: true })
    }
    if ((m = path.match(/search-sources\/([^/]+)\/domains\/([^/]+)$/)) && method === 'DELETE') {
      const company = companies.find((c) => c.instrumentId === decodeURIComponent(m[1]))
      company.custom = company.custom.filter((d) => d.id !== decodeURIComponent(m[2]))
      return new Response(null, { status: 204 })
    }
  }

  // Alerts and briefings
  if (path.startsWith('/api/alerts/events/') && method === 'GET') return reply(200, EVENT_DETAIL)
  if (path.startsWith('/api/alerts/events')) return reply(200, { items: [CARD], nextCursor: null })
  if (/^\/api\/alerts\/[^/?]+$/.test(path) && method === 'GET') {
    const alert = syntheticAlerts.find((a) => a.id === path.split('/').pop())
    return alert ? reply(200, syntheticDetail(alert)) : reply(404, { detail: 'not found' })
  }
  if (path.startsWith('/api/alerts?') || path === '/api/alerts') return reply(200, { items: syntheticAlerts, nextCursor: null })

  // Holdings (Record holdings)
  if ((m = path.match(/^\/api\/alert-portfolios\/([^/?]+)/))) {
    const id = decodeURIComponent(m[1])
    if (method === 'PUT') {
      const positions = body().positions || []
      portfolios[id] = { revision: (portfolios[id]?.revision || 0) + 1, positions,
        totalSgd: positions.reduce((s, p) => s + Number(p.quantity) * Number(p.unitPrice) * Number(p.fxToSgd), 0) }
    }
    return reply(200, portfolios[id] || { revision: 0, positions: [], totalSgd: 0 })
  }

  // Clients
  if (path.startsWith('/api/clients/reassignments') && method === 'POST') return reply(200, {})
  if ((m = path.match(/^\/api\/clients\/([^/?]+)\/documents/))) return reply(200, [])
  if ((m = path.match(/^\/api\/clients\/([^/?]+)\/verify$/)) && method === 'POST') {
    const c = clients.find((x) => x.client_id === decodeURIComponent(m[1])); c.verification_status = 'VERIFIED'
    return reply(200, c)
  }
  if ((m = path.match(/^\/api\/clients\/([^/?]+)$/))) {
    const c = clients.find((x) => x.client_id === decodeURIComponent(m[1]))
    if (!c) return reply(404, { detail: 'not found' })
    if (method === 'PUT') Object.assign(c, body())
    return reply(200, c)
  }
  if (path.startsWith('/api/clients') && method === 'POST') {
    const c = { ...body(), client_id: nextId('c'), verification_status: 'NOT_VERIFIED' }
    clients.unshift(c)
    return reply(201, c)
  }
  if (path.startsWith('/api/clients')) return reply(200, page(clients))

  // Accounts
  if ((m = path.match(/^\/api\/accounts\/([^/?]+)\/transactions/))) return reply(200, transactions[decodeURIComponent(m[1])] || [])
  if ((m = path.match(/^\/api\/accounts\/([^/?]+)$/)) && method === 'PATCH') {
    const a = accounts.find((x) => x.id === decodeURIComponent(m[1])); a.status = body().status
    return reply(200, a)
  }
  if (path.startsWith('/api/accounts') && method === 'POST') {
    const a = { ...body(), id: `${nextId('')}-aaaa-4aaa-8aaa-aaaaaaaaaaaa`, balance: body().initial_deposit ?? 0 }
    accounts.push(a)
    return reply(201, a)
  }
  if (path.startsWith('/api/accounts')) {
    const clientId = new URL(path, 'http://x').searchParams.get('clientId')
    return reply(200, accounts.filter((a) => !clientId || a.client_id === clientId))
  }

  // Users (admin lists)
  if (path.startsWith('/api/users')) {
    const wanted = new URL(path, 'http://x').searchParams.get('role')
    return reply(200, page(users.filter((u) => !wanted || u.role === wanted)))
  }

  // Everything else is outside this preview: an empty answer, so the page shows its empty state.
  return reply(200, { content: [], items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
}

// ---- the fake sign-in and the Agent/Admin switch -----------------------------------
const noop = async () => {}
const unsub = () => () => {}

function Root() {
  const [current, setCurrent] = useState(new URLSearchParams(location.search).get('as') === 'admin' ? 'ADMIN' : 'AGENT')
  role = current
  const value = useMemo(() => {
    const user = { expired: false, profile: { sub: `fake-${current}`, email: `${current.toLowerCase()}@example.test`,
      name: `Fake ${current === 'ADMIN' ? 'Admin' : 'Agent'}`, 'cognito:groups': [current] } }
    return { isAuthenticated: true, isLoading: false, user, error: undefined, activeNavigator: undefined,
      signinRedirect: noop, signoutRedirect: noop, removeUser: noop, signinSilent: async () => user,
      events: { addUserLoaded: unsub, addUserUnloaded: unsub, addAccessTokenExpiring: unsub } }
  }, [current])
  const start = new URLSearchParams(location.search).get('start') || (current === 'ADMIN' ? '/admin' : '/agent')

  return (
    <AuthContext.Provider value={value}>
      <MemoryRouter key={current} initialEntries={[start]}>
        <div className="app-shell">
          <NavBar />
          <main className="main"><App /></main>
        </div>
      </MemoryRouter>
      <div style={{ position: 'fixed', right: 16, bottom: 16, zIndex: 9999, display: 'flex', gap: 8, alignItems: 'center',
        background: '#111827', color: '#fff', padding: '8px 12px', borderRadius: 10, boxShadow: '0 4px 16px rgba(0,0,0,.3)', font: '13px sans-serif' }}>
        <span>Preview, signed in as:</span>
        {['AGENT', 'ADMIN'].map((r) => (
          <button key={r} type="button" onClick={() => setCurrent(r)} aria-pressed={current === r}
            style={{ padding: '4px 10px', borderRadius: 6, border: '1px solid #fff', cursor: 'pointer',
              background: current === r ? '#fff' : 'transparent', color: current === r ? '#111827' : '#fff' }}>
            {r === 'AGENT' ? 'Agent' : 'Admin'}
          </button>
        ))}
      </div>
    </AuthContext.Provider>
  )
}

createRoot(document.getElementById('root')).render(<Root />)
