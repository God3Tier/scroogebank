import { useMemo, useState } from 'react'

import { addSearchWebsite, removeSearchWebsite } from '../api/searchSources.js'
import { formatDate, problemText, searchText } from './searchSourcesText.js'

// The websites searched for every company (ADR-0082). Agents add them; an agent or an admin can
// remove one. The shared news sites are set by the deployment and cannot be changed here.
// Like the clients of a company, the added websites are listed five at a time with a search box once there are more than five.
// An agent can also change a website's name (fix a typo): the new name goes through the same check as a new website, and the
// old one is removed only once the new one is accepted.

const PER_PAGE = 5

const plain = (domain) => String(domain).trim().toLowerCase().replace(/^www\./, '')

function SearchWebsites({ websites, readOnly, tickers, onChanged }) {
  const [domain, setDomain] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [confirming, setConfirming] = useState(null)
  const [editing, setEditing] = useState(null)
  const [draft, setDraft] = useState('')
  const [find, setFind] = useState('')
  const [page, setPage] = useState(0)
  const full = websites.added.length >= websites.max
  const paged = websites.added.length > PER_PAGE

  const matching = useMemo(() => {
    const needle = find.trim().toLowerCase()
    if (!paged || !needle) return websites.added
    return websites.added.filter((entry) => entry.domain.toLowerCase().includes(needle))
  }, [websites.added, find, paged])
  const pages = Math.max(1, Math.ceil(matching.length / PER_PAGE))
  const current = Math.min(page, pages - 1)
  const shown = paged ? matching.slice(current * PER_PAGE, (current + 1) * PER_PAGE) : websites.added

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

  function startEditing(entry) {
    setConfirming(null)
    setError('')
    setNotice('')
    setEditing(entry.id)
    setDraft(entry.domain)
  }

  async function save(entry) {
    if (busy) return
    if (plain(draft) === plain(entry.domain)) {
      setEditing(null)
      return
    }
    setBusy(true)
    setError('')
    setNotice('')
    try {
      // The new name is checked and added first; the old one goes only once the new one is accepted.
      const added = await addSearchWebsite(draft)
      await removeSearchWebsite(entry.id)
      setEditing(null)
      setNotice(`Changed ${entry.domain} to ${added.domain}. ${searchText(added.search, { domain: added.domain, tickers })}`.trim())
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
          <>
            {paged && (
              <div className="clients-search source-search">
                <label>
                  <span>Search websites</span>
                  <input type="search" value={find} placeholder="Type part of a website name" aria-label="Search the added websites"
                    onChange={(e) => { setFind(e.target.value); setPage(0) }} />
                </label>
                <span role="status" className="clients-search__count">{matching.length} of {websites.added.length} websites</span>
              </div>
            )}
            <ul className="source-added">
              {shown.map((entry) => (
                <li key={entry.id}>
                  {editing === entry.id ? (
                    <span className="source-edit" role="group" aria-label={`Change ${entry.domain}`}>
                      <input value={draft} maxLength={300} autoComplete="off" autoFocus aria-label={`New name for ${entry.domain}`}
                        onChange={(e) => setDraft(e.target.value)}
                        onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); save(entry) } if (e.key === 'Escape') setEditing(null) }} />
                      <button type="button" className="source-confirm__yes" disabled={busy || draft.trim() === ''} onClick={() => save(entry)}>
                        {busy ? 'Saving…' : 'Save'}
                      </button>
                      <button type="button" className="source-confirm__no" disabled={busy} onClick={() => setEditing(null)}>Cancel</button>
                    </span>
                  ) : (
                    <>
                      <span className="source-chip source-chip--added">{entry.domain}</span>
                      <span className="muted"> added {formatDate(entry.addedAt)} by {entry.addedByYou ? 'you' : 'another agent'}</span>
                      {confirming === entry.id ? (
                        <span className="source-confirm" role="group" aria-label={`Confirm removing ${entry.domain}`}>
                          {' '}Stop searching {entry.domain}?{' '}
                          <button type="button" className="source-confirm__yes" disabled={busy} onClick={() => remove(entry)}>Yes, remove</button>{' '}
                          <button type="button" className="source-confirm__no" disabled={busy} onClick={() => setConfirming(null)}>Cancel</button>
                        </span>
                      ) : (
                        <span className="source-actions">
                          {!readOnly && (
                            <button type="button" className="source-edit__open" disabled={busy}
                              aria-label={`Edit ${entry.domain}`} onClick={() => startEditing(entry)}>
                              <span className="material-symbols-outlined" aria-hidden="true">edit</span> Edit
                            </button>
                          )}
                          <button type="button" className="source-remove" disabled={busy}
                            aria-label={`Remove ${entry.domain}`} onClick={() => setConfirming(entry.id)}>
                            <span className="material-symbols-outlined" aria-hidden="true">close</span> Remove
                          </button>
                        </span>
                      )}
                    </>
                  )}
                </li>
              ))}
              {paged && shown.length === 0 && <li className="muted">No website matches &quot;{find.trim()}&quot;.</li>}
            </ul>
            {paged && (
              <nav className="clients-pager" aria-label="Added websites pages">
                <button type="button" disabled={current === 0} onClick={() => setPage(current - 1)}>Previous</button>
                <span>Page {current + 1} of {pages}</span>
                <button type="button" disabled={current >= pages - 1} onClick={() => setPage(current + 1)}>Next</button>
              </nav>
            )}
          </>
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
