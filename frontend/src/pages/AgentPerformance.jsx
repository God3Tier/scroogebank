import { Link, useSearchParams } from 'react-router-dom'

import { panelData, windowForDays } from '../api/dashboard.js'
import { Pager } from '../components/ListFooter.jsx'
import AgentPerformanceTable, { SortSelect } from '../components/dashboard/AgentPerformanceTable.jsx'
import DashboardHeader from '../components/dashboard/DashboardHeader.jsx'
import PanelCard from '../components/dashboard/PanelCard.jsx'
import { SORT_OPTIONS } from '../components/dashboard/definitions.js'
import useDashboards, { panelOf } from '../components/dashboard/useDashboards.js'
import { useArrayPages } from '../components/paging.js'

const WINDOWS = [7, 30, 90]
const NO_ROWS = []

// Every agent's figures, ten a page, opened from the Admin Dashboard's Agent
// performance panel. The window and sort come in the link, so the page
// continues the ranking the admin was looking at. dashboard-service sends the
// whole ranking at once.
function AgentPerformance() {
  const [params, setParams] = useSearchParams()
  const days = WINDOWS.includes(Number(params.get('days'))) ? Number(params.get('days')) : 30
  const sort = SORT_OPTIONS.some((option) => option.value === params.get('sort')) ? params.get('sort') : SORT_OPTIONS[0].value
  const period = windowForDays(days)

  const { responses } = useDashboards({ agents: ['agents', { ...period, sort }] })
  const table = panelData(responses.agents?.panels?.agents)
  const pages = useArrayPages(table?.rows ?? NO_ROWS)

  function choose(next) {
    setParams({ days: String(days), sort, ...next }, { replace: true })
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
        action={<SortSelect value={sort} onChange={(value) => choose({ sort: value })} />}
      >
        <AgentPerformanceTable rows={pages.rows} sortedBy={table?.sortedBy} />
        {pages.rows.length > 0 && <Pager list={pages} label="Agent pages" />}
      </PanelCard>
    </div>
  )
}

export default AgentPerformance
