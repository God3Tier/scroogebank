import AmountLines from './AmountLines.jsx'
import { DEFAULT_CURRENCY, DEPOSITS, SORT_OPTIONS } from './definitions.js'
import { formatAmounts, formatNumber, formatPercent } from './format.js'

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

// The currencies deposits can be ranked in: the window's, plus the chosen one
// and the default, so the choice stays on screen while a new ranking loads.
function currencyOptions(currencies, currency) {
  return [...new Set([...(currencies ?? []), currency, DEFAULT_CURRENCY])].sort()
}

// Deposits are never added across currencies, so a deposits ranking is in one
// currency at a time (ADR-0062). The choice appears only when there is one.
export function AgentTableControls({ sort, onSortChange, currency, currencies, onCurrencyChange }) {
  const options = currencyOptions(currencies, currency)
  return (
    <div className="inline-controls">
      <SortSelect value={sort} onChange={onSortChange} />
      {sort === DEPOSITS && options.length > 1 && (
        <label className="inline-select">
          In
          <select value={currency} onChange={(event) => onCurrencyChange(event.target.value)}>
            {options.map((option) => <option key={option} value={option}>{option}</option>)}
          </select>
        </label>
      )}
    </div>
  )
}

function initials(name) {
  return name.split(' ').map((part) => part.charAt(0)).join('').slice(0, 2)
}

// name and enabled are null for a Cognito sub with figures but no roster
// entry (dashboard-api.yaml).
const UNKNOWN_AGENT = 'Unknown agent'

function statusOf(enabled) {
  if (enabled === null || enabled === undefined) return { label: 'Not on roster', className: '' }
  return enabled ? { label: 'Active', className: 'badge-completed' } : { label: 'Disabled', className: '' }
}

function AgentPerformanceTable({ rows, sortedBy, sortCurrency }) {
  const sortedClass = (key) => (sortedBy === key ? 'num sorted' : 'num')

  return (
    <div className="table-wrap">
      <table className="data-table agent-table">
        <thead>
          <tr>
            <th>Agent</th>
            <th>Status</th>
            <th className={sortedClass('clientsOnboarded')}>Onboarded</th>
            <th className="num">Verified / Unverified</th>
            <th className={sortedClass('verificationRate')}>Verification rate</th>
            <th className={sortedClass('accountsOpened')}>Accounts opened</th>
            <th className={sortedClass(DEPOSITS)}>
              Booked deposits{sortedBy === DEPOSITS && sortCurrency ? ` (${sortCurrency})` : ''}
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const name = row.name ?? UNKNOWN_AGENT
            const status = statusOf(row.enabled)
            return (
            <tr key={row.agentId}>
              <td>
                <span className="agent-cell">
                  <span className="mini-avatar" aria-hidden="true">{initials(name)}</span>
                  {name}
                </span>
              </td>
              <td>
                <span className={`badge ${status.className}`}>{status.label}</span>
              </td>
              <td className="num">{formatNumber(row.clientsOnboarded)}</td>
              <td className="num split-cell">
                {formatNumber(row.verified)} / {formatNumber(row.unverified)}
              </td>
              <td className="num">{formatPercent(row.verificationRate)}</td>
              <td className="num">{formatNumber(row.accountsOpened)}</td>
              <td className="num" title={formatAmounts(row.bookedInitialDeposits)}>
                <AmountLines amounts={row.bookedInitialDeposits} compact />
              </td>
            </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

export default AgentPerformanceTable
