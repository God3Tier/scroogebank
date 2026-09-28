function StatCard({ label, value, icon }) {
  return (
    <div className="stat-card">
      <span className="material-symbols-outlined stat-icon" aria-hidden="true">{icon}</span>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
    </div>
  )
}

export default StatCard
