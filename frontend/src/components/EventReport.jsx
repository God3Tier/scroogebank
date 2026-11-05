import { useState } from 'react'

import { downloadReportPdf } from '../utils/reportPdf.js'

// The combined report for one event: written once from every source linked to the event, shared by all the
// clients who hold the stock. It never holds client or portfolio details (ADR-0084).

function formatDateTime(value) {
  if (!value) return 'Not supplied'
  return new Intl.DateTimeFormat('en-SG', {
    day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Singapore',
  }).format(new Date(value))
}

export function EventReport({ report }) {
  const [pdfError, setPdfError] = useState('')
  if (!report) return null
  const body = report.report
  const cited = (list) => (list || []).map((item) => item.text)
  return (
    <div className="event-report" aria-label="Combined event report">
      <div className="event-report__bar">
        <p className="event-report__version">
          Version {report.version}{report.versionCount ? ` of ${report.versionCount}` : ''} ·{' '}
          {report.schemaVersion === 'briefing-v1-legacy' ? 'migrated from an earlier single-article briefing' : 'AI-written from public sources, checked before it is shown'}
        </p>
        <button className="button button--secondary" type="button"
          onClick={() => downloadReportPdf(report).then(() => setPdfError(''), () => setPdfError('The PDF could not be made. Try again.'))}>
          Download report as PDF
        </button>
      </div>
      {pdfError && <p className="error-text" role="alert">{pdfError}</p>}
      <h4 className="event-report__headline">{body.headline}</h4>
      {cited(body.executiveSummary).map((text) => <p key={text} className="event-report__summary">{text}</p>)}
      {(body.keyFacts || []).length > 0 && (
        <>
          <h5>Key facts</h5>
          <ul>{body.keyFacts.map((fact) => <li key={fact.label}><strong>{fact.label}:</strong> {fact.value}. {fact.context}</li>)}</ul>
        </>
      )}
      {(body.whyItMatters || []).length > 0 && (
        <>
          <h5>Why it matters</h5>
          <ul>{body.whyItMatters.map((item) => <li key={item.text}>{item.kind === 'POTENTIAL_IMPLICATION' ? `Potential implication: ${item.text}` : item.text}</li>)}</ul>
        </>
      )}
      {(body.whatToMonitor || []).length > 0 && (
        <>
          <h5>What to monitor</h5>
          <ul>{body.whatToMonitor.map((item) => <li key={item}>{item}</li>)}</ul>
        </>
      )}
      {body.uncertainty && <p className="uncertainty"><strong>Uncertainty:</strong> {body.uncertainty}</p>}
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
