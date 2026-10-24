import { useCallback, useEffect, useRef, useState } from 'react'

// Paging for the lists that open in full on their own page, ten rows at a
// time. A source turns a page number into rows; page(p) and last() both
// resolve to { page, rows, totalPages, hasNext }, where totalPages is null
// while the end of the list is not known yet.
export const PAGE_SIZE = 10

// A list the service pages by number and counts (user-service, client-service).
// loadPage({ page, size }) returns { content, totalPages }.
export function numberedSource(loadPage, { pageSize = PAGE_SIZE } = {}) {
  let totalPages = null

  async function page(p) {
    const data = await loadPage({ page: p, size: pageSize })
    totalPages = Math.max(1, data.totalPages)
    // A page past the end, after rows were removed, shows the last one instead.
    if (p >= totalPages) return page(totalPages - 1)
    return { page: p, rows: data.content, totalPages, hasNext: p + 1 < totalPages }
  }

  async function last() {
    if (totalPages === null) await page(0)
    return page(totalPages - 1)
  }

  return { page, last }
}

// A list the service pages with a cursor and does not count (the activity
// feed, all transactions; ADR-0021, ADR-0043). Rows are fetched chunkSize at
// a time and kept, so going back costs nothing and the last page is reached
// in one request per chunkSize rows. loadChunk({ cursor, size }) returns
// { content, nextCursor }; nextCursor is null on the last chunk.
export function cursorSource(loadChunk, { pageSize = PAGE_SIZE, chunkSize = 100 } = {}) {
  const chunks = []
  // One fetch at a time, so two quick clicks never fetch a chunk twice.
  let queue = Promise.resolve()

  function serially(work) {
    const run = queue.then(work)
    queue = run.catch(() => {})
    return run
  }

  const ended = () => chunks.length > 0 && !chunks.at(-1).nextCursor

  async function fetchUpTo(index) {
    while (chunks.length <= index && !ended()) {
      const data = await loadChunk({ cursor: chunks.at(-1)?.nextCursor ?? null, size: chunkSize })
      chunks.push({ rows: data.content, nextCursor: data.nextCursor })
    }
  }

  function view(p) {
    const rows = chunks.flatMap((chunk) => chunk.rows)
    const known = ended() ? Math.max(1, Math.ceil(rows.length / pageSize)) : null
    const shown = known === null ? p : Math.min(p, known - 1)
    const start = shown * pageSize
    return {
      page: shown,
      rows: rows.slice(start, start + pageSize),
      totalPages: known,
      hasNext: start + pageSize < rows.length || !ended(),
    }
  }

  return {
    page: (p) => serially(async () => {
      await fetchUpTo(Math.floor(((p + 1) * pageSize - 1) / chunkSize))
      return view(p)
    }),
    last: () => serially(async () => {
      await fetchUpTo(Infinity)
      return view(Infinity)
    }),
  }
}

// Shows one page of a source at a time. go(page) or go('last') moves;
// reload() repeats the last move, to retry one that failed (the rows shown
// stay meanwhile) or to show a change. A reply for a move that a newer one
// replaced is dropped. A new source (new filters) starts again from the first
// page.
const FIRST_VIEW = { page: 0, rows: [], totalPages: null, hasNext: false }

export function usePagedList(source) {
  // The rows shown belong to view.source; another source's rows never show.
  const [view, setView] = useState({ ...FIRST_VIEW, source: null, loading: true, error: null })
  const latest = useRef({ request: 0, target: 0 })

  const go = useCallback(async (target) => {
    const request = latest.current.request + 1
    latest.current = { request, target }
    setView((current) => ({ ...current, loading: true, error: null }))
    try {
      const result = target === 'last' ? await source.last() : await source.page(target)
      if (latest.current.request !== request) return
      setView({ ...result, source, loading: false, error: null })
    } catch (error) {
      if (latest.current.request !== request) return
      setView((current) => (
        current.source === source ? { ...current, loading: false, error } : { ...FIRST_VIEW, source, loading: false, error }
      ))
    }
  }, [source])

  useEffect(() => {
    void go(0)
  }, [go])

  const reload = useCallback(() => go(latest.current.target), [go])
  const shown = view.source === source ? view : { ...FIRST_VIEW, loading: true, error: null }
  return { ...shown, go, reload }
}

// Pages a list the service sends whole (an account's transactions, the
// follow-up emails, the per-agent table), in the same shape as
// usePagedList, so the same Pager moves through it. A page past the end, after
// the list shrank, shows the last one.
export function useArrayPages(all, pageSize = PAGE_SIZE) {
  const [page, setPage] = useState(0)
  const totalPages = Math.max(1, Math.ceil(all.length / pageSize))
  const shown = Math.min(page, totalPages - 1)
  return {
    page: shown,
    rows: all.slice(shown * pageSize, (shown + 1) * pageSize),
    totalPages,
    hasNext: shown + 1 < totalPages,
    loading: false,
    error: null,
    go: (target) => setPage(target === 'last' ? totalPages - 1 : target),
  }
}
