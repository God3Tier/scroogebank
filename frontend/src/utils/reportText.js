// The report as one block of plain text for the Edit box on Event Alerts, and back again (news-alert-drafts plan 5.2).
// The sections have the shape the server takes: executiveSummary and whyItMatters are lists of text, keyFacts is a list
// of { label, value, context }, whatToMonitor is a list of text and uncertainty is text. Source citations are not shown
// here: each stays with its item by position, so the number of summary sentences, key facts and why-it-matters points
// cannot change. What to monitor can have 2 to 4 lines.

const HEADINGS = [
  { key: 'executiveSummary', title: 'EXECUTIVE SUMMARY', name: 'Executive summary' },
  { key: 'keyFacts', title: 'KEY FACTS', name: 'Key facts' },
  { key: 'whyItMatters', title: 'WHY IT MATTERS', name: 'Why it matters' },
  { key: 'whatToMonitor', title: 'WHAT TO MONITOR', name: 'What to monitor' },
  { key: 'uncertainty', title: 'UNCERTAINTY', name: 'Uncertainty' },
]
const BY_TITLE = Object.fromEntries(HEADINGS.map((heading) => [heading.title, heading]))
const NAMES = Object.fromEntries(HEADINGS.map((heading) => [heading.key, heading.name]))

export const MONITOR_MIN = 2
export const MONITOR_MAX = 4

/** The sections as editable text. A section the report does not have (no key facts) is left out. */
export function toText(sections) {
  const lines = []
  const numbered = (items) => items.forEach((item, i) => lines.push(`${i + 1}. ${item}`))
  lines.push('EXECUTIVE SUMMARY')
  numbered(sections.executiveSummary || [])
  if ((sections.keyFacts || []).length > 0) {
    lines.push('', 'KEY FACTS')
    numbered(sections.keyFacts.map((fact) => `${fact.label} | ${fact.value} | ${fact.context}`))
  }
  lines.push('', 'WHY IT MATTERS')
  numbered(sections.whyItMatters || [])
  lines.push('', 'WHAT TO MONITOR')
  ;(sections.whatToMonitor || []).forEach((item) => lines.push(`- ${item}`))
  lines.push('', 'UNCERTAINTY', sections.uncertainty || '')
  return lines.join('\n')
}

const ITEM = /^(?:\d+[.)]|[-•*])\s+(.*)$/

/** Splits the text into headed blocks, each with its items and the line numbers they came from. */
function blocks(text) {
  const result = []
  let current = null
  let lastItem = null
  text.split(/\r?\n/).forEach((raw, index) => {
    const line = index + 1
    const trimmed = raw.trim()
    if (!trimmed) {
      lastItem = null
      return
    }
    const heading = BY_TITLE[trimmed.replace(/:$/, '').toUpperCase()]
    if (heading) {
      current = { heading, line, items: [], stray: [] }
      result.push(current)
      lastItem = null
      return
    }
    if (!current) {
      result.push({ heading: null, line, items: [], stray: [trimmed] })
      return
    }
    const item = ITEM.exec(trimmed)
    if (item && current.heading.key !== 'uncertainty') {
      lastItem = { line, text: item[1].trim() }
      current.items.push(lastItem)
    } else if (lastItem) {
      // A wrapped line belongs to the item above it.
      lastItem.text = `${lastItem.text} ${trimmed}`
    } else {
      lastItem = { line, text: trimmed }
      current.items.push(lastItem)
    }
  })
  return result
}

/**
 * The text read back into sections, checked against the report it came from.
 * Returns { sections } or { errors: [{ line, message }] }; a line is 1-based, or 0 when no one line is to blame.
 */
export function fromText(text, original) {
  const errors = []
  const parsed = blocks(text)
  parsed.filter((block) => !block.heading).forEach((block) => {
    errors.push({ line: block.line, message: 'This line is before the first heading. Start with EXECUTIVE SUMMARY.' })
  })
  const byKey = {}
  parsed.filter((block) => block.heading).forEach((block) => {
    if (byKey[block.heading.key]) {
      errors.push({ line: block.line, message: `${block.heading.title} appears twice.` })
    } else {
      byKey[block.heading.key] = block
    }
  })

  const wanted = (key) => (original?.[key] || []).length
  const needed = ['executiveSummary', 'whyItMatters', 'whatToMonitor', 'uncertainty']
  if (wanted('keyFacts') > 0) needed.splice(1, 0, 'keyFacts')
  needed.forEach((key) => {
    if (!byKey[key]) errors.push({ line: 0, message: `The ${NAMES[key].toUpperCase()} heading is missing.` })
  })
  if (!byKey.keyFacts && wanted('keyFacts') === 0) byKey.keyFacts = { items: [] }
  if (errors.length > 0) return { errors }

  const sections = {}
  const fixed = (key) => {
    const items = byKey[key].items
    const expected = wanted(key)
    if (items.length !== expected) {
      errors.push({
        line: byKey[key].line || 0,
        message: `${NAMES[key]} must keep exactly ${expected} item${expected === 1 ? '' : 's'}; there ${items.length === 1 ? 'is' : 'are'} ${items.length}.`,
      })
    }
    return items
  }

  sections.executiveSummary = fixed('executiveSummary').map((item) => item.text)
  const facts = fixed('keyFacts').map((item) => {
    const parts = item.text.split('|').map((part) => part.trim())
    if (parts.length !== 3 || parts.some((part) => !part)) {
      errors.push({ line: item.line, message: 'Key facts need Label | Value | Context, all three filled in.' })
      return { label: '', value: '', context: '' }
    }
    return { label: parts[0], value: parts[1], context: parts[2] }
  })
  sections.keyFacts = facts
  sections.whyItMatters = fixed('whyItMatters').map((item) => item.text)
  const monitor = byKey.whatToMonitor.items
  if (monitor.length < MONITOR_MIN || monitor.length > MONITOR_MAX) {
    errors.push({ line: byKey.whatToMonitor.line, message: `What to monitor needs ${MONITOR_MIN} to ${MONITOR_MAX} lines; there ${monitor.length === 1 ? 'is' : 'are'} ${monitor.length}.` })
  }
  sections.whatToMonitor = monitor.map((item) => item.text)
  sections.uncertainty = byKey.uncertainty.items.map((item) => item.text).join(' ')
  if (!sections.uncertainty) errors.push({ line: byKey.uncertainty.line, message: 'Uncertainty cannot be empty.' })

  return errors.length > 0 ? { errors } : { sections }
}

/** The line a server refusal (section and item, from 0) points at in the text, or 0 when it cannot say. */
export function lineOf(text, section, index) {
  const block = blocks(text).find((candidate) => candidate.heading?.key === section)
  if (!block) return 0
  return index >= 0 && block.items[index] ? block.items[index].line : block.line
}

export function sectionName(key) {
  return NAMES[key] || key
}
