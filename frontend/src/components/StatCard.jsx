function StatCard({ label, value, icon, caption, hint }) {
  return (
    <div className="stat-card" title={hint}>
      <span className="material-symbols-outlined stat-icon" aria-hidden="true">{icon}</span>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
      {caption && <div className="stat-caption">{caption}</div>}
    </div>
  )
}

export default StatCard
