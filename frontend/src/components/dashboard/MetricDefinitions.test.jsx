import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { DEFINITIONS, hintFor } from './definitions.js'
import MetricDefinitions from './MetricDefinitions.jsx'

describe('MetricDefinitions', () => {
  it('shows only the requested definitions', () => {
    render(<MetricDefinitions keys={['clientsOnboarded', 'teamMedian']} />)

    expect(screen.getByText('2 definitions')).toBeInTheDocument()
    expect(screen.getByText('Clients onboarded')).toBeInTheDocument()
    expect(screen.getByText('Team median')).toBeInTheDocument()
    expect(screen.queryByText(/Demo mockup/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Not shown/)).not.toBeInTheDocument()
    expect(screen.queryByText('Ingestion freshness')).not.toBeInTheDocument()
  })

  it('defines no figure that ADR-0038 leaves out', () => {
    expect(DEFINITIONS.ingestionFreshness).toBeUndefined()
    expect(DEFINITIONS.withoutAccount).toBeUndefined()
  })

  it('counts onboarded clients against their current agent (ADR-0060)', () => {
    expect(hintFor('clientsOnboarded')).toMatch(/current agent/)
    expect(hintFor('clientsOnboarded')).toMatch(/reassigned client/)
  })
})
