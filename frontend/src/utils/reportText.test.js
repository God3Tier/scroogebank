import { describe, expect, it } from 'vitest'

import { fromText, lineOf, toText } from './reportText.js'

const sections = {
  executiveSummary: ['Apple reported higher revenue.', 'Services grew.'],
  keyFacts: [{ label: 'Revenue', value: 'USD 94.9 billion', context: 'Fourth quarter.' }],
  whyItMatters: ['Apple is widely held.'],
  whatToMonitor: ['Guidance', 'Analyst targets'],
  uncertainty: 'Partial excerpts.',
}

describe('report text', () => {
  it('writes the sections as one block and reads the same sections back', () => {
    const text = toText(sections)

    expect(text).toBe([
      'EXECUTIVE SUMMARY', '1. Apple reported higher revenue.', '2. Services grew.', '',
      'KEY FACTS', '1. Revenue | USD 94.9 billion | Fourth quarter.', '',
      'WHY IT MATTERS', '1. Apple is widely held.', '',
      'WHAT TO MONITOR', '- Guidance', '- Analyst targets', '',
      'UNCERTAINTY', 'Partial excerpts.',
    ].join('\n'))
    expect(fromText(text, sections)).toEqual({ sections })
  })

  it('leaves out key facts a report does not have, and does not ask for them', () => {
    const none = { ...sections, keyFacts: [] }
    expect(toText(none)).not.toContain('KEY FACTS')
    expect(fromText(toText(none), none)).toEqual({ sections: none })
  })

  it('takes edited wording, wrapped lines and a changed number of things to monitor', () => {
    const text = toText(sections)
      .replace('1. Apple reported higher revenue.', '1. Apple reported higher\nrevenue than expected.')
      .replace('- Analyst targets', '- Analyst targets\n- Supply chain')

    const result = fromText(text, sections)

    expect(result.sections.executiveSummary[0]).toBe('Apple reported higher revenue than expected.')
    expect(result.sections.whatToMonitor).toEqual(['Guidance', 'Analyst targets', 'Supply chain'])
  })

  it('names the line of a mistake', () => {
    const base = toText(sections)

    expect(fromText(base.replace('2. Services grew.\n', ''), sections).errors[0].message)
      .toMatch(/Executive summary must keep exactly 2 items; there is 1/)
    expect(fromText(base.replace('Revenue | USD 94.9 billion | Fourth quarter.', 'Revenue | USD 94.9 billion'), sections).errors)
      .toEqual([{ line: 6, message: 'Key facts need Label | Value | Context, all three filled in.' }])
    expect(fromText(base.replace('WHY IT MATTERS', 'WHY IT IS'), sections).errors[0].message)
      .toMatch(/WHY IT MATTERS heading is missing/)
    expect(fromText(base.replace('- Analyst targets', ''), sections).errors[0].message)
      .toMatch(/What to monitor needs 2 to 4 lines; there is 1/)
    expect(fromText(`oops\n${base}`, sections).errors[0]).toEqual({
      line: 1, message: 'This line is before the first heading. Start with EXECUTIVE SUMMARY.',
    })
  })

  it('finds the line a server refusal points at', () => {
    const text = toText(sections)
    expect(lineOf(text, 'executiveSummary', 1)).toBe(3)
    expect(lineOf(text, 'keyFacts', 0)).toBe(6)
    expect(lineOf(text, 'uncertainty', -1)).toBe(15)
    expect(lineOf(text, 'nothing', 0)).toBe(0)
  })
})
