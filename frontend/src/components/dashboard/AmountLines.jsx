import { NO_FIGURE, amountEntries, formatNumber } from './format.js'

// Amounts by currency, one line each, so they read as separate amounts rather
// than a sum or a conversion (ADR-0062).
function AmountLines({ amounts, label, compact = false }) {
  if (amounts === null || amounts === undefined) return NO_FIGURE
  const entries = amountEntries(amounts, { compact })
  if (entries.length === 0) return formatNumber(0)
  return (
    <ul className="amount-lines" aria-label={label}>
      {entries.map(({ currency, amount }) => (
        <li key={currency}>
          <span className="amount-currency">{currency}</span>{' '}{amount}
        </li>
      ))}
    </ul>
  )
}

export default AmountLines
