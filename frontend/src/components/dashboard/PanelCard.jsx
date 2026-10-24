// A dashboard panel. Every panel carries its own status (dashboard-api.yaml):
// a 200 response can still hold UNAVAILABLE panels, so each one renders its
// own state instead of the page failing as a whole.

import Spinner from '../Spinner.jsx'

const REASONS = {
  upstream_not_deployed: 'The source service is not deployed yet.',
  upstream_timeout: 'The source service did not answer in time.',
  upstream_error: 'The source service returned an error.',
  upstream_forbidden: 'You do not have access to this figure.',
  unsupported_window: 'Only shown for the last 7, 30 or 90 days.',
}

function PanelCard({ title, panel, action, className = '', children }) {
  // panelOf gives undefined until the panel's response arrives.
  const loading = panel === undefined
  const unavailable = panel && panel.status !== 'OK'

  return (
    <section className={`card panel ${className}`}>
      <div className="panel-header">
        <h2>{title}</h2>
        {action && !unavailable && <div className="panel-action">{action}</div>}
      </div>
      {loading ? (
        <div className="panel-loading">
          <Spinner label="Loading figures…" />
        </div>
      ) : unavailable ? (
        <div className="panel-unavailable" role="status">
          <span className="material-symbols-outlined" aria-hidden="true">cloud_off</span>
          <div>
            <p className="panel-unavailable-title">Data unavailable</p>
            <p>{REASONS[panel.reason] ?? 'The figures could not be loaded.'}</p>
          </div>
        </div>
      ) : (
        <div className="panel-body">{children}</div>
      )}
    </section>
  )
}

export default PanelCard
