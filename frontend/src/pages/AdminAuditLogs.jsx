import { useMemo } from 'react'

import { listActivities } from '../api/activities.js'
import { cursorSource, usePagedList } from '../components/paging.js'

function errorMessage(error) {
  if (error.status === 401) return 'Your session has expired. Sign in again.'
  if (error.status === 403) return 'You do not have permission to view this page.'
  if (error.status === 503) return 'Audit activity is temporarily unavailable.'
  return 'Audit activity could not be loaded.'
}

export default function AdminAuditLogs() {
  const source = useMemo(
    () => cursorSource(
      ({ size, cursor }) => listActivities({ size, cursor }),
      { pageSize: 20, chunkSize: 20 },
    ),
    [],
  )

  const list = usePagedList(source)

  return (
    <div className="page">
      <header className="page-header">
        <h1>Audit Activity</h1>
        <p className="page-subtitle">
          Successful changes recorded across the CRM, newest first.
          Reads, sign-ins, sign-outs, and failures are not included.
        </p>
      </header>

      <section className="card">
        {list.loading && (
          <p role="status">Loading audit activity…</p>
        )}

        {list.error && (
          <div role="alert">
            <p>{errorMessage(list.error)}</p>
            <button type="button" onClick={list.reload}>
              Try again
            </button>
          </div>
        )}

        {!list.loading && !list.error && list.rows.length === 0 && (
          <p>No audit activity found.</p>
        )}

        {list.rows.length > 0 && (
          <div className="table-wrap">
            <table className="audit-log-table">
              <thead>
                <tr>
                  <th scope="col">Time</th>
                  <th scope="col">User</th>
                  <th scope="col">Service</th>
                  <th scope="col">Action</th>
                  <th scope="col">Details</th>
                </tr>
              </thead>

              <tbody>
                {list.rows.map((entry) => (
                  <tr key={entry.id}>
                    <td>
                      <time dateTime={entry.timestamp}>
                        {new Date(entry.timestamp).toLocaleString('en-SG')}
                      </time>
                    </td>
                    <td>{entry.actorName || entry.actorId || 'Unknown'}</td>
                    <td>{entry.sourceService}</td>
                    <td>{entry.action}</td>
                    <td>
                      <details>
                        <summary>View details</summary>
                        <pre className="audit-log-payload">
                          {typeof entry.payload === 'string'
                            ? entry.payload
                            : JSON.stringify(entry.payload, null, 2)}
                        </pre>
                      </details>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <nav className="list-footer pager" aria-label="Audit activity pages">
          <button
            type="button"
            disabled={list.loading || list.page === 0}
            onClick={() => list.go(list.page - 1)}
          >
            Previous
          </button>

          <span aria-live="polite">Page {list.page + 1}</span>

          <button
            type="button"
            disabled={list.loading || !list.hasNext}
            onClick={() => list.go(list.page + 1)}
          >
            Next
          </button>
        </nav>
      </section>
    </div>
  )
}