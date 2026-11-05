import { describe, expect, it } from 'vitest'

import { buildReportPdf, reportBlocks, reportFileName } from './reportPdf.js'

const report = {
  version: 2,
  versionCount: 2,
  schemaVersion: 'alert-client-report-v2',
  disclaimer: 'Not investment advice.',
  portfolioLines: { 'SGX-D05': 'Your portfolio has exposure to DBS, which may be affected by this event.' },
  sources: [
    { id: 's1', title: 'DBS results', url: 'https://a.example/1', domain: 'a.example', publishedAt: '2026-11-02T01:00:00Z', retrievedAt: '2026-11-02T02:30:00Z', usedInReport: true },
    { id: 's2', title: 'Profit up', url: 'https://b.example/2', domain: 'b.example', publishedAt: null, retrievedAt: '2026-11-02T02:30:00Z', usedInReport: false },
  ],
  report: {
    headline: "DBS's Q3 results: profit up 6%",
    executiveSummary: [{ text: 'DBS reported higher profit.', sourceRefs: ['s1'] }, { text: 'Analysts agree.', sourceRefs: ['s1', 's2'] }],
    keyFacts: [{ label: 'Net profit', value: 'SGD 2.9bn', context: 'Up 6% on the year', sourceRefs: ['s1'] }],
    whyItMatters: [{ kind: 'POTENTIAL_IMPLICATION', text: 'Dividend may rise.', sourceRefs: [] }, { kind: 'FACT', text: 'Banks are in focus.', sourceRefs: ['s2'] }],
    marketContext: null,
    whatToMonitor: ['Next guidance', 'Analyst target changes'],
    uncertainty: 'One source only.',
  },
}

describe('report PDF', () => {
  it('follows the order of the client email, with citation numbers, times and the contact line', () => {
    const blocks = reportBlocks(report, { name: 'Alice Agent', email: 'agent1@example.test' })

    expect(blocks.filter((b) => b.type === 'kicker' || b.type === 'heading').map((b) => b.text)).toEqual([
      'PORTFOLIO INTELLIGENCE / COMPANY EVENT', 'Key facts and numbers', 'Why it matters', 'What to monitor', 'Why you received this', 'Linked sources',
    ])
    expect(blocks.find((b) => b.type === 'title').text).toBe("DBS's Q3 results: profit up 6%")
    expect(blocks.find((b) => b.type === 'paragraph').text).toBe('DBS reported higher profit. [1] Analysts agree. [1] [2]')
    expect(blocks.map((b) => b.text)).toEqual(expect.arrayContaining([
      'Net profit: SGD 2.9bn. Up 6% on the year [1]',
      'Potential implication: Dividend may rise.', 'Banks are in focus. [2]',
      'Uncertainty: One source only.',
      'Your portfolio has exposure to DBS, which may be affected by this event.',
      'Not investment advice.', 'Your relationship manager: Alice Agent, agent1@example.test.',
    ]))
    const sources = blocks.filter((b) => b.type === 'source')
    expect(sources[0]).toMatchObject({ text: '[1] DBS results', link: 'https://a.example/1' })
    expect(sources[0].detail).toBe('Published 2 Nov 2026, 09:00 SGT; retrieved 2 Nov 2026, 10:30 SGT')
    expect(sources[1].detail).toBe('Published not supplied; retrieved 2 Nov 2026, 10:30 SGT')
  })

  it('shows market context only when the report has it, and no contact line without an agent', () => {
    expect(reportBlocks(report).some((b) => b.text === 'Market context')).toBe(false)
    expect(reportBlocks(report).some((b) => String(b.text).startsWith('Your relationship manager'))).toBe(false)

    const withContext = reportBlocks({ ...report, report: { ...report.report, marketContext: 'Shares closed higher.' } })
    expect(withContext.some((b) => b.text === 'Market context')).toBe(true)
  })

  it('holds no scores, confidence or client details', () => {
    const text = JSON.stringify(reportBlocks(report, { name: 'Alice', email: 'a@example.test' })).toLowerCase()
    expect(text).not.toMatch(/confidence|materiality|score|client_ref|clientref/)
  })

  it('names the file after the headline and version', () => {
    expect(reportFileName(report)).toBe('dbs-s-q3-results-profit-up-6-v2.pdf')
    expect(reportFileName({ version: 1, report: {} })).toBe('event-report-v1.pdf')
  })

  it('builds a real PDF', async () => {
    const doc = await buildReportPdf(report, { name: 'Alice Agent', email: 'agent1@example.test' })
    expect(doc.getNumberOfPages()).toBeGreaterThanOrEqual(1)
    expect(String.fromCharCode(...new Uint8Array(doc.output('arraybuffer')).slice(0, 5))).toBe('%PDF-')
  })
})
