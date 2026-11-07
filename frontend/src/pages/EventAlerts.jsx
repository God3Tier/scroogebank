import AlertWorkspace from '../components/AlertWorkspace.jsx'
import SearchSources from '../components/SearchSources.jsx'
import SentOutList from '../components/SentOutList.jsx'
import TickerReports from '../components/TickerReports.jsx'

// Agents review one report per ticker, with the clients it may affect, then see what is searched and when.
// Admins have read-only access to the per-client alerts and the Sent out view, and can remove a website an agent added.
function EventAlerts({ readOnly = false }) {
  if (readOnly) {
    return (
      <>
        <AlertWorkspace readOnly />
        <div className="alerts-page"><SentOutList readOnly /></div>
        <SearchSources readOnly />
      </>
    )
  }
  return (
    <>
      <TickerReports />
      <SearchSources />
    </>
  )
}

export default EventAlerts
