import { useMemo } from 'react'

import { listAuditLogs } from '../api/auditLogs.js'
import { AuditAction, AuditPayload, AuditTime } from '../components/AuditLogParts.jsx'
import CorruptedAuditLogs from '../components/CorruptedAuditLogs.jsx'
import { Pager } from '../components/ListFooter.jsx'
import Spinner from '../components/Spinner.jsx'
import { cursorSource, usePagedList } from '../components/paging.js'

function errorMessage(error) {
  if (error.status === 401) return 'Your session has expired. Sign in again.'
  if (error.status === 403) return 'You do not have permission to view this page.'
  if (error.status === 503) return 'The audit log is temporarily unavailable.'
  return 'The audit log could not be loaded.'
}

// The audit trail and its integrity findings. ListPage supplies the page
// title; each table is a flush card like the other admin lists.
export default function AdminAuditLogs() {
  const source = useMemo(
    () => cursorSource(
      ({ size, cursor }) => listAuditLogs({ size, cursor }),
      { pageSize: 20, chunkSize: 20 },
    ),
    [],
  )

  const list = usePagedList(source)
  const empty = !list.loading && !list.error && list.rows.length === 0

  return (
    <>
      <section className="card card-flush audit-log" aria-labelledby="audit-activity-heading">
        <header className="audit-log__header">
          <div>
            <h2 id="audit-activity-heading">Complete Audit Log</h2>
            <p className="audit-log__note">
              All recorded events, including reads, authentication, failures, and transaction-service activity.
            </p>
          </div>
        </header>

        {list.error && (
          <div className="audit-log__problem" role="alert">
            <span className="material-symbols-outlined" aria-hidden="true">warning</span>
            <p>{errorMessage(list.error)}</p>
            <button type="button" onClick={list.reload}>Try again</button>
          </div>
        )}

        {list.rows.length > 0 && (
          <div className="table-wrap">
            <table className="data-table audit-log-table">
              <thead>
                <tr>
                  <th scope="col">Time</th>
                  <th scope="col">User</th>
                  <th scope="col">Service</th>
                  <th scope="col">Event type</th>
                  <th scope="col">Action</th>
                  <th scope="col">Details</th>
                </tr>
              </thead>
              <tbody>
                {list.rows.map((entry) => (
                  <tr key={entry.id}>
                    <td><AuditTime value={entry.timestamp} /></td>
                    <td>
                      {entry.actorName
                        ? <span className="audit-log__actor" title={entry.actorId}>{entry.actorName}</span>
                        : <span className="mono">{entry.actorId || 'Unknown'}</span>}
                    </td>
                    <td className="mono">{entry.sourceService}</td>
                    <td className="mono">{entry.eventType}</td>
                    <td><AuditAction action={entry.action} /></td>
                    <td><AuditPayload label="View details" value={entry.payload} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {list.loading && (
          <div className="audit-log__status"><Spinner label="Loading audit activity…" /></div>
        )}

        {empty && (
          <p className="audit-log__status">
            <span className="material-symbols-outlined" aria-hidden="true">history</span>
            No audit log entries found.
          </p>
        )}

        {!empty && !list.error && <Pager list={list} label="Audit log pages" />}
      </section>

      <CorruptedAuditLogs />
    </>
  )
}
