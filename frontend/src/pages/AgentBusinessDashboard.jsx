import { useMemo, useState } from 'react'

// Demo mockup: figures come from src/data/dashboard.js, shaped like
// docs/api/dashboard-api.yaml on feat/business-dashboard.
import { getDashboard, windowForDays } from '../data/dashboard.js'
import StatCard from '../components/StatCard.jsx'
import ActionList from '../components/dashboard/ActionList.jsx'
import BreakdownChart from '../components/dashboard/BreakdownChart.jsx'
import DashboardHeader from '../components/dashboard/DashboardHeader.jsx'
import MetricDefinitions from '../components/dashboard/MetricDefinitions.jsx'
import PanelCard from '../components/dashboard/PanelCard.jsx'
import TrendChart from '../components/dashboard/TrendChart.jsx'
import { hintFor } from '../components/dashboard/definitions.js'
import {
  daysSince, formatNumber, formatPercent, formatSGD, formatValue, toRows,
} from '../components/dashboard/format.js'

const STATUS_LABELS = { COMPLETED: 'Completed', PENDING: 'Pending', FAILED: 'Failed' }

function load(endpoint, days) {
  return getDashboard(endpoint, { role: 'AGENT', ...windowForDays(days) })
}

function AgentBusinessDashboard() {
  const [days, setDays] = useState(30)

  // Recomputed only when the window changes, like a cached API response.
  const { overview, portfolio, transactions, actions } = useMemo(() => ({
    overview: load('overview', days),
    portfolio: load('portfolio', days),
    transactions: load('transactions', days),
    actions: load('action-items', days),
  }), [days])

  const clients = overview.panels.clients.data
  const accounts = overview.panels.accounts.data
  const team = overview.panels.team.data
  const tx = transactions.panels.transactions.data
  const actionItems = actions.panels.clients.data
  const flagged = actions.panels.transactions.data.flagged
  const today = overview.window.to

  const verifiedShare = clients.byVerificationStatus.VERIFIED / clients.total
  const failureRate = tx.total === 0 ? 0 : tx.byStatus.FAILED / tx.total
  const trend = tx.daily.map((day) => ({ date: day.date, transactions: day.count }))

  const clientRow = (ref, key) => ({
    id: `${ref.clientId}-${key}`,
    clientId: ref.clientId,
    name: `${ref.firstName} ${ref.lastName}`,
    meta: `${daysSince(ref.createdAt, today)} days old`,
  })

  const actionGroups = [
    {
      key: 'stale',
      title: 'Stale unverified (> 7 days)',
      icon: 'hourglass_top',
      empty: 'Nothing here: no clients waiting on verification.',
      rows: actionItems.staleUnverified.map((ref) => clientRow(ref, 'stale')),
    },
    {
      key: 'no-account',
      title: 'No account yet',
      icon: 'account_balance_wallet',
      empty: 'Nothing here: every client has an account.',
      rows: actionItems.withoutAccount.map((ref) => clientRow(ref, 'no-account')),
    },
    {
      key: 'rejected',
      title: 'Rejected verification',
      icon: 'gpp_bad',
      empty: 'Nothing here: no rejected verifications in your book.',
      rows: actionItems.rejectedVerification.map((ref) => clientRow(ref, 'rejected')),
    },
    {
      key: 'transactions',
      title: 'Failed or long-pending transactions',
      icon: 'report',
      empty: 'Nothing here: no failed or long-pending transactions.',
      rows: flagged.map((row) => ({
        id: `tx-${row.transactionId}`,
        clientId: row.clientId,
        name: `${row.firstName} ${row.lastName}`,
        meta: `Txn ${row.transactionId} · ${STATUS_LABELS[row.status]}`,
      })),
    },
  ]

  const comparisonRows = team.teamMedian === null ? [] : [
    { key: 'me', name: 'Me', value: team.mine },
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
        <StatCard label="My clients" value={formatNumber(clients.total)} icon="groups" />
        <StatCard
          label="Added in window"
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
          label="My accounts"
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
      </div>

      <div className="dash-grid">
        <PanelCard title="My action queue" panel={actions.panels.clients} className="span-8">
          <ActionList groups={actionGroups} />
        </PanelCard>

        <div className="span-4 dash-stack">
          <PanelCard title="How I compare" panel={overview.panels.team}>
            {comparisonRows.length > 0 ? (
              <>
                <BreakdownChart title="Clients onboarded in window" rows={comparisonRows} />
                <p className="panel-footnote">{hintFor('teamMedian')}</p>
              </>
            ) : (
              <p className="empty-note">
                Not shown: the team is too small to compare anonymously (fewer than {team.minCohort} active agents).
              </p>
            )}
          </PanelCard>

          <PanelCard title="Client verification" panel={portfolio.panels.clients}>
            <BreakdownChart rows={toRows(portfolio.panels.clients.data.byVerificationStatus)} />
          </PanelCard>
        </div>

        <PanelCard title="My client transactions" panel={transactions.panels.transactions} className="span-12">
          <div className="metric-row">
            <div className="metric">
              <span className="metric-label">Transactions</span>
              <span className="metric-value">{formatNumber(tx.total)}</span>
            </div>
            <div className="metric" title={hintFor('transactionValue')}>
              <span className="metric-label">Value</span>
              <span className="metric-value">{formatValue(tx.totalValue, { compact: true })}</span>
            </div>
            <div className="metric" title={hintFor('failureRate')}>
              <span className="metric-label">Failure rate</span>
              <span className="metric-value">{formatPercent(failureRate)}</span>
            </div>
            <div className="metric" title={hintFor('pendingBacklog')}>
              <span className="metric-label">Pending backlog</span>
              <span className="metric-value">{formatNumber(tx.pendingBacklog)}</span>
            </div>
          </div>
          <div className="tx-grid">
            <div>
              <TrendChart data={trend} series={[{ key: 'transactions', label: 'Transactions per day' }]} height="sm" />
              <BreakdownChart title="By status" rows={toRows(tx.byStatus, STATUS_LABELS)} />
            </div>
            <div className="table-wrap">
              <table className="data-table">
                <caption className="table-caption">Most active clients by value</caption>
                <thead>
                  <tr>
                    <th>Client</th>
                    <th className="num">Transactions</th>
                    <th className="num">Value</th>
                  </tr>
                </thead>
                <tbody>
                  {tx.topClients.map((client) => (
                    <tr key={client.clientId}>
                      <td>{client.firstName} {client.lastName}</td>
                      <td className="num">{formatNumber(client.count)}</td>
                      <td className="num">{formatValue(client.value)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </PanelCard>

        <PanelCard title="My accounts" panel={portfolio.panels.accounts} className="span-12">
          <div className="breakdown-grid">
            <BreakdownChart title="Accounts by type" rows={toRows(portfolio.panels.accounts.data.byType)} />
            <BreakdownChart title="Accounts by status" rows={toRows(portfolio.panels.accounts.data.byStatus)} />
          </div>
          <p className="panel-footnote" title={hintFor('accountsPerClient')}>
            Accounts per client: <strong>{portfolio.panels.accounts.data.accountsPerClient}</strong>
          </p>
        </PanelCard>
      </div>

      <MetricDefinitions
        keys={[
          'clientsOnboarded', 'verifiedShare', 'bookedInitialDeposits', 'failureRate',
          'pendingBacklog', 'transactionValue', 'staleUnverified', 'withoutAccount', 'teamMedian',
        ]}
      />
    </div>
  )
}

export default AgentBusinessDashboard
