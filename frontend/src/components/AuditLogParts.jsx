// Pieces the two audit tables on the admin Audit Activity page share, styled
// after the Stitch "Transaction Log" screen: a date over its time, a tone
// badge for the recorded action, and an expandable payload.

const ACTION_TONES = { CREATE: 'badge-active', DELETE: 'badge-failed' }

export function AuditTime({ value }) {
  if (!value) return '—'
  const date = new Date(value)
  return (
    <time className="audit-log__time" dateTime={value}>
      <span>{date.toLocaleDateString('en-SG', { dateStyle: 'medium' })}</span>
      <span className="audit-log__clock">{date.toLocaleTimeString('en-SG', { timeStyle: 'medium' })}</span>
    </time>
  )
}

// Colour follows the verb; the verb is always written out beside it.
export function AuditAction({ action }) {
  if (!action) return '—'
  const tone = ACTION_TONES[String(action).toUpperCase()] || ''
  return <span className={`badge ${tone}`.trim()}>{action}</span>
}

export function AuditPayload({ label, value }) {
  return (
    <details className="audit-log__details">
      <summary>
        <span className="material-symbols-outlined" aria-hidden="true">chevron_right</span>
        {label}
      </summary>
      <pre className="audit-log-payload">
        {typeof value === 'string' ? value : JSON.stringify(value, null, 2)}
      </pre>
    </details>
  )
}
