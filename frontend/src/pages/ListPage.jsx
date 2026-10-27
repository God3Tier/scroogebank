import { Link } from 'react-router-dom'

// A whole list on its own page, opened from a View all link. back is the page
// that link is on.
function ListPage({ title, subtitle, back, backLabel = 'Back to Home', children }) {
  return (
    <div className="page">
      <div className="back-row"><Link to={back}>{backLabel}</Link></div>
      <header className="page-header">
        <h1>{title}</h1>
        <p className="page-subtitle">{subtitle}</p>
      </header>
      {children}
    </div>
  )
}

export default ListPage
