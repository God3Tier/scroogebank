// Builds the event report as a PDF in the browser, for the agent to keep or share. It follows the layout of the
// client email in the report plan (ADR-0084), in the same order, and holds only what the report itself holds:
// no client names, accounts, holdings or scores. jsPDF is loaded only when Download is pressed.

const MARGIN = 48
const LINE = 1.35

// The built-in PDF font only covers Latin-1, so typographic marks become plain ones and anything else a "?".
const PLAIN = { '‘': "'", '’': "'", '“': '"', '”': '"', '–': '-', '—': '-', '…': '...', '€': 'EUR' }
const printable = (text) => [...text].map((ch) => PLAIN[ch] ?? (ch.charCodeAt(0) <= 0xFF ? ch : '?')).join('')
const clean = (value) => printable(String(value ?? '').replace(/\s+/g, ' ').trim())

const STAMP = new Intl.DateTimeFormat('en-GB', {
  day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Singapore',
})
const stamp = (value) => (value ? `${STAMP.format(new Date(value))} SGT` : 'not supplied')

/** The cited text with its [n] marks: the number is the source's place in the linked-sources list. */
export function cited(text, refs, sources) {
  const marks = (refs || [])
    .map((ref) => sources.findIndex((source) => source.id === ref))
    .filter((index) => index >= 0)
    .map((index) => ` [${index + 1}]`)
    .join('')
  return `${clean(text)}${marks}`
}

/**
 * The report as ordered blocks, in the order of the client email: label, headline, summary, key facts, why it matters,
 * market context (only when present), what to monitor, why you received this, linked sources, disclaimer, contact.
 * {@code agent} is the signed-in relationship manager's {name, email}.
 */
export function reportBlocks(report, agent = {}) {
  const body = report.report || {}
  const sources = report.sources || []
  const blocks = [
    { type: 'kicker', text: 'PORTFOLIO INTELLIGENCE / COMPANY EVENT' },
    { type: 'title', text: clean(body.headline) || 'Company event' },
  ]
  const summary = (body.executiveSummary || []).map((item) => cited(item.text, item.sourceRefs, sources)).filter(Boolean)
  if (summary.length) blocks.push({ type: 'paragraph', text: summary.join(' ') })

  if ((body.keyFacts || []).length) {
    blocks.push({ type: 'heading', text: 'Key facts and numbers' })
    body.keyFacts.forEach((fact) => blocks.push({
      type: 'bullet', text: `${clean(fact.label)}: ${clean(fact.value)}. ${cited(fact.context, fact.sourceRefs, sources)}`,
    }))
  }
  if ((body.whyItMatters || []).length) {
    blocks.push({ type: 'heading', text: 'Why it matters' })
    body.whyItMatters.forEach((item) => blocks.push({
      type: 'bullet',
      text: `${item.kind === 'POTENTIAL_IMPLICATION' ? 'Potential implication: ' : ''}${cited(item.text, item.sourceRefs, sources)}`,
    }))
  }
  if (body.marketContext && clean(body.marketContext)) {
    blocks.push({ type: 'heading', text: 'Market context' }, { type: 'paragraph', text: clean(body.marketContext) })
  }
  if ((body.whatToMonitor || []).length) {
    blocks.push({ type: 'heading', text: 'What to monitor' })
    body.whatToMonitor.forEach((item) => blocks.push({ type: 'bullet', text: clean(item) }))
  }
  if (body.uncertainty && clean(body.uncertainty)) blocks.push({ type: 'paragraph', text: `Uncertainty: ${clean(body.uncertainty)}` })

  const lines = [...new Set(Object.values(report.portfolioLines || {}).map(clean).filter(Boolean))]
  if (lines.length) {
    blocks.push({ type: 'heading', text: 'Why you received this' })
    lines.forEach((line) => blocks.push({ type: 'paragraph', text: line }))
  }
  if (sources.length) {
    blocks.push({ type: 'heading', text: 'Linked sources' })
    sources.forEach((source, index) => blocks.push({
      type: 'source',
      text: `[${index + 1}] ${clean(source.title)}`,
      link: source.url,
      detail: `Published ${stamp(source.publishedAt)}; retrieved ${stamp(source.retrievedAt)}`,
    }))
  }
  if (report.disclaimer) blocks.push({ type: 'note', text: clean(report.disclaimer) })
  const contact = [clean(agent.name), clean(agent.email)].filter(Boolean).join(', ')
  if (contact) blocks.push({ type: 'note', text: `Your relationship manager: ${contact}.` })
  return blocks
}

export function reportFileName(report) {
  const slug = clean(report.report?.headline).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60)
  return `${slug || 'event-report'}-v${report.version}.pdf`
}

/** The PDF document for a report; the caller saves it. */
export async function buildReportPdf(report, agent = {}) {
  const { jsPDF } = await import('jspdf')
  const doc = new jsPDF({ unit: 'pt', format: 'a4' })
  const width = doc.internal.pageSize.getWidth() - MARGIN * 2
  const bottom = doc.internal.pageSize.getHeight() - MARGIN
  let y = MARGIN

  const write = (text, { size = 10.5, style = 'normal', indent = 0, gap = 6, link, colour = [20, 20, 20] }) => {
    doc.setFont('helvetica', style)
    doc.setFontSize(size)
    doc.setTextColor(...colour)
    const height = size * LINE
    doc.splitTextToSize(text, width - indent).forEach((line) => {
      if (y + height > bottom) { doc.addPage(); y = MARGIN }
      if (link) doc.textWithLink(line, MARGIN + indent, y + size, { url: link })
      else doc.text(line, MARGIN + indent, y + size)
      y += height
    })
    y += gap
  }

  reportBlocks(report, agent).forEach((block) => {
    if (block.type === 'kicker') write(block.text, { size: 9, style: 'bold', gap: 8, colour: [90, 90, 90] })
    else if (block.type === 'title') write(block.text, { size: 18, style: 'bold', gap: 8 })
    else if (block.type === 'heading') { y += 6; write(block.text, { size: 12, style: 'bold', gap: 3 }) }
    else if (block.type === 'bullet') write(`- ${block.text}`, { indent: 10, gap: 3 })
    else if (block.type === 'source') {
      write(block.text, { indent: 10, gap: 0, link: block.link, colour: [20, 70, 160] })
      write(block.detail, { size: 9, indent: 10, gap: 4, colour: [90, 90, 90] })
    } else if (block.type === 'note') write(block.text, { size: 9, colour: [90, 90, 90], gap: 3 })
    else write(block.text, {})
  })

  const pages = doc.getNumberOfPages()
  for (let page = 1; page <= pages; page += 1) {
    doc.setPage(page)
    doc.setFontSize(8)
    doc.setTextColor(120, 120, 120)
    doc.text(`Report version ${report.version}`, MARGIN, doc.internal.pageSize.getHeight() - 24)
    doc.text(`Page ${page} of ${pages}`, doc.internal.pageSize.getWidth() - MARGIN, doc.internal.pageSize.getHeight() - 24, { align: 'right' })
  }
  return doc
}

export async function downloadReportPdf(report, agent = {}) {
  const doc = await buildReportPdf(report, agent)
  doc.save(reportFileName(report))
}
