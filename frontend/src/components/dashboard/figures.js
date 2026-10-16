// Figures the dashboards derive from panel data.

// A share, or null when there is nothing to divide by, so the tile shows no
// figure rather than 0% (ADR-0038).
export function ratio(part, whole) {
  return whole ? part / whole : null
}

// Joins the daily client and transaction series by date. Either may be
// missing when its panel is unavailable.
export function dailyTrend(clientsDaily = [], transactionsDaily = []) {
  const byDate = new Map()
  for (const day of clientsDaily ?? []) {
    byDate.set(day.date, { date: day.date, clients: day.count, transactions: 0 })
  }
  for (const day of transactionsDaily ?? []) {
    const row = byDate.get(day.date) ?? { date: day.date, clients: 0, transactions: 0 }
    row.transactions = day.count
    byDate.set(day.date, row)
  }
  return [...byDate.values()].sort((a, b) => a.date.localeCompare(b.date))
}
