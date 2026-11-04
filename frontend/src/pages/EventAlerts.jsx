import AlertWorkspace from '../components/AlertWorkspace.jsx'
import EventBriefings from '../components/EventBriefings.jsx'
import SearchSources from '../components/SearchSources.jsx'

// Agents start from one briefing per event; the per-client list stays available
// beneath it, then the sites searched for each company. Admins have read-only
// access to the per-client list and can remove a search site an agent added.
function EventAlerts({ readOnly = false }) {
  if (readOnly) {
    return (
      <>
        <AlertWorkspace readOnly />
        <SearchSources readOnly />
      </>
    )
  }
  return (
    <>
      <EventBriefings />
      <details className="client-alert-list">
        <summary>All client alerts</summary>
        <AlertWorkspace readOnly={false} />
      </details>
      <SearchSources />
    </>
  )
}

export default EventAlerts
