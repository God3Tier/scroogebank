import { useEffect, useMemo, useState } from 'react'

import { getClient } from '../api/clients.js'
import { searchNow } from '../api/searchSources.js'
import { listUsers } from '../api/users.js'
import { problemText, searchText } from './searchSourcesText.js'

// The companies that are searched and which clients hold each one (ADR-0082). The alerts service gives
// anonymous client ids; the names are looked up here, in the CRM, with the signed-in user's own login.

const number = new Intl.NumberFormat('en-SG', { maximumFractionDigits: 4 })
const money = new Intl.NumberFormat('en-SG', { maximumFractionDigits: 0 })

// Holders are shown five at a time, with a search box, once a company has more than five.
const HOLDERS_PER_PAGE = 5

function shortId(id) {
  return String(id).slice(0, 8)
}

// Client names, fetched once each. A client the user cannot read is shown by a short id instead.
function useClientNames(holdings) {
  const [names, setNames] = useState({})
  useEffect(() => {
    const ids = [...new Set(holdings.flatMap((company) => company.holders.map((holder) => holder.clientId)))]
    let current = true
    Promise.all(ids.map((id) => getClient(id).then(
      (client) => [id, `${client.first_name} ${client.last_name}`],
      () => [id, `Client ${shortId(id)}`],
    ))).then((pairs) => { if (current) setNames(Object.fromEntries(pairs)) })
    return () => { current = false }
  }, [holdings])
  return names
}

// For an admin: the owning agent's name, when the user service says whose login id it is.
function useAgentNames(enabled) {
  const [names, setNames] = useState({})
  useEffect(() => {
    if (!enabled) return undefined
    let current = true
    listUsers({ role: 'AGENT', size: 100 }).then(
      (page) => {
        if (!current) return
        const entries = (page.content || []).filter((user) => user.idpSub)
          .map((user) => [user.idpSub, `${user.firstName} ${user.lastName}`])
        setNames(Object.fromEntries(entries))
      },
      () => {},
    )
    return () => { current = false }
  }, [enabled])
  return names
}

function Company({ company, admin, clientNames, agentNames, tickers }) {
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')
  const [find, setFind] = useState('')
  const [page, setPage] = useState(0)
  const paged = company.holders.length > HOLDERS_PER_PAGE
  const nameOf = (holder) => clientNames[holder.clientId] || `Client ${shortId(holder.clientId)}`
  const matching = useMemo(() => {
    const needle = find.trim().toLowerCase()
    if (!paged || !needle) return company.holders
    return company.holders.filter((holder) => (clientNames[holder.clientId] || `Client ${shortId(holder.clientId)}`).toLowerCase().includes(needle))
  }, [company.holders, clientNames, find, paged])
  const pages = Math.max(1, Math.ceil(matching.length / HOLDERS_PER_PAGE))
  const current = Math.min(page, pages - 1)
  const shown = paged ? matching.slice(current * HOLDERS_PER_PAGE, (current + 1) * HOLDERS_PER_PAGE) : company.holders

  async function search() {
    if (busy) return
    setBusy(true)
    setError('')
    setNotice('')
    try {
      setNotice(searchText(await searchNow(company.instrumentId), { tickers }))
    } catch (e) {
      setError(problemText(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <article className="source-card" aria-label={`${company.issuerName} holdings`}>
      <h3>{company.issuerName} <span className="muted">({company.ticker})</span></h3>
      <p className="source-line">
        <strong>Official sites:</strong>{' '}
        {company.officialDomains.length === 0 ? <span className="muted">none</span>
          : company.officialDomains.map((site) => <span key={site} className="source-chip">{site}</span>)}
      </p>
      {paged && (
        <div className="clients-search">
          <label>
            <span>Search clients</span>
            <input type="search" value={find} placeholder="Type a client name" aria-label={`Search the clients holding ${company.issuerName}`}
              onChange={(e) => { setFind(e.target.value); setPage(0) }} />
          </label>
          <span role="status" className="clients-search__count">{matching.length} of {company.holders.length} clients</span>
        </div>
      )}
      <table className="holders-table">
        <caption className="visually-hidden">Clients holding {company.issuerName}</caption>
        <thead>
          <tr>
            <th scope="col">Client</th>
            <th scope="col" className="num">Share of portfolio</th>
            <th scope="col" className="num">Quantity</th>
            <th scope="col" className="num">Value (SGD)</th>
            {admin && <th scope="col">Agent</th>}
          </tr>
        </thead>
        <tbody>
          {shown.map((holder) => (
            <tr key={`${holder.accountId}`}>
              <td>{nameOf(holder)}</td>
              <td className="num">{number.format(holder.sharePercent)}%</td>
              <td className="num">{number.format(holder.quantity)}</td>
              <td className="num">{money.format(holder.valueSgd)}</td>
              {admin && <td>{agentNames[holder.ownerAgentSubject] || holder.ownerAgentSubject || '—'}</td>}
            </tr>
          ))}
          {paged && shown.length === 0 && (
            <tr><td colSpan={admin ? 5 : 4} className="muted">No client matches &quot;{find.trim()}&quot;.</td></tr>
          )}
        </tbody>
      </table>
      {paged && (
        <nav className="clients-pager" aria-label={`${company.issuerName} client pages`}>
          <button type="button" disabled={current === 0} onClick={() => setPage(current - 1)}>Previous</button>
          <span>Page {current + 1} of {pages}</span>
          <button type="button" disabled={current >= pages - 1} onClick={() => setPage(current + 1)}>Next</button>
        </nav>
      )}
      {error && <p className="error-text" role="alert">{error}</p>}
      {notice && <p className="holdings-note" role="status">{notice}</p>}
      <div className="source-add">
        <button type="button" disabled={busy} onClick={search} aria-label={`Search now for ${company.ticker}`}>
          {busy ? 'Starting…' : 'Search now'}
        </button>
      </div>
    </article>
  )
}

function HeldCompanies({ holdings, admin, tickers }) {
  const clientNames = useClientNames(holdings)
  const agentNames = useAgentNames(admin)
  if (holdings.length === 0) {
    return <p className="empty-note">No companies to show yet. They appear once a client holds one.</p>
  }
  return holdings.map((company) => (
    <Company key={company.instrumentId} company={company} admin={admin}
      clientNames={clientNames} agentNames={agentNames} tickers={tickers} />
  ))
}

export default HeldCompanies
