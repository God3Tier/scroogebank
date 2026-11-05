import AlertWorkspace from '../components/AlertWorkspace.jsx'
import SearchSources from '../components/SearchSources.jsx'

// Agents review one alert per client and event, each with the report combined from its sources, then see what is
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
      <AlertWorkspace readOnly={false} />
      <SearchSources />
    </>
  )
}

export default EventAlerts
