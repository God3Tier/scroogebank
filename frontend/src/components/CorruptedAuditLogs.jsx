import { useEffect, useState } from 'react'

import { listCorruptedLogs } from '../api/corruptedLogs.js'
import { AuditAction, AuditPayload, AuditTime } from './AuditLogParts.jsx'
import { Pager } from './ListFooter.jsx'
import { useArrayPages } from './paging.js'
import Spinner from './Spinner.jsx'

function errorMessage(error) {
  if (error.status === 401) {
    return 'Your session has expired. Sign in again.'
  }
  if (error.status === 403) {
    return 'Only admins can view corrupted audit logs.'
  }
  return 'Corrupted audit logs could not be loaded.'
}

export default function CorruptedAuditLogs() {
  const [attempt, setAttempt] = useState(0)
  const [result, setResult] = useState({
    rows: [],
    loading: true,
    error: null,
  })

  const list = useArrayPages(result.rows, 20)

  useEffect(() => {
    let current = true

    listCorruptedLogs()
      .then((rows) => {
        // The endpoint does not guarantee ordering.
        const sorted = [...rows].sort(
          (a, b) =>
            Date.parse(b.date_of_detect) - Date.parse(a.date_of_detect),
        )

        if (current) {
          setResult({
            rows: sorted,
            loading: false,
            error: null,
          })
        }
      })
      .catch((error) => {
        if (current) {
          setResult({
            rows: [],
            loading: false,
            error,
          })
        }
      })

    return () => {
      current = false
    }
  }, [attempt])

  function refresh() {
    setResult({ rows: [], loading: true, error: null })
    setAttempt((value) => value + 1)
  }

  const empty = !result.loading && !result.error && result.rows.length === 0

  return (
    <section className="card card-flush audit-log" aria-labelledby="corrupted-logs-heading">
      <header className="audit-log__header">
        <div>
          <h2 id="corrupted-logs-heading">Corrupted Audit Logs</h2>
          <p className="audit-log__note">Audit integrity findings, newest detection first.</p>
        </div>
        <button type="button" disabled={result.loading} onClick={refresh}>
          <span className="material-symbols-outlined" aria-hidden="true">refresh</span>
          Refresh corrupted logs
        </button>
      </header>

      {result.error && (
        <div className="audit-log__problem" role="alert">
          <span className="material-symbols-outlined" aria-hidden="true">warning</span>
          <p>{errorMessage(result.error)}</p>
        </div>
      )}

      {result.loading && (
        <div className="audit-log__status"><Spinner label="Loading corrupted audit logs…" /></div>
      )}

      {empty && (
        <p className="audit-log__status">
          <span className="material-symbols-outlined" aria-hidden="true">verified</span>
          No corrupted audit logs found.
        </p>
      )}

      {!result.loading && !result.error && result.rows.length > 0 && (
        <>
          <div className="table-wrap">
            <table className="data-table audit-log-table">
              <thead>
                <tr>
                  <th scope="col">Detected</th>
                  <th scope="col">Reason</th>
                  <th scope="col">Service</th>
                  <th scope="col">Action</th>
                  <th scope="col">Actor</th>
                  <th scope="col">Details</th>
                </tr>
              </thead>
              <tbody>
                {list.rows.map((entry) => (
                  <tr key={entry.id}>
                    <td><AuditTime value={entry.date_of_detect} /></td>
                    <td><span className="badge badge-failed">{entry.reason || 'Not specified'}</span></td>
                    <td className="mono">{entry.payload?.source_service || '—'}</td>
                    <td><AuditAction action={entry.payload?.action} /></td>
                    <td className="mono">{entry.payload?.actor_id || '—'}</td>
                    <td><AuditPayload label="View finding" value={entry} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <Pager list={list} label="Corrupted audit log pages" />
        </>
      )}
    </section>
  )
}
