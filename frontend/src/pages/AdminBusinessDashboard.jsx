import { useState } from 'react'

import { panelData, windowForDays } from '../api/dashboard.js'
import StatCard from '../components/StatCard.jsx'
import ActionList from '../components/dashboard/ActionList.jsx'
import AgentPerformanceTable, { SortSelect } from '../components/dashboard/AgentPerformanceTable.jsx'
import BreakdownChart from '../components/dashboard/BreakdownChart.jsx'
import DashboardHeader from '../components/dashboard/DashboardHeader.jsx'
import MetricDefinitions from '../components/dashboard/MetricDefinitions.jsx'
import PanelCard from '../components/dashboard/PanelCard.jsx'
import TrendChart from '../components/dashboard/TrendChart.jsx'
import useDashboards, { panelOf, upperKeys } from '../components/dashboard/useDashboards.js'
import { hintFor } from '../components/dashboard/definitions.js'
import { dailyTrend, ratio } from '../components/dashboard/figures.js'
import {
  NO_FIGURE, daysSince, formatNumber, formatPercent, formatSGD, formatValue, toRows,
} from '../components/dashboard/format.js'

const STATUS_LABELS = { COMPLETED: 'Completed', PENDING: 'Pending', FAILED: 'Failed' }
const TYPE_LABELS = { DEPOSIT: 'Deposit', WITHDRAWAL: 'Withdrawal' }
const STALE_DAYS = 7

// Figures come from dashboard-service (docs/api/dashboard-api.yaml). Panels it
// does not serve yet (most active clients, ingestion freshness, clients
// without an account, rejected verifications, flagged transactions) are not
// shown; see docs/architecture/open-items.md.
function AdminBusinessDashboard() {
  const [days, setDays] = useState(30)
  const [sort, setSort] = useState('clientsOnboarded')
  const period = windowForDays(days)

  const { responses } = useDashboards({
    overview: ['overview', period],
    portfolio: ['portfolio', period],
    transactions: ['transactions', period],
    actions: ['action-items', { ...period, staleDays: STALE_DAYS }],
  })
  const { responses: ranked } = useDashboards({ agents: ['agents', { ...period, sort }] })
  const { overview, portfolio, transactions, actions } = responses
  // The stat tiles all come from the overview.
  const tilesLoading = overview === undefined

  const clients = panelData(overview?.panels?.clients)
  const accounts = panelData(overview?.panels?.accounts)
  const roster = panelData(overview?.panels?.agents)
  const tx = panelData(transactions?.panels?.transactions)
  const portfolioClients = panelData(portfolio?.panels?.clients)
  const portfolioAccounts = panelData(portfolio?.panels?.accounts)
  const perClient = panelData(portfolio?.panels?.accountsPerClient)
  const actionItems = panelData(actions?.panels?.clients)
  const agentTable = panelData(ranked.agents?.panels?.agents)
  const today = period.to

  const byStatus = upperKeys(tx?.byStatus)
  const failureRate = tx ? ratio(byStatus.FAILED ?? 0, tx.total) : null
  const verifiedShare = clients ? ratio(clients.byVerificationStatus?.VERIFIED ?? 0, clients.total) : null
  const trend = dailyTrend(clients?.dailyCreated, tx?.daily)

  const actionGroups = [
    {
      key: 'stale',
      title: `Stale unverified clients (> ${STALE_DAYS} days)`,
      icon: 'hourglass_top',
      empty: 'No stale unverified clients.',
      total: actionItems?.staleUnverifiedTotal,
      rows: (actionItems?.staleUnverified ?? []).map((ref) => ({
        id: `${ref.clientId}-stale`,
        clientId: ref.clientId,
        name: `${ref.firstName} ${ref.lastName}`,
        meta: `${daysSince(ref.createdAt, today)} days old`,
      })),
    },
  ]

  return (
    <div className="page dashboard-page">
      <DashboardHeader
        title="Business Dashboard"
        subtitle="Bank-wide performance and operational health"
        scopeLabel="Bank-wide"
        days={days}
        onDaysChange={setDays}
        response={overview}
      />

      <div className="stat-grid stat-grid-dense stat-grid-3">
        <StatCard loading={tilesLoading} label="Total clients" value={formatNumber(clients?.total)} icon="groups" />
        <StatCard
          loading={tilesLoading}
          label="Clients added"
          value={formatNumber(clients?.createdInWindow)}
          icon="person_add"
          caption={`Last ${days} days`}
          hint={hintFor('clientsOnboarded')}
        />
        <StatCard
          loading={tilesLoading}
          label="Verified share"
          value={formatPercent(verifiedShare)}
          icon="verified_user"
          hint={hintFor('verifiedShare')}
        />
        <StatCard
          loading={tilesLoading}
          label="Total accounts"
          value={formatNumber(accounts?.total)}
          icon="account_balance"
          caption={accounts ? `+${formatNumber(accounts.openedInWindow)} opened` : undefined}
        />
        <StatCard
          loading={tilesLoading}
          label="Booked initial deposits"
          value={formatSGD(accounts?.bookedInitialDeposits, { compact: true })}
          icon="savings"
          caption="Opening deposits, not a balance"
          hint={hintFor('bookedInitialDeposits')}
        />
        <StatCard
          loading={tilesLoading}
          label="Active agents"
          value={formatNumber(roster?.active)}
          icon="support_agent"
          caption={roster ? `${formatNumber(roster.total)} on roster` : undefined}
        />
      </div>

      <div className="dash-grid">
        <PanelCard title="Daily activity" panel={panelOf(overview, 'clients')} className="span-8">
          <TrendChart
            data={trend}
            series={[
              { key: 'clients', label: 'Clients created', axis: 'left' },
              { key: 'transactions', label: 'Transactions', axis: 'right' },
            ]}
          />
        </PanelCard>

        <PanelCard title="Transaction health" panel={panelOf(transactions, 'transactions')} className="span-4">
          <div className="metric-row">
            <div className="metric" title={hintFor('failureRate')}>
              <span className="metric-label">Failure rate</span>
              <span className="metric-value">{formatPercent(failureRate)}</span>
            </div>
            <div className="metric" title={hintFor('pendingBacklog')}>
              <span className="metric-label">Pending backlog</span>
              <span className="metric-value">{formatNumber(tx?.pendingBacklog)}</span>
            </div>
            <div className="metric" title={hintFor('transactionValue')}>
              <span className="metric-label">Value</span>
              <span className="metric-value">{formatValue(tx?.totalValue, { compact: true })}</span>
            </div>
          </div>
          <BreakdownChart title="By status" rows={toRows(byStatus, STATUS_LABELS)} />
          <BreakdownChart title="By type" rows={toRows(upperKeys(tx?.byType), TYPE_LABELS)} />
        </PanelCard>

        <PanelCard title="Portfolio composition" panel={panelOf(portfolio, 'accounts')} className="span-12">
          <div className="breakdown-grid breakdown-grid-4">
            <BreakdownChart title="Accounts by type" rows={toRows(portfolioAccounts?.byType)} />
            <BreakdownChart title="Accounts by status" rows={toRows(portfolioAccounts?.byStatus)} />
            <BreakdownChart title="Clients by verification" rows={toRows(portfolioClients?.byVerificationStatus)} />
            <BreakdownChart title="Accounts by branch" rows={toRows(portfolioAccounts?.byBranch)} labelWidth={110} />
          </div>
          <p className="panel-footnote" title={hintFor('accountsPerClient')}>
            Accounts per client: <strong>{perClient?.ratio == null ? NO_FIGURE : perClient.ratio.toFixed(1)}</strong>
            {' · '}Currency: {Object.keys(portfolioAccounts?.byCurrency ?? {}).join(', ') || NO_FIGURE}
          </p>
        </PanelCard>

        <PanelCard
          title="Agent performance"
          panel={panelOf(ranked.agents, 'agents')}
          className="span-12 card-flush"
          action={<SortSelect value={sort} onChange={setSort} />}
        >
          <AgentPerformanceTable rows={agentTable?.rows ?? []} sortedBy={agentTable?.sortedBy} />
        </PanelCard>

        <PanelCard title="Action items" panel={panelOf(actions, 'clients')} className="span-12">
          <ActionList groups={actionGroups} />
        </PanelCard>
      </div>

      <MetricDefinitions
        keys={[
          'clientsOnboarded', 'verificationRate', 'bookedInitialDeposits', 'accountsPerClient',
          'failureRate', 'pendingBacklog', 'transactionValue', 'staleUnverified',
        ]}
      />
    </div>
  )
}

export default AdminBusinessDashboard
