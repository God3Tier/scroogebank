// Figures the dashboards derive from panel data.

// A share, or null when there is nothing to divide by, so the tile shows no
// figure rather than 0% (ADR-0038).
export function ratio(part, whole) {
  return whole ? part / whole : null
}

// Joins the daily client and transaction series by date, with a row for
// every day of the window. The services send only the days that had any, so
// a day missing from both is a zero, and the chart's days stay evenly spaced
// in time. Either series may be missing when its panel is unavailable.
export function dailyTrend(clientsDaily = [], transactionsDaily = [], { from, to } = {}) {
  const byDate = new Map()
  for (const date of daysBetween(from, to)) {
    byDate.set(date, { date, clients: 0, transactions: 0 })
  }
  for (const day of clientsDaily ?? []) {
    const row = byDate.get(day.date) ?? { date: day.date, clients: 0, transactions: 0 }
    row.clients = day.count
    byDate.set(day.date, row)
  }
  for (const day of transactionsDaily ?? []) {
    const row = byDate.get(day.date) ?? { date: day.date, clients: 0, transactions: 0 }
    row.transactions = day.count
    byDate.set(day.date, row)
  }
  return [...byDate.values()].sort((a, b) => a.date.localeCompare(b.date))
}

// Every YYYY-MM-DD date from from to to, both included.
function daysBetween(from, to) {
  if (!from || !to) return []
  const dates = []
  for (const day = new Date(`${from}T00:00:00Z`); day <= new Date(`${to}T00:00:00Z`); day.setUTCDate(day.getUTCDate() + 1)) {
    dates.push(day.toISOString().slice(0, 10))
  }
  return dates
}
