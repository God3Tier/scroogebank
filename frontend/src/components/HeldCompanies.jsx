import { useEffect, useState } from 'react'

import { getClient } from '../api/clients.js'
import { searchNow } from '../api/searchSources.js'
import { listUsers } from '../api/users.js'
import { problemText, searchText } from './searchSourcesText.js'

// The companies that are searched and which clients hold each one (ADR-0082). The alerts service gives
// anonymous client ids; the names are looked up here, in the CRM, with the signed-in user's own login.

const number = new Intl.NumberFormat('en-SG', { maximumFractionDigits: 4 })
const money = new Intl.NumberFormat('en-SG', { maximumFractionDigits: 0 })

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
          {company.holders.map((holder) => (
            <tr key={`${holder.accountId}`}>
              <td>{clientNames[holder.clientId] || `Client ${shortId(holder.clientId)}`}</td>
              <td className="num">{number.format(holder.sharePercent)}%</td>
              <td className="num">{number.format(holder.quantity)}</td>
              <td className="num">{money.format(holder.valueSgd)}</td>
              {admin && <td>{agentNames[holder.ownerAgentSubject] || holder.ownerAgentSubject || '—'}</td>}
            </tr>
          ))}
        </tbody>
      </table>
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
