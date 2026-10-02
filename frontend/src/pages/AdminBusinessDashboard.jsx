import { useMemo, useState } from 'react'

// Demo mockup: figures come from src/data/dashboard.js, shaped like
// docs/api/dashboard-api.yaml on feat/business-dashboard.
import { getDashboard, windowForDays } from '../data/dashboard.js'
import StatCard from '../components/StatCard.jsx'
import ActionList from '../components/dashboard/ActionList.jsx'
import AgentPerformanceTable, { SortSelect } from '../components/dashboard/AgentPerformanceTable.jsx'
import BreakdownChart from '../components/dashboard/BreakdownChart.jsx'
import DashboardHeader from '../components/dashboard/DashboardHeader.jsx'
import MetricDefinitions from '../components/dashboard/MetricDefinitions.jsx'
import PanelCard from '../components/dashboard/PanelCard.jsx'
import TrendChart from '../components/dashboard/TrendChart.jsx'
import { hintFor } from '../components/dashboard/definitions.js'
import {
  daysSince, formatAge, formatNumber, formatPercent, formatSGD, formatTime, formatValue, toRows,
} from '../components/dashboard/format.js'

const STATUS_LABELS = { COMPLETED: 'Completed', PENDING: 'Pending', FAILED: 'Failed' }
const TYPE_LABELS = { DEPOSIT: 'Deposit', WITHDRAWAL: 'Withdrawal' }

function load(endpoint, days, extra = {}) {
  return getDashboard(endpoint, { role: 'ADMIN', ...windowForDays(days), ...extra })
}

function AdminBusinessDashboard() {
  const [days, setDays] = useState(30)
  const [sort, setSort] = useState('clientsOnboarded')

  // Recomputed only when the inputs change, like a cached API response.
  const { overview, portfolio, transactions, actions } = useMemo(() => ({
    overview: load('overview', days),
    portfolio: load('portfolio', days),
    transactions: load('transactions', days),
    actions: load('action-items', days),
  }), [days])
  const agents = useMemo(() => load('agents', days, { sort }), [days, sort])

  const clients = overview.panels.clients.data
  const accounts = overview.panels.accounts.data
  const roster = overview.panels.agents.data
  const tx = transactions.panels.transactions.data
  const ingestion = transactions.panels.ingestion.data
  const actionItems = actions.panels.clients.data
  const flagged = actions.panels.transactions.data.flagged
  const today = overview.window.to

  const verifiedShare = clients.byVerificationStatus.VERIFIED / clients.total
  const failureRate = tx.total === 0 ? 0 : tx.byStatus.FAILED / tx.total
  const ingestionStale = ingestion.ageSeconds > ingestion.warnAfterSeconds

  const trend = clients.dailyCreated.map((day, i) => ({
    date: day.date,
    clients: day.count,
    transactions: tx.daily[i]?.count ?? 0,
  }))

  const clientRow = (ref, detail) => ({
    id: `${ref.clientId}-${detail}`,
    clientId: ref.clientId,
    name: `${ref.firstName} ${ref.lastName}`,
    detail: `Agent: ${ref.agentName}`,
    meta: `${daysSince(ref.createdAt, today)} days old`,
  })

  const actionGroups = [
    {
      key: 'stale',
      title: 'Stale unverified clients (> 7 days)',
      icon: 'hourglass_top',
      empty: 'No stale unverified clients.',
      rows: actionItems.staleUnverified.map((ref) => clientRow(ref, 'stale')),
    },
    {
      key: 'no-account',
      title: 'Clients without an account',
      icon: 'account_balance_wallet',
      empty: 'Every client has an account.',
      rows: actionItems.withoutAccount.map((ref) => clientRow(ref, 'no-account')),
    },
    {
      key: 'rejected',
      title: 'Rejected verifications',
      icon: 'gpp_bad',
      empty: 'No rejected verifications.',
      rows: actionItems.rejectedVerification.map((ref) => clientRow(ref, 'rejected')),
    },
    {
      key: 'transactions',
      title: 'Failed or long-pending transactions',
      icon: 'report',
      empty: 'No failed or long-pending transactions.',
      rows: flagged.map((row) => ({
        id: `tx-${row.transactionId}`,
        clientId: row.clientId,
        name: `${row.firstName} ${row.lastName}`,
        detail: `Agent: ${row.agentName}`,
        meta: `Txn ${row.transactionId} · ${STATUS_LABELS[row.status]}`,
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
        <StatCard label="Total clients" value={formatNumber(clients.total)} icon="groups" />
        <StatCard
          label="Clients added"
          value={formatNumber(clients.createdInWindow)}
          icon="person_add"
          caption={`Last ${days} days`}
          hint={hintFor('clientsOnboarded')}
        />
        <StatCard
          label="Verified share"
          value={formatPercent(verifiedShare)}
          icon="verified_user"
          hint={hintFor('verifiedShare')}
        />
        <StatCard
          label="Total accounts"
          value={formatNumber(accounts.total)}
          icon="account_balance"
          caption={`+${formatNumber(accounts.openedInWindow)} opened`}
        />
        <StatCard
          label="Booked initial deposits"
          value={formatSGD(accounts.bookedInitialDeposits, { compact: true })}
          icon="savings"
          caption="Opening deposits, not a balance"
          hint={hintFor('bookedInitialDeposits')}
        />
        <StatCard
          label="Active agents"
          value={roster.filter((agent) => agent.enabled).length}
          icon="support_agent"
          caption={`${roster.length} on roster`}
        />
      </div>

      <div className="dash-grid">
        <PanelCard title="Daily activity" panel={overview.panels.clients} className="span-8">
          <TrendChart
            data={trend}
            series={[
              { key: 'clients', label: 'Clients created', axis: 'left' },
              { key: 'transactions', label: 'Transactions', axis: 'right' },
            ]}
          />
        </PanelCard>

        <PanelCard title="Transaction health" panel={transactions.panels.transactions} className="span-4">
          <div className="metric-row">
            <div className="metric" title={hintFor('failureRate')}>
              <span className="metric-label">Failure rate</span>
              <span className="metric-value">{formatPercent(failureRate)}</span>
            </div>
            <div className="metric" title={hintFor('pendingBacklog')}>
              <span className="metric-label">Pending backlog</span>
              <span className="metric-value">{formatNumber(tx.pendingBacklog)}</span>
            </div>
            <div className="metric" title={hintFor('transactionValue')}>
              <span className="metric-label">Value</span>
              <span className="metric-value">{formatValue(tx.totalValue, { compact: true })}</span>
            </div>
          </div>
          <BreakdownChart title="By status" rows={toRows(tx.byStatus, STATUS_LABELS)} />
          <BreakdownChart title="By type" rows={toRows(tx.byType, TYPE_LABELS)} />
        </PanelCard>

        <PanelCard title="Ingestion freshness" panel={transactions.panels.ingestion} className="span-12 panel-compact">
          <div className={`freshness ${ingestionStale ? 'freshness-warn' : 'freshness-ok'}`}>
            <span className="material-symbols-outlined" aria-hidden="true">
              {ingestionStale ? 'warning' : 'sync'}
            </span>
            <div>
              <p className="freshness-value">Last synced {formatAge(ingestion.ageSeconds)} ago</p>
              <p className="freshness-detail">
                at {formatTime(ingestion.lastSyncedAt)} · warns after {formatAge(ingestion.warnAfterSeconds)}
              </p>
            </div>
            <span className={`badge ${ingestionStale ? 'badge-pending' : 'badge-completed'}`}>
              {ingestionStale ? 'Stale' : 'Healthy'}
            </span>
          </div>
        </PanelCard>

        <PanelCard title="Portfolio composition" panel={portfolio.panels.accounts} className="span-12">
          <div className="breakdown-grid breakdown-grid-4">
            <BreakdownChart title="Accounts by type" rows={toRows(portfolio.panels.accounts.data.byType)} />
            <BreakdownChart title="Accounts by status" rows={toRows(portfolio.panels.accounts.data.byStatus)} />
            <BreakdownChart title="Clients by verification" rows={toRows(portfolio.panels.clients.data.byVerificationStatus)} />
            <BreakdownChart title="Accounts by branch" rows={toRows(portfolio.panels.accounts.data.byBranch)} labelWidth={110} />
          </div>
          <p className="panel-footnote" title={hintFor('accountsPerClient')}>
            Accounts per client: <strong>{portfolio.panels.accounts.data.accountsPerClient}</strong> · Currency:{' '}
            {Object.keys(portfolio.panels.accounts.data.byCurrency).join(', ')}
          </p>
        </PanelCard>

        <PanelCard
          title="Agent performance"
          panel={agents.panels.agents}
          className="span-12 card-flush"
          action={<SortSelect value={sort} onChange={setSort} />}
        >
          <AgentPerformanceTable rows={agents.panels.agents.data.rows} sortedBy={agents.panels.agents.data.sortedBy} />
        </PanelCard>

        <PanelCard title="Action items" panel={actions.panels.clients} className="span-12">
          <ActionList groups={actionGroups} />
        </PanelCard>
      </div>

      <MetricDefinitions
        keys={[
          'clientsOnboarded', 'verificationRate', 'bookedInitialDeposits', 'accountsPerClient',
          'failureRate', 'pendingBacklog', 'transactionValue', 'ingestionFreshness',
          'staleUnverified', 'withoutAccount',
        ]}
      />
    </div>
  )
}

export default AdminBusinessDashboard
