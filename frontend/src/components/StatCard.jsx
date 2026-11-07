import Spinner from './Spinner.jsx'

function StatCard({ label, value, icon, caption, hint, loading = false }) {
  return (
    <div className="stat-card" title={hint}>
      <span className="material-symbols-outlined stat-icon" aria-hidden="true">{icon}</span>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{loading ? <Spinner label={`Loading ${label}`} hideLabel /> : value}</div>
      {caption && <div className="stat-caption">{caption}</div>}
    </div>
  )
}

export default StatCard
