import { useEffect, useMemo, useState } from 'react'

import { listSearchHoldings, listSearchWebsites, searchNow } from '../api/searchSources.js'
import HeldCompanies from './HeldCompanies.jsx'
import SearchSchedule from './SearchSchedule.jsx'
import SearchWebsites from './SearchWebsites.jsx'
import { problemText, searchText } from './searchSourcesText.js'

// What is searched for news and when (ADR-0081, ADR-0082): when the next automatic search is, the
// websites searched for every company, a button to search now, and the companies with the clients
// that hold them. An admin can look, search and remove a website, but not add one.

function SearchSources({ readOnly = false }) {
  const [state, setState] = useState({ loading: true, error: '', websites: null, holdings: [] })
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState('')
  const [searchError, setSearchError] = useState('')

  async function load() {
    try {
      const [websites, holdings] = await Promise.all([listSearchWebsites(), listSearchHoldings()])
      setState({ loading: false, error: '', websites, holdings })
    } catch (e) {
      setState({ loading: false, error: problemText(e), websites: null, holdings: [] })
    }
  }

  useEffect(() => {
    let current = true
    Promise.all([listSearchWebsites(), listSearchHoldings()]).then(
      ([websites, holdings]) => { if (current) setState({ loading: false, error: '', websites, holdings }) },
      (e) => { if (current) setState({ loading: false, error: problemText(e), websites: null, holdings: [] }) },
    )
    return () => { current = false }
  }, [])

  const tickers = useMemo(
    () => Object.fromEntries(state.holdings.map((company) => [company.instrumentId, company.ticker])), [state.holdings])

  async function searchAll() {
    if (busy) return
    setBusy(true)
    setSearchError('')
    setNotice('')
    try {
      setNotice(searchText(await searchNow(), { tickers }))
    } catch (e) {
      setSearchError(problemText(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="search-sources" aria-labelledby="search-sources-title">
      <h2 id="search-sources-title">Search sources</h2>
      <p className="holdings-note">
        {readOnly
          ? 'The websites searched for news about every company, and who holds each company. You can remove a website an agent added.'
          : 'The websites we search for news about the companies your clients hold. A website you add is searched for every company. Anyone can remove one.'}
      </p>
      <SearchSchedule />
      {state.loading && <p className="empty-note" role="status">Loading search sources…</p>}
      {state.error && <p className="error-text" role="alert">{state.error}</p>}
      {state.websites && (
        <SearchWebsites websites={state.websites} readOnly={readOnly} tickers={tickers} onChanged={load} />
      )}
      {!state.loading && !state.error && state.holdings.length > 0 && (
        <div className="source-add">
          <button type="button" disabled={busy} onClick={searchAll}>
            {busy ? 'Starting…' : readOnly ? 'Search now for all companies' : 'Search now for all my companies'}
          </button>
          <span className="muted">Searches the last 7 days on every website. The automatic schedule is not changed.</span>
        </div>
      )}
      {searchError && <p className="error-text" role="alert">{searchError}</p>}
      {notice && <p className="holdings-note" role="status">{notice}</p>}
      {!state.loading && !state.error && <HeldCompanies holdings={state.holdings} admin={readOnly} tickers={tickers} />}
    </section>
  )
}

export default SearchSources
