// The app's loading indicator: the progress_activity symbol, turning, as the
// activity feed shows it. prefers-reduced-motion stops the turning (index.css).
// The label is read out; hideLabel keeps it for screen readers only, where a
// visible word would not fit, such as in a stat card.

function Spinner({ label = 'Loading…', hideLabel = false }) {
  return (
    <span className="spinner" role="status">
      <span className="material-symbols-outlined" aria-hidden="true">progress_activity</span>
      <span className={hideLabel ? 'visually-hidden' : undefined}>{label}</span>
    </span>
  )
}

export default Spinner
