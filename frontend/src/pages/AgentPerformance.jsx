import { Link, useSearchParams } from 'react-router-dom'

import { panelData, windowForDays } from '../api/dashboard.js'
import { Pager } from '../components/ListFooter.jsx'
import AgentPerformanceTable, { AgentTableControls } from '../components/dashboard/AgentPerformanceTable.jsx'
import DashboardHeader from '../components/dashboard/DashboardHeader.jsx'
import PanelCard from '../components/dashboard/PanelCard.jsx'
import { DEFAULT_CURRENCY, SORT_OPTIONS, rankingParams } from '../components/dashboard/definitions.js'
import useDashboards, { panelOf } from '../components/dashboard/useDashboards.js'
import { useArrayPages } from '../components/paging.js'

const WINDOWS = [7, 30, 90]
const NO_ROWS = []
const CURRENCY = /^[A-Z]{3}$/

// Every agent's figures, ten a page, opened from the Admin Dashboard's Agent
// performance panel. The window, sort and deposits currency come in the link,
// so the page continues the ranking the admin was looking at.
// dashboard-service sends the whole ranking at once.
function AgentPerformance() {
  const [params, setParams] = useSearchParams()
  const days = WINDOWS.includes(Number(params.get('days'))) ? Number(params.get('days')) : 30
  const sort = SORT_OPTIONS.some((option) => option.value === params.get('sort')) ? params.get('sort') : SORT_OPTIONS[0].value
  const currency = CURRENCY.test(params.get('currency') ?? '') ? params.get('currency') : DEFAULT_CURRENCY
  const period = windowForDays(days)
  const ranking = rankingParams(sort, currency)

  const { responses } = useDashboards({ agents: ['agents', { ...period, ...ranking }] })
  const table = panelData(responses.agents?.panels?.agents)
  const pages = useArrayPages(table?.rows ?? NO_ROWS)

  function choose(next) {
    setParams({ days: String(days), ...rankingParams(next.sort ?? sort, next.currency ?? currency), ...next }, { replace: true })
    pages.go(0)
  }

  return (
    <div className="page dashboard-page">
      <div className="back-row"><Link to="/insights/admin">Back to Admin Dashboard</Link></div>
      <DashboardHeader
        title="All agents"
        subtitle="Every agent's figures for the window"
        scopeLabel="Bank-wide"
        days={days}
        onDaysChange={(value) => choose({ days: String(value) })}
        response={responses.agents}
      />
      <PanelCard
        title="Agent performance"
        panel={panelOf(responses.agents, 'agents')}
        className="card-flush"
        action={(
          <AgentTableControls
            sort={sort}
            onSortChange={(value) => choose({ sort: value })}
            currency={currency}
            currencies={table?.currencies}
            onCurrencyChange={(value) => choose({ currency: value })}
          />
        )}
      >
        <AgentPerformanceTable rows={pages.rows} sortedBy={table?.sortedBy} sortCurrency={table?.sortCurrency} />
        {pages.rows.length > 0 && <Pager list={pages} label="Agent pages" />}
      </PanelCard>
    </div>
  )
}

export default AgentPerformance
