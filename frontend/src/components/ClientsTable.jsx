import { useMemo, useState } from 'react'

const CLIENTS_PER_PAGE = 5

// The affected clients of one ticker. With more than CLIENTS_PER_PAGE clients it adds a search box and pages; ticks are kept
// by the page (selected: { alertId: true }), so they survive searching and paging.
function ClientsTable({ clients, names, selected, setSelected, selectable, statusLabels, clientLabel }) {
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const paged = clients.length > CLIENTS_PER_PAGE

  const matching = useMemo(() => {
    const needle = search.trim().toLowerCase()
    if (!paged || !needle) return clients
    return clients.filter((client) => clientLabel(client, names).toLowerCase().includes(needle))
  }, [clients, names, search, paged, clientLabel])

  const pages = Math.max(1, Math.ceil(matching.length / CLIENTS_PER_PAGE))
  const current = Math.min(page, pages - 1)
  const shown = paged ? matching.slice(current * CLIENTS_PER_PAGE, (current + 1) * CLIENTS_PER_PAGE) : clients
  const ticked = clients.filter((client) => selected[client.alertId] && selectable(client)).length
  // "Select all" covers every client that can be chosen and matches the search, on every page.
  const chooseable = matching.filter(selectable)
  const allChosen = chooseable.length > 0 && chooseable.every((client) => selected[client.alertId])
  const selectAll = (checked) => setSelected((s) => ({ ...s, ...Object.fromEntries(chooseable.map((client) => [client.alertId, checked])) }))

  return (
    <>
      {paged && (
        <div className="clients-search">
          <label>
            <span>Search clients</span>
            <input type="search" value={search} placeholder="Type a client name"
              onChange={(e) => { setSearch(e.target.value); setPage(0) }} />
          </label>
          <span role="status" className="clients-search__count">
            {matching.length} of {clients.length} clients · {ticked} ticked
          </span>
        </div>
      )}
      <div className="table-wrap">
        <table className="data-table">
          <thead>
            <tr>
              <th>
                <input type="checkbox" aria-label={paged && search.trim() ? 'Select all matching clients' : 'Select all clients'}
                  disabled={chooseable.length === 0} checked={allChosen} onChange={(e) => selectAll(e.target.checked)} />
              </th>
              <th>Client <span className="clients-selectall-hint">(tick the box to select all {chooseable.length})</span></th><th className="num">Share of portfolio</th><th className="num">Exposure (SGD)</th><th className="num">Score</th><th>Status</th>
            </tr>
          </thead>
          <tbody>
            {shown.map((client) => (
              <tr key={client.alertId}>
                <td>
                  <input type="checkbox" aria-label={`Select ${clientLabel(client, names)}`}
                    disabled={!selectable(client)}
                    checked={Boolean(selected[client.alertId])}
                    onChange={(e) => setSelected((s) => ({ ...s, [client.alertId]: e.target.checked }))} />
                </td>
                <td title={client.clientRef}>{clientLabel(client, names)}</td>
                <td className="num">{client.portfolioPercentage == null ? '—' : `${Number(client.portfolioPercentage).toFixed(1)}%`}</td>
                <td className="num">{Number(client.amountSgd).toLocaleString('en-SG', { minimumFractionDigits: 2 })}</td>
                <td className="num">{Number(client.score).toFixed(1)}</td>
                <td>{statusLabels[client.status] || client.status}</td>
              </tr>
            ))}
            {paged && shown.length === 0 && (
              <tr><td colSpan={6} className="muted">No client matches "{search.trim()}".</td></tr>
            )}
          </tbody>
        </table>
      </div>
      {paged && (
        <nav className="clients-pager" aria-label="Client pages">
          <button type="button" className="button" disabled={current === 0} onClick={() => setPage(current - 1)}>Previous</button>
          <span>Page {current + 1} of {pages}</span>
          <button type="button" className="button" disabled={current >= pages - 1} onClick={() => setPage(current + 1)}>Next</button>
        </nav>
      )}
    </>
  )
}

export default ClientsTable
