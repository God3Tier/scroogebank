import { useState } from 'react'

import { panelData, windowForDays } from '../api/dashboard.js'
import StatCard from '../components/StatCard.jsx'
import ActionList from '../components/dashboard/ActionList.jsx'
import AmountLines from '../components/dashboard/AmountLines.jsx'
import BreakdownChart from '../components/dashboard/BreakdownChart.jsx'
import DashboardHeader from '../components/dashboard/DashboardHeader.jsx'
import MetricDefinitions from '../components/dashboard/MetricDefinitions.jsx'
import PanelCard from '../components/dashboard/PanelCard.jsx'
import TrendChart from '../components/dashboard/TrendChart.jsx'
import useDashboards, { panelOf, upperKeys } from '../components/dashboard/useDashboards.js'
import { hintFor } from '../components/dashboard/definitions.js'
import { dailyTrend, ratio } from '../components/dashboard/figures.js'
import {
  NO_FIGURE, currenciesNote, daysSince, formatNumber, formatPercent, formatTotal, toRows,
} from '../components/dashboard/format.js'

const STATUS_LABELS = { COMPLETED: 'Completed', PENDING: 'Pending', FAILED: 'Failed' }
const STALE_DAYS = 7
// TeamComparison.MIN_COHORT in dashboard-service: below it no median is sent.
const TEAM_MIN_COHORT = 5

// Figures come from dashboard-service (docs/api/dashboard-api.yaml), scoped
// to the signed-in agent by their token. Panels it does not serve yet (most
// active clients, clients without an account, rejected verifications, flagged
// transactions) are not shown; see docs/architecture/open-items.md.
function AgentBusinessDashboard() {
  const [days, setDays] = useState(30)
  const period = windowForDays(days)

  const { responses } = useDashboards({
    overview: ['overview', period],
    portfolio: ['portfolio', period],
    transactions: ['transactions', period],
    actions: ['action-items', { ...period, staleDays: STALE_DAYS }],
  })
  const { overview, portfolio, transactions, actions } = responses
  // The stat tiles all come from the overview.
  const tilesLoading = overview === undefined

  const clients = panelData(overview?.panels?.clients)
  const accounts = panelData(overview?.panels?.accounts)
  const team = panelData(overview?.panels?.teamComparison)
  const tx = panelData(transactions?.panels?.transactions)
  const portfolioClients = panelData(portfolio?.panels?.clients)
  const portfolioAccounts = panelData(portfolio?.panels?.accounts)
  const perClient = panelData(portfolio?.panels?.accountsPerClient)
  const actionItems = panelData(actions?.panels?.clients)
  const today = period.to

  const byStatus = upperKeys(tx?.byStatus)
  const verifiedShare = clients ? ratio(clients.byVerificationStatus?.VERIFIED ?? 0, clients.total) : null
  const failureRate = tx ? ratio(byStatus.FAILED ?? 0, tx.total) : null
  const trend = dailyTrend(null, tx?.daily, period)

  const actionGroups = [
    {
      key: 'stale',
      title: `Stale unverified (> ${STALE_DAYS} days)`,
      icon: 'hourglass_top',
      empty: 'Nothing here: no clients waiting on verification.',
      total: actionItems?.staleUnverifiedTotal,
      rows: (actionItems?.staleUnverified ?? []).map((ref) => ({
        id: `${ref.clientId}-stale`,
        clientId: ref.clientId,
        name: `${ref.firstName} ${ref.lastName}`,
        meta: `${daysSince(ref.createdAt, today)} days old`,
      })),
    },
  ]

  const comparisonRows = team?.teamMedian == null ? [] : [
    { key: 'me', name: 'Me', value: team.clientsOnboarded },
    { key: 'median', name: 'Team median', value: team.teamMedian },
  ]

  return (
    <div className="page dashboard-page">
      <DashboardHeader
        title="My Business Dashboard"
        subtitle="Your book of clients and what needs attention"
        scopeLabel="My clients only"
        days={days}
        onDaysChange={setDays}
        response={overview}
      />

      <div className="stat-grid stat-grid-dense">
        <StatCard loading={tilesLoading} label="My clients" value={formatNumber(clients?.total)} icon="groups" />
        <StatCard
          loading={tilesLoading}
          label="Added in window"
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
          label="My accounts"
          value={formatNumber(accounts?.total)}
          icon="account_balance"
          caption={accounts ? `+${formatNumber(accounts.openedInWindow)} opened` : undefined}
        />
        <StatCard
          loading={tilesLoading}
          label="Booked initial deposits"
          value={<AmountLines amounts={accounts?.bookedInitialDeposits} label="Booked initial deposits by currency" compact />}
          icon="savings"
          caption="Opening deposits, not a balance"
          hint={hintFor('bookedInitialDeposits')}
        />
      </div>

      <div className="dash-grid">
        <PanelCard title="My action queue" panel={panelOf(actions, 'clients')} className="span-8">
          <ActionList groups={actionGroups} />
        </PanelCard>

        <div className="span-4 dash-stack">
          <PanelCard title="How I compare" panel={panelOf(overview, 'teamComparison')}>
            {comparisonRows.length > 0 ? (
              <>
                <BreakdownChart title="Clients onboarded in window" rows={comparisonRows} />
                <p className="panel-footnote">{hintFor('teamMedian')}</p>
              </>
            ) : (
              <p className="empty-note">
                Not shown: the team is too small to compare anonymously (fewer than {TEAM_MIN_COHORT} agents).
              </p>
            )}
          </PanelCard>

          <PanelCard title="Client verification" panel={panelOf(portfolio, 'clients')}>
            <BreakdownChart rows={toRows(portfolioClients?.byVerificationStatus)} />
          </PanelCard>
        </div>

        <PanelCard title="My client transactions" panel={panelOf(transactions, 'transactions')} className="span-12">
          <div className="metric-row">
            <div className="metric">
              <span className="metric-label">Transactions</span>
              <span className="metric-value">{formatNumber(tx?.total)}</span>
            </div>
            <div className="metric" title={hintFor('transactionValue')}>
              <span className="metric-label">Value</span>
              <span className="metric-value">{formatTotal(tx?.totalValue, tx?.currencies, { compact: true })}</span>
              {currenciesNote(tx?.currencies) && <span className="metric-note">{currenciesNote(tx?.currencies)}</span>}
            </div>
            <div className="metric" title={hintFor('failureRate')}>
              <span className="metric-label">Failure rate</span>
              <span className="metric-value">{formatPercent(failureRate)}</span>
            </div>
            <div className="metric" title={hintFor('pendingBacklog')}>
              <span className="metric-label">Pending backlog</span>
              <span className="metric-value">{formatNumber(tx?.pendingBacklog)}</span>
            </div>
          </div>
          <div className="tx-grid">
            <TrendChart data={trend} series={[{ key: 'transactions', label: 'Transactions per day' }]} height="sm" />
            <BreakdownChart title="By status" rows={toRows(byStatus, STATUS_LABELS)} />
          </div>
        </PanelCard>

        <PanelCard title="My accounts" panel={panelOf(portfolio, 'accounts')} className="span-12">
          <div className="breakdown-grid">
            <BreakdownChart title="Accounts by type" rows={toRows(portfolioAccounts?.byType)} />
            <BreakdownChart title="Accounts by status" rows={toRows(portfolioAccounts?.byStatus)} />
          </div>
          <p className="panel-footnote" title={hintFor('accountsPerClient')}>
            Accounts per client: <strong>{perClient?.ratio == null ? NO_FIGURE : perClient.ratio.toFixed(1)}</strong>
          </p>
        </PanelCard>
      </div>

      <MetricDefinitions
        keys={[
          'clientsOnboarded', 'verifiedShare', 'bookedInitialDeposits', 'failureRate',
          'pendingBacklog', 'transactionValue', 'staleUnverified', 'teamMedian',
        ]}
      />
    </div>
  )
}

export default AgentBusinessDashboard
