// Builds the event report as a PDF in the browser, for the agent to keep or share. It holds only what the
// report itself holds: no client names, accounts or holdings. jsPDF is loaded only when Download is pressed.

const MARGIN = 48
const LINE = 1.35

// The built-in PDF font covers Latin-1 plus a few typographic marks; anything else would print as junk.
const WINANSI_EXTRA = '–—‘’“”…€'
const printable = (text) => [...text].map((ch) => (ch.charCodeAt(0) <= 0xFF || WINANSI_EXTRA.includes(ch) ? ch : '?')).join('')
const clean = (value) => printable(String(value ?? '').replace(/\s+/g, ' ').trim())

/** The text of the report as ordered blocks, so the page and the tests share one definition of the layout. */
export function reportBlocks(report) {
  const body = report.report || {}
  const cited = (list) => (list || []).map((item) => clean(item.text)).filter(Boolean)
  const blocks = [{ type: 'title', text: clean(body.headline) || 'Event report' }]
  blocks.push({
    type: 'note',
    text: `Version ${report.version}${report.versionCount ? ` of ${report.versionCount}` : ''} - `
      + (report.schemaVersion === 'briefing-v1-legacy' ? 'migrated from an earlier briefing' : 'AI-written from public sources, checked before it is shown'),
  })
  const summary = cited(body.executiveSummary)
  if (summary.length) blocks.push({ type: 'heading', text: 'Summary' }, ...summary.map((text) => ({ type: 'paragraph', text })))
  if ((body.keyFacts || []).length) {
    blocks.push({ type: 'heading', text: 'Key facts' })
    body.keyFacts.forEach((fact) => blocks.push({ type: 'bullet', text: `${clean(fact.label)}: ${clean(fact.value)}. ${clean(fact.context)}`.trim() }))
  }
  if ((body.whyItMatters || []).length) {
    blocks.push({ type: 'heading', text: 'Why it matters' })
    body.whyItMatters.forEach((item) => blocks.push({
      type: 'bullet', text: item.kind === 'POTENTIAL_IMPLICATION' ? `Potential implication: ${clean(item.text)}` : clean(item.text),
    }))
  }
  if ((body.whatToMonitor || []).length) {
    blocks.push({ type: 'heading', text: 'What to monitor' })
    body.whatToMonitor.forEach((item) => blocks.push({ type: 'bullet', text: clean(item) }))
  }
  if (body.uncertainty) blocks.push({ type: 'heading', text: 'Uncertainty' }, { type: 'paragraph', text: clean(body.uncertainty) })
  if ((report.sources || []).length) {
    blocks.push({ type: 'heading', text: 'Sources' })
    report.sources.forEach((source, index) => blocks.push({
      type: 'bullet',
      text: `[${index + 1}] ${clean(source.title)} (${clean(source.domain)})${source.usedInReport ? '' : ' - linked, not used in the summary'}`,
      link: source.url,
    }))
  }
  if (report.disclaimer) blocks.push({ type: 'note', text: clean(report.disclaimer) })
  return blocks
}

export function reportFileName(report) {
  const slug = clean(report.report?.headline).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60)
  return `${slug || 'event-report'}-v${report.version}.pdf`
}

/** The PDF document for a report; the caller saves it. */
export async function buildReportPdf(report) {
  const { jsPDF } = await import('jspdf')
  const doc = new jsPDF({ unit: 'pt', format: 'a4' })
  const width = doc.internal.pageSize.getWidth() - MARGIN * 2
  const bottom = doc.internal.pageSize.getHeight() - MARGIN
  let y = MARGIN

  const write = (text, { size = 10.5, style = 'normal', indent = 0, gap = 6, link, colour = [20, 20, 20] }) => {
    doc.setFont('helvetica', style)
    doc.setFontSize(size)
    doc.setTextColor(...colour)
    const lines = doc.splitTextToSize(text, width - indent)
    const height = size * LINE
    lines.forEach((line) => {
      if (y + height > bottom) { doc.addPage(); y = MARGIN }
      if (link) doc.textWithLink(line, MARGIN + indent, y + size, { url: link })
      else doc.text(line, MARGIN + indent, y + size)
      y += height
    })
    y += gap
  }

  reportBlocks(report).forEach((block) => {
    if (block.type === 'title') write(block.text, { size: 18, style: 'bold', gap: 4 })
    else if (block.type === 'heading') { y += 6; write(block.text, { size: 12, style: 'bold', gap: 3 }) }
    else if (block.type === 'bullet') write(`- ${block.text}`, { indent: 10, gap: 3, link: block.link, colour: block.link ? [20, 70, 160] : [20, 20, 20] })
    else if (block.type === 'note') write(block.text, { size: 9, colour: [90, 90, 90] })
    else write(block.text, {})
  })

  const pages = doc.getNumberOfPages()
  for (let page = 1; page <= pages; page += 1) {
    doc.setPage(page)
    doc.setFontSize(8)
    doc.setTextColor(120, 120, 120)
    doc.text(`Page ${page} of ${pages}`, doc.internal.pageSize.getWidth() - MARGIN, doc.internal.pageSize.getHeight() - 24, { align: 'right' })
  }
  return doc
}

export async function downloadReportPdf(report) {
  const doc = await buildReportPdf(report)
  doc.save(reportFileName(report))
}
