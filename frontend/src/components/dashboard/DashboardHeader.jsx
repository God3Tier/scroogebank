import { formatDate, formatTime } from './format.js'

const WINDOW_OPTIONS = [7, 30, 90]

function DashboardHeader({ title, subtitle, scopeLabel, days, onDaysChange, response }) {
  return (
    <header className="dash-header">
      <div>
        <h1>{title}</h1>
        <p className="page-subtitle">{subtitle}</p>
      </div>
      <div className="dash-controls">
        <span className="scope-badge">
          <span className="material-symbols-outlined" aria-hidden="true">
            {response.scope === 'ADMIN' ? 'account_balance' : 'person'}
          </span>
          {scopeLabel}
        </span>
        <div className="segmented" role="group" aria-label="Reporting window">
          {WINDOW_OPTIONS.map((option) => (
            <button
              key={option}
              type="button"
              aria-pressed={days === option}
              onClick={() => onDaysChange(option)}
            >
              {option} days
            </button>
          ))}
        </div>
        <p className="generated-at">
          {formatDate(response.window.from)} – {formatDate(response.window.to)} · Figures as of{' '}
          {formatTime(response.generatedAt)} · cached up to 60 s
        </p>
      </div>
    </header>
  )
}

export default DashboardHeader
