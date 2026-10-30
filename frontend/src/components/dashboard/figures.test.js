import { describe, expect, it } from 'vitest'

import { dailyTrend, ratio } from './figures.js'

describe('the daily trend', () => {
  it('has a row for every day of the window, so days are evenly spaced in time', () => {
    const rows = dailyTrend(
      [{ date: '2026-10-02', count: 3 }],
      [{ date: '2026-09-30', count: 1 }, { date: '2026-10-03', count: 2 }],
      { from: '2026-09-29', to: '2026-10-03' },
    )

    expect(rows).toEqual([
      { date: '2026-09-29', clients: 0, transactions: 0 },
      { date: '2026-09-30', clients: 0, transactions: 1 },
      { date: '2026-10-01', clients: 0, transactions: 0 },
      { date: '2026-10-02', clients: 3, transactions: 0 },
      { date: '2026-10-03', clients: 0, transactions: 2 },
    ])
  })

  it('fills the window even when a panel is unavailable', () => {
    const rows = dailyTrend(null, null, { from: '2026-12-31', to: '2027-01-01' })

    expect(rows.map((row) => row.date)).toEqual(['2026-12-31', '2027-01-01'])
  })

  it('joins the series by date when no window is given', () => {
    expect(dailyTrend([{ date: '2026-10-02', count: 3 }], [{ date: '2026-10-01', count: 1 }])).toEqual([
      { date: '2026-10-01', clients: 0, transactions: 1 },
      { date: '2026-10-02', clients: 3, transactions: 0 },
    ])
  })
})

describe('a ratio', () => {
  it('is null with nothing to divide by', () => {
    expect(ratio(1, 0)).toBeNull()
    expect(ratio(1, 4)).toBe(0.25)
  })
})
