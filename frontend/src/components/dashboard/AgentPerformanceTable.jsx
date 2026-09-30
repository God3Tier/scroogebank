import { SORT_OPTIONS } from './definitions.js'
import { formatNumber, formatPercent, formatSGD } from './format.js'

export function SortSelect({ value, onChange }) {
  return (
    <label className="inline-select">
      Sort by
      <select value={value} onChange={(event) => onChange(event.target.value)}>
        {SORT_OPTIONS.map((option) => (
          <option key={option.value} value={option.value}>{option.label}</option>
        ))}
      </select>
    </label>
  )
}

function initials(name) {
  return name.split(' ').map((part) => part.charAt(0)).join('').slice(0, 2)
}

function AgentPerformanceTable({ rows, sortedBy }) {
  const sortedClass = (key) => (sortedBy === key ? 'num sorted' : 'num')

  return (
    <div className="table-wrap">
      <table className="data-table agent-table">
        <thead>
          <tr>
            <th>Agent</th>
            <th>Status</th>
            <th className={sortedClass('clientsOnboarded')}>Onboarded</th>
            <th className="num">Verified / Unverified / Rejected</th>
            <th className={sortedClass('verificationRate')}>Verification rate</th>
            <th className={sortedClass('accountsOpened')}>Accounts opened</th>
            <th className={sortedClass('bookedInitialDeposits')}>Booked deposits</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.agentId}>
              <td>
                <span className="agent-cell">
                  <span className="mini-avatar" aria-hidden="true">{initials(row.name)}</span>
                  {row.name}
                </span>
              </td>
              <td>
                <span className={`badge ${row.enabled ? 'badge-completed' : ''}`}>
                  {row.enabled ? 'Active' : 'Disabled'}
                </span>
              </td>
              <td className="num">{formatNumber(row.clientsOnboarded)}</td>
              <td className="num split-cell">
                {formatNumber(row.verified)} / {formatNumber(row.unverified)} / {formatNumber(row.rejected)}
              </td>
              <td className="num">{formatPercent(row.verificationRate)}</td>
              <td className="num">{formatNumber(row.accountsOpened)}</td>
              <td className="num" title={formatSGD(row.bookedInitialDeposits)}>{formatSGD(row.bookedInitialDeposits, { compact: true })}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export default AgentPerformanceTable
