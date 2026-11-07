import { useEffect, useState } from 'react'

import { listSentOut } from '../api/alertEvents.js'
import { getClient } from '../api/clients.js'
import { isCrmClientId } from '../api/engagement.js'

// The "Sent out" view (news-alert-drafts plan 5.8): each client who was emailed a report. Sent Out is final, so
// the rows are read-only. Agents see their own clients; admins see everyone's.

const WHEN = new Intl.DateTimeFormat('en-SG', {
  day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Singapore',
})

function SentOutList({ readOnly = false }) {
  const [state, setState] = useState({ loading: true, error: '', rows: [] })
  const [names, setNames] = useState({})

  useEffect(() => {
    let current = true
    listSentOut().then(
      (rows) => { if (current) setState({ loading: false, error: '', rows }) },
      () => { if (current) setState({ loading: false, error: 'The sent reports could not be loaded.', rows: [] }) },
    )
    return () => { current = false }
  }, [])

  // Names come from the CRM, in the browser; the alerts service never sees them.
  useEffect(() => {
    let current = true
    const refs = [...new Set(state.rows.map((row) => row.clientRef).filter(isCrmClientId))]
    refs.forEach((ref) => {
      getClient(ref).then(
        (client) => {
          const name = [client.first_name, client.last_name].filter(Boolean).join(' ')
          if (current && name) setNames((existing) => ({ ...existing, [ref]: name }))
        },
        () => { /* keep the ID */ },
      )
    })
    return () => { current = false }
  }, [state.rows])

  return (
    <section className="detail-section sent-out" aria-labelledby="sent-out-title">
      <div className="section-heading">
        <div><span className="material-symbols-outlined" aria-hidden="true">outgoing_mail</span><h3 id="sent-out-title">Sent out</h3></div>
        <span>{readOnly ? 'Read-only' : 'Final; these clients have been emailed'}</span>
      </div>
      {state.loading && <p className="empty-note" role="status">Loading…</p>}
      {state.error && <p className="error-text" role="alert">{state.error}</p>}
      {!state.loading && !state.error && state.rows.length === 0 && <p className="empty-note">No report has been sent yet.</p>}
      {state.rows.length > 0 && (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr><th>Ticker</th><th>Headline</th><th>Client</th><th>Edited</th><th>Sent</th></tr>
            </thead>
            <tbody>
              {state.rows.map((row) => (
                <tr key={row.alertId}>
                  <td title={row.issuer}>{row.ticker || row.instrumentId}</td>
                  <td>{row.headline || row.issuer}</td>
                  <td title={row.clientRef}>{names[row.clientRef] || row.clientRef.slice(0, 8)}</td>
                  <td>{row.edited ? 'Yes' : 'No'}</td>
                  <td>{WHEN.format(new Date(row.sentAt))} SGT</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

export default SentOutList
