import AlertWorkspace from '../components/AlertWorkspace.jsx'
import EventBriefings from '../components/EventBriefings.jsx'

// Agents start from one briefing per event; the per-client list stays available
// beneath it. Admins have read-only access to the per-client list only.
function EventAlerts({ readOnly = false }) {
  if (readOnly) return <AlertWorkspace readOnly />
  return (
    <>
      <EventBriefings />
      <details className="client-alert-list">
        <summary>All client alerts</summary>
        <AlertWorkspace readOnly={false} />
      </details>
    </>
  )
}

export default EventAlerts
