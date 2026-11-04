import { useEffect, useState } from 'react'

import { addSearchDomain, listSearchSources, removeSearchDomain } from '../api/searchSources.js'

// Which sites are searched for news about each company. Official and shared sites
// are fixed. Agents add their own for companies their clients hold; the new site is
// searched from the next run. Admins can look and remove, but not add (ADR-0079).

function problemText(error) {
  if (error.status === 503) return 'Client records cannot be verified right now. Try again shortly.'
  return error.message || 'Something went wrong. Try again.'
}

function formatDate(value) {
  return new Intl.DateTimeFormat('en-SG', { day: '2-digit', month: 'short', year: 'numeric', timeZone: 'Asia/Singapore' })
    .format(new Date(value))
}

function DomainChips({ label, domains }) {
  return (
    <p className="source-line">
      <strong>{label}:</strong>{' '}
      {domains.length === 0 ? <span className="muted">none</span>
        : domains.map((domain) => <span key={domain} className="source-chip">{domain}</span>)}
    </p>
  )
}

function CompanySources({ company, readOnly, onChanged }) {
  const [domain, setDomain] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [confirming, setConfirming] = useState(null)
  const full = company.custom.length >= company.maxCustom
  const inputId = `add-domain-${company.instrumentId}`

  async function add(event) {
    event.preventDefault()
    if (busy) return
    setBusy(true)
    setError('')
    try {
      await addSearchDomain(company.instrumentId, domain)
      setDomain('')
      await onChanged()
    } catch (e) {
      setError(problemText(e))
    } finally {
      setBusy(false)
    }
  }

  async function remove(entry) {
    setBusy(true)
    setError('')
    try {
      await removeSearchDomain(company.instrumentId, entry.id)
      setConfirming(null)
      await onChanged()
    } catch (e) {
      setError(problemText(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <article className="source-card" aria-label={`${company.issuerName} search sources`}>
      <h3>{company.issuerName} <span className="muted">({company.ticker})</span></h3>
      <DomainChips label="Official sites" domains={company.official} />
      <DomainChips label="Shared news sites" domains={company.shared} />
      <div className="source-line">
        <strong>Added by agents:</strong>
        {company.custom.length === 0 ? <span className="muted"> none yet</span> : (
          <ul className="source-added">
            {company.custom.map((entry) => (
              <li key={entry.id}>
                <span className="source-chip source-chip--added">{entry.domain}</span>
                <span className="muted"> added {formatDate(entry.addedAt)} by {entry.addedByYou ? 'you' : 'another agent'}</span>
                {confirming === entry.id ? (
                  <span className="source-confirm" role="group" aria-label={`Confirm removing ${entry.domain}`}>
                    {' '}Stop searching {entry.domain}?{' '}
                    <button type="button" disabled={busy} onClick={() => remove(entry)}>Yes, remove</button>{' '}
                    <button type="button" disabled={busy} onClick={() => setConfirming(null)}>Cancel</button>
                  </span>
                ) : (
                  <button type="button" className="source-remove" disabled={busy}
                    aria-label={`Remove ${entry.domain}`} onClick={() => setConfirming(entry.id)}>Remove</button>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
      {error && <p className="error-text" role="alert">{error}</p>}
      {!readOnly && company.canAdd && (
        full ? (
          <p className="holdings-note">This company has {company.maxCustom} added sites. Remove one to add another.</p>
        ) : (
          <form className="source-add" onSubmit={add}>
            <label htmlFor={inputId}>Add a site for {company.ticker}</label>
            <input id={inputId} value={domain} maxLength={300} placeholder="example.com" autoComplete="off"
              onChange={(e) => setDomain(e.target.value)} />
            <button type="submit" disabled={busy || domain.trim() === ''}>{busy ? 'Saving…' : 'Add site'}</button>
          </form>
        )
      )}
    </article>
  )
}

function SearchSources({ readOnly = false }) {
  const [state, setState] = useState({ loading: true, error: '', companies: [] })

  async function load() {
    try {
      const companies = await listSearchSources()
      setState({ loading: false, error: '', companies })
    } catch (e) {
      setState({ loading: false, error: problemText(e), companies: [] })
    }
  }

  useEffect(() => {
    let current = true
    listSearchSources().then(
      (companies) => { if (current) setState({ loading: false, error: '', companies }) },
      (e) => { if (current) setState({ loading: false, error: problemText(e), companies: [] }) },
    )
    return () => { current = false }
  }, [])

  return (
    <section className="search-sources" aria-labelledby="search-sources-title">
      <h2 id="search-sources-title">Search sources</h2>
      <p className="holdings-note">
        {readOnly
          ? 'The sites searched for news about each company. You can remove a site an agent added.'
          : 'The sites we search for news about companies your clients hold. Add another site and it is searched from the next run. Anyone holding the company can remove a site you add.'}
      </p>
      {state.loading && <p className="empty-note" role="status">Loading search sources…</p>}
      {state.error && <p className="error-text" role="alert">{state.error}</p>}
      {!state.loading && !state.error && state.companies.length === 0 && (
        <p className="empty-note">No companies to show yet. They appear once a client holds one.</p>
      )}
      {state.companies.map((company) => (
        <CompanySources key={company.instrumentId} company={company} readOnly={readOnly} onChanged={load} />
      ))}
    </section>
  )
}

export default SearchSources
