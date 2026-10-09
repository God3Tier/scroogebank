import { Link } from 'react-router-dom'

import clients from '../../data/clients.js'

// Only seed clients have a client page in this demo, so only they get a link.
function clientPath(clientId) {
  const exists = clients.some((client) => String(client.clientId) === String(clientId))
  return exists ? `/agent/client/${clientId}/transactions` : null
}

/**
 * Grouped work queue. Each group shows an explicit empty state rather than
 * disappearing, so "nothing to do" is visible as an answer.
 *
 * @param groups [{ key, title, icon, empty, rows: [{ id, name, detail, meta, clientId }] }]
 */
function ActionList({ groups }) {
  return (
    <div className="action-groups">
      {groups.map((group) => (
        <div key={group.key} className="action-group">
          <h3>
            <span className="material-symbols-outlined" aria-hidden="true">{group.icon}</span>
            {group.title}
            <span className="count-pill">{group.rows.length}</span>
          </h3>
          {group.rows.length === 0 ? (
            <p className="empty-note">{group.empty}</p>
          ) : (
            <ul className="action-rows">
              {group.rows.map((row) => {
                const path = clientPath(row.clientId)
                return (
                  <li key={row.id}>
                    <div className="action-main">
                      <span className="action-name">{row.name}</span>
                      {row.detail && <span className="action-detail">{row.detail}</span>}
                    </div>
                    <span className="action-meta">{row.meta}</span>
                    {path ? (
                      <Link className="action-link" to={path}>Open client</Link>
                    ) : (
                      <span className="action-link action-link-disabled" title="Demo: only seed clients have a client page">Open client</span>
                    )}
                  </li>
                )
              })}
            </ul>
          )}
        </div>
      ))}
    </div>
  )
}

export default ActionList
