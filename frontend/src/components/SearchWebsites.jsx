import { useState } from 'react'

import { addSearchWebsite, removeSearchWebsite } from '../api/searchSources.js'
import { formatDate, problemText, searchText } from './searchSourcesText.js'

// The websites searched for every company (ADR-0082). Agents add them; an agent or an admin can
// remove one. The shared news sites are set by the deployment and cannot be changed here.

function SearchWebsites({ websites, readOnly, tickers, onChanged }) {
  const [domain, setDomain] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [confirming, setConfirming] = useState(null)
  const full = websites.added.length >= websites.max

  async function add(event) {
    event.preventDefault()
    if (busy) return
    setBusy(true)
    setError('')
    setNotice('')
    try {
      const added = await addSearchWebsite(domain)
      setDomain('')
      setNotice(`Added ${added.domain}. ${searchText(added.search, { domain: added.domain, tickers })}`.trim())
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
    setNotice('')
    try {
      await removeSearchWebsite(entry.id)
      setConfirming(null)
      await onChanged()
    } catch (e) {
      setError(problemText(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <article className="source-card" aria-label="Websites searched for every company">
      <h3>Websites searched for every company</h3>
      <p className="source-line">
        <strong>Always searched:</strong>{' '}
        {websites.shared.length === 0 ? <span className="muted">none</span>
          : websites.shared.map((site) => <span key={site} className="source-chip">{site}</span>)}
        <span className="muted"> (plus each company&apos;s own official sites)</span>
      </p>
      <div className="source-line">
        <strong>Added by agents:</strong>
        {websites.added.length === 0 ? <span className="muted"> none yet</span> : (
          <ul className="source-added">
            {websites.added.map((entry) => (
              <li key={entry.id}>
                <span className="source-chip source-chip--added">{entry.domain}</span>
                <span className="muted"> added {formatDate(entry.addedAt)} by {entry.addedByYou ? 'you' : 'another agent'}</span>
                {confirming === entry.id ? (
                  <span className="source-confirm" role="group" aria-label={`Confirm removing ${entry.domain}`}>
                    {' '}Stop searching {entry.domain}?{' '}
                    <button type="button" className="source-confirm__yes" disabled={busy} onClick={() => remove(entry)}>Yes, remove</button>{' '}
                    <button type="button" className="source-confirm__no" disabled={busy} onClick={() => setConfirming(null)}>Cancel</button>
                  </span>
                ) : (
                  <button type="button" className="source-remove" disabled={busy}
                    aria-label={`Remove ${entry.domain}`} onClick={() => setConfirming(entry.id)}>
                    <span className="material-symbols-outlined" aria-hidden="true">close</span> Remove
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
      {error && <p className="error-text" role="alert">{error}</p>}
      {notice && <p className="holdings-note" role="status">{notice}</p>}
      {!readOnly && websites.canAdd && (
        full ? (
          <p className="holdings-note">{websites.max} websites have been added. Remove one to add another.</p>
        ) : (
          <form className="source-add" onSubmit={add}>
            <label htmlFor="add-website">Add a website</label>
            <input id="add-website" value={domain} maxLength={300} placeholder="example.com" autoComplete="off"
              onChange={(e) => setDomain(e.target.value)} />
            <button type="submit" disabled={busy || domain.trim() === ''}>{busy ? 'Saving…' : 'Add website'}</button>
          </form>
        )
      )}
    </article>
  )
}

export default SearchWebsites
