import AlertWorkspace from '../components/AlertWorkspace.jsx'
import EventBriefings from '../components/EventBriefings.jsx'
import SearchSources from '../components/SearchSources.jsx'

// Agents start from one briefing per event, then the per-client alerts (always open), then what is
// searched and when. Admins have read-only access to the per-client alerts and can remove a website an
// agent added.
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
      <AlertWorkspace readOnly={false} />
      <SearchSources />
    </>
  )
}

export default EventAlerts
