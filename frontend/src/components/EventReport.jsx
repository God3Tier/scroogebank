import { useState } from 'react'

import { downloadReportPdf } from '../api/alertEvents.js'
import { saveBlob } from '../api/request.js'
import { cited } from '../utils/citations.js'

// The combined report for one event: written once from every source linked to the event, shared by all the
// clients who hold the stock. It never holds client or portfolio details (ADR-0084).

function formatDateTime(value) {
  if (!value) return 'Not supplied'
  return new Intl.DateTimeFormat('en-SG', {
    day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Singapore',
  }).format(new Date(value))
}

// body: the text to show, when it is not the AI's original (the agent's saved edit or approved text).
// label: says which text is shown. The PDF is rendered on the server, in the layout the client receives.
// editor: the Edit / Save and preview toggle (useReportEditor). In Edit the report is one text box, in the same place.
export function EventReport({ report, eventId, body: shown, label, editor }) {
  const [pdfError, setPdfError] = useState('')
  const [downloading, setDownloading] = useState(false)
  if (!report) return null
  const body = shown || report.report
  const mark = (item) => cited(item.text, item.sourceRefs, report.sources || [])

  async function download() {
    setDownloading(true)
    try {
      saveBlob(await downloadReportPdf(eventId, report.version))
      setPdfError('')
    } catch {
      setPdfError('The PDF could not be made. Try again.')
    } finally {
      setDownloading(false)
    }
  }

  return (
    <div className="event-report" aria-label="Combined event report">
      <div className="event-report__bar">
        <p className="event-report__version">
          Version {report.version}{report.versionCount ? ` of ${report.versionCount}` : ''} ·{' '}
          {label || (report.schemaVersion === 'briefing-v1-legacy' ? 'migrated from an earlier single-article briefing' : 'AI-written from public sources, checked before it is shown')}
        </p>
        <div className="event-report__buttons">
          {editor?.canEdit && (
            <button className="button button--primary" type="button" onClick={editor.toggle} disabled={editor.saving}
              aria-pressed={editor.editing}>
              {editor.editing ? (editor.saving ? 'Checking…' : 'Save and preview') : 'Edit'}
            </button>
          )}
          {eventId && (
            <button className="button button--secondary" type="button" onClick={download}
              disabled={downloading || Boolean(editor?.editing)}
              title={editor?.editing ? 'Click Save and preview first, so your edit is saved and goes into the PDF' : undefined}>
              {downloading ? 'Preparing PDF…' : editor?.editing ? 'Save and preview to download' : 'Download report as PDF'}
            </button>
          )}
        </div>
      </div>
      {pdfError && <p className="error-text" role="alert">{pdfError}</p>}
      <h4 className="event-report__headline">{body.headline}</h4>
      {editor?.editing ? (
        <div className="event-report__edit">
          <p className="holdings-note">
            Editing. The headline and sources stay as they are. One line per item: key facts are Label | Value | Context.
            You can add or remove lines only under WHAT TO MONITOR (2 to 4). Click Save and preview to check and save.
            Your edit is checked again: a figure you add must be in the sources, and advice wording is refused.
          </p>
          <textarea className="event-report__text" aria-label="Report text" spellCheck rows={Math.max(18, editor.text.split('\n').length + 2)}
            value={editor.text} onChange={(e) => editor.setText(e.target.value)} disabled={editor.saving} />
          {editor.errors.length > 0 && (
            <ul className="event-report__errors" role="alert">
              {editor.errors.map((error) => (
                <li key={`${error.line}-${error.message}`}>{error.line > 0 ? `Line ${error.line}: ` : ''}{error.message}</li>
              ))}
            </ul>
          )}
        </div>
      ) : (
        <>
      {(body.executiveSummary || []).map(mark).map((text) => <p key={text} className="event-report__summary">{text}</p>)}
      {(body.keyFacts || []).length > 0 && (
        <>
          <h5>Key facts</h5>
          <ul>{body.keyFacts.map((fact) => <li key={fact.label}><strong>{fact.label}:</strong> {fact.value}. {cited(fact.context, fact.sourceRefs, report.sources || [])}</li>)}</ul>
        </>
      )}
      {(body.whyItMatters || []).length > 0 && (
        <>
          <h5>Why it matters</h5>
          <ul>{body.whyItMatters.map((item) => <li key={item.text}>{item.kind === 'POTENTIAL_IMPLICATION' ? `Potential implication: ${mark(item)}` : mark(item)}</li>)}</ul>
        </>
      )}
      {(body.whatToMonitor || []).length > 0 && (
        <>
          <h5>What to monitor</h5>
          <ul>{body.whatToMonitor.map((item) => <li key={item}>{item}</li>)}</ul>
        </>
      )}
      {body.uncertainty && <p className="uncertainty"><strong>Uncertainty:</strong> {body.uncertainty}</p>}
        </>
      )}
      <p className="holdings-note">{report.disclaimer}</p>
    </div>
  )
}

/** Where the report's data comes from: every source linked to the event, and which ones the AI actually used. */
export function CombinedSources({ report, evidence }) {
  const sources = report
    ? report.sources
    : (evidence || []).map((item) => ({ url: item.url, title: item.title, domain: item.sourceDomain, retrievedAt: item.retrievedAt, publishedAt: item.publishedAt, usedInReport: true }))
  const used = sources.filter((source) => source.usedInReport).length
  return (
    <section className="combined-sources" aria-labelledby="combined-sources-title">
      <h4 id="combined-sources-title">Sources combined in this report</h4>
      <p className="combined-sources__count">
        {sources.length === 0 ? 'No sources are linked yet.'
          : `${used} of ${sources.length} linked source${sources.length === 1 ? '' : 's'} used in the report.`}
        {report?.schemaVersion === 'briefing-v1-legacy' && ' This earlier briefing was written from a single article.'}
      </p>
      <ol>
        {sources.map((source, index) => (
          <li key={source.url}>
            <a href={source.url} target="_blank" rel="noreferrer">[{index + 1}] {source.title}</a>
            <span>{source.domain} · published {formatDateTime(source.publishedAt)} · retrieved {formatDateTime(source.retrievedAt)}</span>
            <em>{source.usedInReport ? 'Used in the report' : 'Linked, not used in the summary'}</em>
          </li>
        ))}
      </ol>
    </section>
  )
}
