import { describe, expect, it } from 'vitest'

import { buildReportPdf, reportBlocks, reportFileName } from './reportPdf.js'

const report = {
  version: 2,
  versionCount: 2,
  schemaVersion: 'alert-client-report-v2',
  disclaimer: 'Not investment advice.',
  portfolioLines: { 'SGX-D05': 'This appears in portfolios you manage.' },
  sources: [
    { title: 'DBS results', url: 'https://a.example/1', domain: 'a.example', usedInReport: true },
    { title: 'Other story', url: 'https://b.example/2', domain: 'b.example', usedInReport: false },
  ],
  report: {
    headline: "DBS's Q3 results: profit up 6%",
    executiveSummary: [{ text: 'DBS reported higher profit [1].' }],
    keyFacts: [{ label: 'Net profit', value: 'SGD 2.9bn', context: 'Up 6% on the year' }],
    whyItMatters: [{ kind: 'POTENTIAL_IMPLICATION', text: 'Dividend may rise.' }, { kind: 'FACT', text: 'Banks are in focus.' }],
    whatToMonitor: ['Next guidance'],
    uncertainty: 'One source only.',
  },
}

describe('report PDF', () => {
  it('lays out the report in reading order with sources and the disclaimer, and no client or portfolio text', () => {
    const blocks = reportBlocks(report)
    expect(blocks[0]).toEqual({ type: 'title', text: "DBS's Q3 results: profit up 6%" })
    expect(blocks.map((b) => b.text)).toEqual(expect.arrayContaining([
      'Summary', 'DBS reported higher profit [1].', 'Net profit: SGD 2.9bn. Up 6% on the year',
      'Potential implication: Dividend may rise.', 'Next guidance', 'One source only.',
      '[1] DBS results (a.example)', '[2] Other story (b.example) - linked, not used in the summary', 'Not investment advice.',
    ]))
    expect(JSON.stringify(blocks)).not.toContain('portfolios you manage')
  })

  it('names the file after the headline and version', () => {
    expect(reportFileName(report)).toBe('dbs-s-q3-results-profit-up-6-v2.pdf')
    expect(reportFileName({ version: 1, report: {} })).toBe('event-report-v1.pdf')
  })

  it('builds a real PDF', async () => {
    const doc = await buildReportPdf(report)
    expect(doc.getNumberOfPages()).toBeGreaterThanOrEqual(1)
    expect(String.fromCharCode(...new Uint8Array(doc.output('arraybuffer')).slice(0, 5))).toBe('%PDF-')
  })
})
