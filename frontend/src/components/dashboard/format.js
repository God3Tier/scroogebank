// Display helpers for dashboard figures.

const numberFormat = new Intl.NumberFormat('en-SG')

export function formatNumber(value) {
  return numberFormat.format(value)
}

export function formatPercent(ratio) {
  return `${(ratio * 100).toFixed(1)}%`
}

// Decimal strings from the API, shown in SGD. Compact for tiles, full in tables.
export function formatSGD(decimal, { compact = false } = {}) {
  const value = Number(decimal)
  if (compact && value >= 1_000_000) return `SGD ${(value / 1_000_000).toFixed(2)}M`
  if (compact && value >= 10_000) return `SGD ${(value / 1_000).toFixed(1)}K`
  return `SGD ${value.toLocaleString('en-SG', { maximumFractionDigits: 0 })}`
}

// Transaction values carry no currency column, so they stay unlabelled.
export function formatValue(decimal, { compact = false } = {}) {
  const value = Number(decimal)
  if (compact && value >= 1_000_000) return `${(value / 1_000_000).toFixed(2)}M`
  if (compact && value >= 10_000) return `${(value / 1_000).toFixed(1)}K`
  return value.toLocaleString('en-SG', { maximumFractionDigits: 0 })
}

export function formatDate(iso) {
  return new Date(`${iso}T00:00:00Z`).toLocaleDateString('en-SG', { day: 'numeric', month: 'short', timeZone: 'UTC' })
}

export function formatTime(isoDateTime) {
  return new Date(isoDateTime).toLocaleTimeString('en-SG', { hour: '2-digit', minute: '2-digit' })
}

export function daysSince(isoDateTime, today) {
  return Math.round((new Date(`${today}T00:00:00Z`) - new Date(isoDateTime)) / 86400000)
}

export function formatAge(seconds) {
  if (seconds < 60) return `${seconds} s`
  if (seconds < 3600) return `${Math.round(seconds / 60)} min`
  return `${(seconds / 3600).toFixed(1)} h`
}

// Turns { KEY: count } into chart rows, keeping the given key order.
export function toRows(map, labels = {}) {
  return Object.entries(map).map(([key, value]) => ({ key, name: labels[key] ?? key, value }))
}
