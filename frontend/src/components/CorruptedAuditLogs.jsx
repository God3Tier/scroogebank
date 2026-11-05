import { useEffect, useState } from 'react'

import { listCorruptedLogs } from '../api/corruptedLogs.js'
import { Pager } from './ListFooter.jsx'
import { useArrayPages } from './paging.js'

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

  return (
    <section className="card" aria-labelledby="corrupted-logs-heading">
      <h2 id="corrupted-logs-heading">Corrupted Audit Logs</h2>

      <p className="page-subtitle">
        Audit integrity findings, newest detection first.
      </p>

      <div className="action-bar">
        <button
          type="button"
          disabled={result.loading}
          onClick={refresh}
        >
          Refresh corrupted logs
        </button>
      </div>

      {result.loading && (
        <p role="status">Loading corrupted audit logs…</p>
      )}

      {result.error && (
        <p className="form-error" role="alert">
          {errorMessage(result.error)}
        </p>
      )}

      {!result.loading &&
        !result.error &&
        result.rows.length === 0 && (
          <p>No corrupted audit logs found.</p>
        )}

      {!result.loading &&
        !result.error &&
        result.rows.length > 0 && (
          <>
            <div className="table-wrap">
              <table className="audit-log-table">
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
                      <td>
                        <time dateTime={entry.date_of_detect}>
                          {new Date(entry.date_of_detect).toLocaleString(
                            'en-SG',
                          )}
                        </time>
                      </td>

                      <td>{entry.reason || 'Not specified'}</td>
                      <td>{entry.payload?.source_service || '—'}</td>
                      <td>{entry.payload?.action || '—'}</td>
                      <td>{entry.payload?.actor_id || '—'}</td>

                      <td>
                        <details>
                          <summary>View finding</summary>
                          <pre className="audit-log-payload">
                            {JSON.stringify(entry, null, 2)}
                          </pre>
                        </details>
                      </td>
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