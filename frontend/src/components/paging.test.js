import { describe, expect, it, vi } from 'vitest'

import { cursorSource, numberedSource } from './paging.js'

const rows = (count, first = 1) => Array.from({ length: count }, (_, i) => first + i)

describe('a numbered source', () => {
  it('asks the service for the page and says whether more follow', async () => {
    const loadPage = vi.fn().mockResolvedValue({ content: rows(10, 11), totalPages: 3 })
    const source = numberedSource(loadPage)

    expect(await source.page(1)).toEqual({ page: 1, rows: rows(10, 11), totalPages: 3, hasNext: true })
    expect(loadPage).toHaveBeenCalledWith({ page: 1, size: 10 })
  })

  it('goes to the last page the service counts', async () => {
    const loadPage = vi.fn(({ page }) => Promise.resolve({ content: [page], totalPages: 4 }))
    const source = numberedSource(loadPage)

    expect(await source.last()).toEqual({ page: 3, rows: [3], totalPages: 4, hasNext: false })
  })

  it('shows the last page instead of one past the end, after rows were removed', async () => {
    const loadPage = vi.fn(({ page }) => Promise.resolve({ content: page < 2 ? [page] : [], totalPages: 2 }))
    const source = numberedSource(loadPage)

    expect(await source.page(2)).toMatchObject({ page: 1, rows: [1] })
  })

  it('has one page when the list is empty', async () => {
    const source = numberedSource(vi.fn().mockResolvedValue({ content: [], totalPages: 0 }))

    expect(await source.page(0)).toEqual({ page: 0, rows: [], totalPages: 1, hasNext: false })
  })
})

describe('a cursor source', () => {
  it('fetches chunks only as far as the page needs, and keeps them', async () => {
    const loadChunk = vi.fn()
      .mockResolvedValueOnce({ content: rows(25), nextCursor: 'b' })
      .mockResolvedValueOnce({ content: rows(25, 26), nextCursor: 'c' })
    const source = cursorSource(loadChunk, { chunkSize: 25 })

    expect(await source.page(2)).toEqual({ page: 2, rows: rows(10, 21), totalPages: null, hasNext: true })
    expect(await source.page(3)).toMatchObject({ rows: rows(10, 31) })
    expect(await source.page(0)).toMatchObject({ rows: rows(10) })
    expect(loadChunk.mock.calls).toEqual([[{ cursor: null, size: 25 }], [{ cursor: 'b', size: 25 }]])
  })

  it('never fetches a chunk twice when moves overlap', async () => {
    const loadChunk = vi.fn().mockResolvedValue({ content: rows(5), nextCursor: null })
    const source = cursorSource(loadChunk)

    await Promise.all([source.page(0), source.last()])
    expect(loadChunk).toHaveBeenCalledTimes(1)
  })

  it('counts the pages once the last chunk is in', async () => {
    const loadChunk = vi.fn()
      .mockResolvedValueOnce({ content: rows(100), nextCursor: 'b' })
      .mockResolvedValueOnce({ content: rows(1, 101), nextCursor: null })
    const source = cursorSource(loadChunk)

    expect(await source.last()).toEqual({ page: 10, rows: [101], totalPages: 11, hasNext: false })
  })

  it('has one empty page when there is nothing', async () => {
    const source = cursorSource(vi.fn().mockResolvedValue({ content: [], nextCursor: null }))

    expect(await source.page(0)).toEqual({ page: 0, rows: [], totalPages: 1, hasNext: false })
  })
})
