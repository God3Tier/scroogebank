import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import { createNewsDrafts } from '../api/engagement.js'
import { SKIP_REASONS } from '../utils/newsReports.js'

// Notify several tickers at once (news-alert-drafts plan, round 2). Making the drafts approves nothing and sends nothing:
// a report is only ever approved one ticker at a time. Each button makes (or adds to) a News Alert draft for every
// client it covers, then opens the Follow-up Emails list, so the agent does not go back and forth ticker by ticker.

function groupByEvent(items) {
  const byEvent = new Map()
  items.forEach((item) => byEvent.set(item.eventId, [...(byEvent.get(item.eventId) || []), item.alertId]))
  return byEvent
}

const people = (items) => new Set(items.map((item) => item.clientRef)).size
const plural = (n, word) => `${n} ${word}${n === 1 ? '' : 's'}`

/** ready: the agent's approved reports for clients in Plan client briefing; selected: { alertId: true }. */
export function BatchNotify({ ready, selected, panelRef }) {
  const navigate = useNavigate()
  const [state, setState] = useState({ busy: false, error: '' })
  const ticked = ready.filter((item) => selected[item.alertId])

  async function notify(items) {
    if (items.length === 0) return
    setState({ busy: true, error: '' })
    const drafts = new Set()
    const skipped = []
    try {
      for (const [eventId, alertIds] of groupByEvent(items)) {
        const result = await createNewsDrafts({ eventId, alertIds })
        result.drafts.forEach((draft) => drafts.add(draft.draftId))
        skipped.push(...(result.skipped || []))
      }
    } catch (e) {
      setState({ busy: false, error: e.status === 503 ? 'Follow-up Emails or event alerts are unavailable. Some drafts may not have been made.' : e.message || 'The drafts could not be made.' })
      return
    }
    if (drafts.size === 0) {
      setState({ busy: false, error: `No draft was made: ${[...new Set(skipped.map((s) => SKIP_REASONS[s.reason] || s.reason))].join(', ')}.` })
      return
    }
    const params = new URLSearchParams({ news: String(drafts.size) })
    if (skipped.length) params.set('skipped', String(skipped.length))
    navigate(`/agent/drafts?${params}`)
  }

  return (
    <section className="batch-notify" ref={panelRef} tabIndex={-1} aria-labelledby="batch-notify-title">
      <h2 id="batch-notify-title">Notify several tickers at once</h2>
      <p className="holdings-note batch-notify__intro">
        Approve each ticker&apos;s report first (approval is final and is always one ticker at a time). Then make the client drafts
        for many tickers here. Nothing is approved or sent by these buttons; you review and send each draft in Follow-up Emails.
      </p>
      <div className="batch-notify__options">
        <div>
          <button type="button" className="button button--primary" onClick={() => notify(ready)} disabled={state.busy || ready.length === 0}>
            Notify all ready clients ({plural(people(ready), 'client')}, {plural(ready.length, 'report')})
          </button>
          <p className="batch-notify__what">
            <span className="material-symbols-outlined" aria-hidden="true">groups</span>
            Makes a draft for every client who is in Plan client briefing and whose report you have approved, across all tickers.
          </p>
        </div>
        <div>
          <button type="button" className="button button--secondary" onClick={() => notify(ticked)} disabled={state.busy || ticked.length === 0}>
            Notify ticked clients ({plural(people(ticked), 'client')}, {plural(ticked.length, 'report')})
          </button>
          <p className="batch-notify__what">
            <span className="material-symbols-outlined" aria-hidden="true">checklist</span>
            The same, but only for the clients you have ticked. Your ticks are kept when you switch ticker.
          </p>
        </div>
      </div>
      {state.busy && <p className="empty-note" role="status">Making drafts…</p>}
      {state.error && <p className="error-text" role="alert">{state.error}</p>}
    </section>
  )
}

/**
 * A small button that floats at the bottom right once the panel is scrolled out of view and at least two reports are
 * ready. It only scrolls back to the panel; it does not notify anyone.
 */
export function FloatingBatchHint({ panelRef, readyCount }) {
  const [away, setAway] = useState(false)
  const timer = useRef(null)

  useEffect(() => {
    const node = panelRef.current
    if (!node || typeof IntersectionObserver === 'undefined') return undefined
    const observer = new IntersectionObserver(([entry]) => setAway(!entry.isIntersecting))
    observer.observe(node)
    return () => observer.disconnect()
  }, [panelRef])

  useEffect(() => () => clearTimeout(timer.current), [])

  if (!away || readyCount < 2) return null

  function jump() {
    const node = panelRef.current
    if (!node) return
    const calm = typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
    node.scrollIntoView({ behavior: calm ? 'auto' : 'smooth', block: 'start' })
    node.focus({ preventScroll: true })
    node.classList.add('batch-notify--flash')
    clearTimeout(timer.current)
    timer.current = setTimeout(() => node.classList.remove('batch-notify--flash'), 1600)
  }

  return (
    <button type="button" className="batch-hint" onClick={jump}
      title="Make drafts for every approved report in one go. Click to jump to the buttons at the top."
      aria-label="Several tickers? Notify them all at once. Jumps to the buttons at the top of the page.">
      <span className="material-symbols-outlined" aria-hidden="true">arrow_upward</span>
      Several tickers? Notify them all at once
    </button>
  )
}
