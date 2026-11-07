export const syntheticAlerts = [
  {
    id: '50000000-0000-4000-8000-000000000001',
    clientRef: 'syn-client-001',
    instrumentId: 'SYN-SGX-001',
    headline: 'Synthetic issuer announces a material acquisition review',
    status: 'NEW',
    score: 82.4,
    createdAt: '2026-09-28T00:04:00Z',
    version: 0,
  },
  {
    id: '50000000-0000-4000-8000-000000000002',
    clientRef: 'syn-client-002',
    instrumentId: 'SYN-NASDAQ-004',
    headline: 'Regulator opens consultation affecting synthetic sector exposure',
    status: 'MONITORING',
    score: 71.25,
    createdAt: '2026-09-27T22:40:00Z',
    version: 1,
  },
  {
    id: '50000000-0000-4000-8000-000000000003',
    clientRef: 'syn-client-003',
    instrumentId: 'SYN-LSE-002',
    headline: 'Synthetic issuer updates full-year earnings guidance',
    status: 'INFORMED',
    score: 64.1,
    createdAt: '2026-09-27T18:15:00Z',
    version: 1,
  },
]

export const syntheticAlertDetails = {
  [syntheticAlerts[0].id]: {
    ...syntheticAlerts[0],
    exposure: {
      amountSgd: '128450.00',
      portfolioPercentage: 18.6,
      valuationAsOf: '2026-09-27T23:00:00Z',
      freshness: 'FRESH',
    },
    explanationFactors: [
      { code: 'EVENT_SEVERITY', contribution: 27.5, description: 'Acquisition review may change the issuer risk profile.' },
      { code: 'CLIENT_EXPOSURE', contribution: 31.2, description: 'Synthetic position represents 18.6% of this portfolio snapshot.' },
      { code: 'SOURCE_RELEVANCE', contribution: 11.7, description: 'Official issuer announcement directly references the instrument.' },
      { code: 'CLASSIFIER_CONFIDENCE', contribution: 12, description: 'Structured event classification confidence is 0.80.' },
    ],
    evidence: [
      {
        title: 'Synthetic issuer announces acquisition review',
        url: 'https://example.invalid/synthetic-acquisition-review',
        sourceDomain: 'example.invalid',
        publishedAt: '2026-09-27T23:42:00Z',
        retrievedAt: '2026-09-27T23:55:00Z',
      },
    ],
    briefing: {
      summary: 'The announced review could change the issuer’s capital allocation and near-term risk profile. The client has a material synthetic exposure, so the event merits an agent review before any outreach decision.',
      aiGenerated: true,
      modelVersion: 'synthetic-luna-fixture-v1',
      promptVersion: 'brief-v1',
      uncertainty: 'Terms and timing remain preliminary. Confirm against the cited issuer announcement.',
    },
    latestDecision: null,
  },
}

export function syntheticDetail(alert) {
  return syntheticAlertDetails[alert.id] ?? {
    ...alert,
    exposure: {
      amountSgd: '42600.00',
      portfolioPercentage: 7.4,
      valuationAsOf: '2026-09-27T21:00:00Z',
      freshness: 'STALE',
    },
    explanationFactors: [
      { code: 'EVENT_SEVERITY', contribution: 24, description: 'The event may affect the issuer outlook.' },
      { code: 'CLIENT_EXPOSURE', contribution: 21.5, description: 'The portfolio has a measurable synthetic exposure.' },
      { code: 'SOURCE_RELEVANCE', contribution: 12.75, description: 'The source directly references the instrument.' },
      { code: 'CLASSIFIER_CONFIDENCE', contribution: 13, description: 'Classification confidence passed the review threshold.' },
    ],
    evidence: [
      {
        title: alert.headline,
        url: 'https://example.invalid/synthetic-event',
        sourceDomain: 'example.invalid',
        publishedAt: alert.createdAt,
        retrievedAt: alert.createdAt,
      },
    ],
    briefing: {
      summary: 'This synthetic event passed the deterministic materiality threshold and should be reviewed with the cited public evidence.',
      aiGenerated: true,
      modelVersion: 'synthetic-luna-fixture-v1',
      promptVersion: 'brief-v1',
      uncertainty: 'The valuation snapshot is stale; confirm exposure before acting.',
    },
    latestDecision: alert.status === 'MONITORING'
      ? { decision: 'MONITOR', actorSubject: 'syn-agent-001', decidedAt: alert.createdAt, alertVersion: alert.version }
      : null,
  }
}
