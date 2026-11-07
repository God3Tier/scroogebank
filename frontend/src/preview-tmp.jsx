// TEMPORARY local preview with made-up data. Not committed; deleted after the check.
import { createRoot } from 'react-dom/client'
import '@fontsource-variable/inter'
import '@fontsource-variable/material-symbols-outlined'
import './styles/index.css'
import './styles/dashboard.css'
import './styles/engagement.css'
import SearchSources from './components/SearchSources.jsx'

const admin = new URLSearchParams(location.search).get('as') === 'admin'
const BLOCKED = ['reddit.com', 'x.com', 'facebook.com', 'youtube.com', 'medium.com']
let nextId = 3
const companies = [
  { instrumentId: 'SGX-D05', ticker: 'D05', issuerName: 'DBS Group Holdings Ltd',
    official: ['sgx.com', 'dbs.com'], shared: ['reuters.com', 'cnbc.com', 'ft.com'],
    custom: [
      { id: 'd1', domain: 'businesstimes.com.sg', addedAt: '2026-11-04T04:00:00Z', addedByYou: true },
      { id: 'd2', domain: 'theedgesingapore.com', addedAt: '2026-11-03T04:00:00Z', addedByYou: false },
    ], canAdd: !admin, maxCustom: 10 },
  { instrumentId: 'SGX-Z74', ticker: 'Z74', issuerName: 'Singapore Telecommunications Ltd',
    official: ['singtel.com'], shared: ['reuters.com', 'cnbc.com', 'ft.com'], custom: [], canAdd: !admin, maxCustom: 10 },
]

function reply(status, body) {
  return new Response(body === undefined ? null : JSON.stringify(body),
    { status, headers: { 'Content-Type': 'application/json' } })
}
function problem(status, detail) { return reply(status, { title: 'Search source refused', detail }) }

window.fetch = async (url, options = {}) => {
  const method = options.method || 'GET'
  const path = String(url)
  await new Promise((resolve) => setTimeout(resolve, 250))
  if (method === 'GET') return reply(200, companies)
  const add = path.match(/search-sources\/([^/]+)\/domains$/)
  if (add && method === 'POST') {
    const company = companies.find((c) => c.instrumentId === decodeURIComponent(add[1]))
    const domain = JSON.parse(options.body).domain.trim().toLowerCase().replace(/^www\./, '')
    if (!/^([a-z0-9-]+\.)+[a-z]{2,}$/.test(domain)) return problem(400, 'enter a plain site name such as example.com')
    if (BLOCKED.includes(domain)) return problem(400, 'social, forum and blog platforms cannot be used as news sources')
    const taken = [...company.official, ...company.shared, ...company.custom.map((d) => d.domain)]
    if (taken.some((d) => domain === d || domain.endsWith(`.${d}`))) return problem(409, 'that site is already searched for this company')
    const entry = { id: `d${nextId++}`, domain, addedAt: new Date().toISOString(), addedByYou: true }
    company.custom.push(entry)
    return reply(201, entry)
  }
  const del = path.match(/search-sources\/([^/]+)\/domains\/([^/]+)$/)
  if (del && method === 'DELETE') {
    const company = companies.find((c) => c.instrumentId === decodeURIComponent(del[1]))
    company.custom = company.custom.filter((d) => d.id !== decodeURIComponent(del[2]))
    return new Response(null, { status: 204 })
  }
  return reply(404, {})
}

createRoot(document.getElementById('root')).render(
  <main style={{ maxWidth: 960, margin: '24px auto', padding: '0 16px' }}>
    <SearchSources readOnly={admin} />
  </main>,
)
