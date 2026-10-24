import { useMemo } from 'react'
import { Link } from 'react-router-dom'

import { listTransactions } from '../api/transactions.js'
import { NO_FIGURE } from './dashboard/format.js'
import { Pager, ViewAll } from './ListFooter.jsx'
import { PAGE_SIZE, cursorSource, usePagedList } from './paging.js'

function shortId(id) {
  return id ? String(id).slice(0, 8) : NO_FIGURE
}

function formatDate(value) {
  return new Date(value).toLocaleDateString('en-SG', { dateStyle: 'medium' })
}

// Every account's transactions, newest first (ADR-0043). On Admin Home the
// newest ten show; paged, the whole list does, ten at a time.
function TransactionsTable({ paged = false }) {
  const source = useMemo(
    () => cursorSource(({ cursor, size }) => listTransactions({ cursor, size }), paged ? {} : { chunkSize: PAGE_SIZE }),
    [paged],
  )
  const list = usePagedList(source)
  const empty = !list.loading && !list.error && list.rows.length === 0

  return (
    <section className="card card-flush">
      <h2>All Transactions</h2>
      {list.error && <p className="form-error" role="alert">Transactions could not be loaded.</p>}
      <div className="table-wrap">
        <table className="data-table">
          <thead>
            <tr>
              <th>Transaction ID</th>
              <th>Client</th>
              <th>Type</th>
              <th className="num">Amount</th>
              <th>Date</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {list.rows.map((transaction) => (
              <tr key={transaction.transaction_id}>
                <td className="mono">{transaction.transaction_id}</td>
                <td>
                  {transaction.client_id ? (
                    <Link to={`/clients/${encodeURIComponent(transaction.client_id)}`} title={transaction.client_id}>
                      <span className="mono">{shortId(transaction.client_id)}</span>
                    </Link>
                  ) : NO_FIGURE}
                </td>
                <td>{transaction.transaction_type}</td>
                <td className="num">{transaction.currency ? `${transaction.currency} ` : ''}{transaction.amount}</td>
                <td>{formatDate(transaction.transaction_date)}</td>
                <td>
                  <span className={`badge badge-${String(transaction.status).toLowerCase()}`}>{transaction.status}</span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {empty && <p className="empty-note">No transactions yet.</p>}
      {list.loading && <p className="empty-note">Loading…</p>}
      {!empty && (paged ? <Pager list={list} label="Transaction pages" /> : <ViewAll to="/admin/transactions" label="View all transactions" />)}
    </section>
  )
}

export default TransactionsTable
