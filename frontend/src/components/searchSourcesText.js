// Wording shared by the Search sources parts (ADR-0081, ADR-0082).

export function problemText(error) {
  if (error.status === 503) return 'Client records cannot be verified right now. Try again shortly.'
  return error.message || 'Something went wrong. Try again.'
}

const TIME = { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Singapore' }

export function clockTime(value) {
  return new Intl.DateTimeFormat('en-SG', TIME).format(new Date(value))
}

export function formatDate(value) {
  return new Intl.DateTimeFormat('en-SG', { day: '2-digit', month: 'short', year: 'numeric', timeZone: 'Asia/Singapore' })
    .format(new Date(value))
}

// What happened to a search that was asked for, in words. tickers maps a company id to its ticker.
export function searchText(search, { domain, tickers = {} } = {}) {
  if (!search) return ''
  const name = (id) => tickers[id] || id
  const waiting = (search.cooldown || []).map((w) => `${name(w.instrumentId)} (again at ${clockTime(w.availableAt)})`)
  const using = domain ? ` on ${domain}` : ' on every website'
  switch (search.outcome) {
    case 'STARTED': {
      const count = search.started.length
      const base = `Searching ${count} ${count === 1 ? 'company' : 'companies'}${using} over the last ${search.lookbackDays} days. Results appear on this page in a few minutes.`
      return waiting.length === 0 ? base : `${base} Searched too recently, so skipped: ${waiting.join(', ')}.`
    }
    case 'COOLDOWN':
      return `Searched a moment ago, so nothing new was started. Available again: ${waiting.join(', ')}.`
    case 'DAILY_LIMIT':
      return 'The daily limit for searches by hand is reached. The next automatic search will still run.'
    case 'NOTHING_TO_SEARCH':
      return 'No company to search yet. Record a client\'s holdings first.'
    case 'FAILED':
      return 'The search could not start. The next automatic search will still run.'
    default:
      return ''
  }
}
