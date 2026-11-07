import { Link } from 'react-router-dom'
import SearchSources from '../components/SearchSources.jsx'
import TickerReports from '../components/TickerReports.jsx'

// Agents review one report per ticker, with the clients it may affect, then see what is searched and when.
// Admins see the same per-ticker view, every agent's, read only (no ticks, decisions, approval or notifying), and can remove a website an agent added.
function EventAlerts({ readOnly = false }) {
  if (readOnly) {
    return (
      <>
        <div className="back-row"><Link to="/admin">Back to Admin Home</Link></div>
        <TickerReports readOnly />
        <SearchSources readOnly />
      </>
    )
  }
  return (
    <>
      <div className="back-row"><Link to="/agent">Back to Agent Home</Link></div>
      <TickerReports />
      <SearchSources />
    </>
  )
}

export default EventAlerts
