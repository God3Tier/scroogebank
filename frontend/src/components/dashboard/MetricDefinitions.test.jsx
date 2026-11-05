import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { DEFINITIONS, NOT_SHOWN, hintFor } from './definitions.js'
import MetricDefinitions from './MetricDefinitions.jsx'

describe('MetricDefinitions', () => {
  it('shows the requested definitions and the figures left out, with reasons', () => {
    render(<MetricDefinitions keys={['clientsOnboarded', 'teamMedian']} />)

    expect(screen.getByText('2 definitions')).toBeInTheDocument()
    expect(screen.getByText('Clients onboarded')).toBeInTheDocument()
    expect(screen.getByText('Team median')).toBeInTheDocument()
    expect(screen.queryByText(/Demo mockup/)).not.toBeInTheDocument()

    const omitted = screen.getByRole('heading', { name: 'Not shown, and why' }).parentElement
    for (const { term, reason } of NOT_SHOWN) {
      expect(within(omitted).getByText(term)).toBeInTheDocument()
      expect(within(omitted).getByText(reason)).toBeInTheDocument()
    }
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
