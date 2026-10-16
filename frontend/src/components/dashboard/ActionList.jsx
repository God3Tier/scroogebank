import { Link } from 'react-router-dom'

/**
 * Grouped work queue. Each group shows an explicit empty state rather than
 * disappearing, so "nothing to do" is visible as an answer. Every row names a
 * client the caller may open (dashboard-api.yaml).
 *
 * @param groups [{ key, title, icon, empty, total?, rows: [{ id, name, detail, meta, clientId }] }]
 *   total is the full count when the API returns only the first rows.
 */
function ActionList({ groups }) {
  return (
    <div className="action-groups">
      {groups.map((group) => (
        <div key={group.key} className="action-group">
          <h3>
            <span className="material-symbols-outlined" aria-hidden="true">{group.icon}</span>
            {group.title}
            <span className="count-pill">{group.total ?? group.rows.length}</span>
          </h3>
          {group.rows.length === 0 ? (
            <p className="empty-note">{group.empty}</p>
          ) : (
            <ul className="action-rows">
              {group.rows.map((row) => (
                <li key={row.id}>
                  <div className="action-main">
                    <span className="action-name">{row.name}</span>
                    {row.detail && <span className="action-detail">{row.detail}</span>}
                  </div>
                  <span className="action-meta">{row.meta}</span>
                  <Link className="action-link" to={`/clients/${encodeURIComponent(row.clientId)}`}>Open client</Link>
                </li>
              ))}
            </ul>
          )}
        </div>
      ))}
    </div>
  )
}

export default ActionList
