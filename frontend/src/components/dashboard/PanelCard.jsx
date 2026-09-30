// A dashboard panel. Every panel carries its own status (dashboard-api.yaml):
// a 200 response can still hold UNAVAILABLE panels, so each one renders its
// own state instead of the page failing as a whole.

const REASONS = {
  upstream_not_deployed: 'The source service is not deployed yet.',
  upstream_timeout: 'The source service did not answer in time.',
  upstream_error: 'The source service returned an error.',
  upstream_forbidden: 'You do not have access to this figure.',
}

function PanelCard({ title, panel, action, className = '', children }) {
  const unavailable = panel && panel.status !== 'OK'

  return (
    <section className={`card panel ${className}`}>
      <div className="panel-header">
        <h2>{title}</h2>
        {action && !unavailable && <div className="panel-action">{action}</div>}
      </div>
      {unavailable ? (
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
