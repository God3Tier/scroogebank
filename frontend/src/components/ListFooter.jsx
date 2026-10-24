import { Link } from 'react-router-dom'

// The foot of a list. On a dashboard the list shows its first ten rows and
// links to the whole list on its own page; there, the pager moves through it
// ten rows at a time.

export function ViewAll({ to, label }) {
  return (
    <div className="list-footer">
      <Link className="list-footer__link" to={to}>{label}</Link>
    </div>
  )
}

// list is what usePagedList returns. A list the service does not count shows
// no "of N" until its last page has been reached.
export function Pager({ list, label = 'Pages' }) {
  const { page, totalPages, hasNext, loading, go } = list
  const atStart = loading || page === 0
  const atEnd = loading || !hasNext

  return (
    <nav className="list-footer pager" aria-label={label}>
      <button type="button" aria-label="First page" title="First page" disabled={atStart} onClick={() => go(0)}>«</button>
      <button type="button" aria-label="Previous page" title="Previous page" disabled={atStart} onClick={() => go(page - 1)}>‹</button>
      <span className="pager__position" aria-live="polite">
        Page {page + 1}{totalPages !== null && ` of ${totalPages}`}
      </span>
      <button type="button" aria-label="Next page" title="Next page" disabled={atEnd} onClick={() => go(page + 1)}>›</button>
      <button type="button" aria-label="Last page" title="Last page" disabled={atEnd} onClick={() => go('last')}>»</button>
    </nav>
  )
}
