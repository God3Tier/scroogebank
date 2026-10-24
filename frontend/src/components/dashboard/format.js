// Display helpers for dashboard figures.

const numberFormat = new Intl.NumberFormat('en-SG')

// Shown for a figure the API left out: a rate with nothing to divide, or a
// value in more than one currency, which is never added up (ADR-0038).
export const NO_FIGURE = '—'

const missing = (value) => value === null || value === undefined || value === ''

export function formatNumber(value) {
  return missing(value) ? NO_FIGURE : numberFormat.format(value)
}

export function formatPercent(ratio) {
  return missing(ratio) ? NO_FIGURE : `${(ratio * 100).toFixed(1)}%`
}

// Decimal strings from the API, shown with their currency. Compact for tiles,
// full in tables.
export function formatMoney(decimal, currency, { compact = false } = {}) {
  if (missing(decimal)) return NO_FIGURE
  const value = Number(decimal)
  const prefix = currency ? `${currency} ` : ''
  if (compact && value >= 1_000_000) return `${prefix}${(value / 1_000_000).toFixed(2)}M`
  if (compact && value >= 10_000) return `${prefix}${(value / 1_000).toFixed(1)}K`
  return `${prefix}${value.toLocaleString('en-SG', { maximumFractionDigits: 0 })}`
}

// Amounts by currency, such as booked initial deposits, one per currency and
// never added together (ADR-0062). An empty map is no amounts at all.
export function formatAmounts(byCurrency, options) {
  if (missing(byCurrency)) return NO_FIGURE
  const entries = Object.entries(byCurrency).sort(([a], [b]) => a.localeCompare(b))
  if (entries.length === 0) return formatNumber(0)
  return entries.map(([currency, decimal]) => formatMoney(decimal, currency, options)).join(' · ')
}

// A total summed only when its rows share one currency, such as transaction
// value: shown in that currency, or left out when there are several.
export function formatTotal(decimal, currencies, options) {
  if ((currencies?.length ?? 0) > 1) return NO_FIGURE
  return formatMoney(decimal, currencies?.[0], options)
}

// Says why formatTotal left a total out.
export function currenciesNote(currencies) {
  return (currencies?.length ?? 0) > 1 ? `In ${currencies.join(', ')}; not added` : undefined
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
  return Object.entries(map ?? {}).map(([key, value]) => ({ key, name: labels[key] ?? key, value }))
}
