import { useEffect, useState } from 'react'

import { getPortfolio, replacePortfolio } from '../api/alertPortfolios.js'

// Records the dummy securities an agent holds for a client's investment account.
// This is project bookkeeping data: saving never buys or sells anything.

const EMPTY_ROW = { instrumentId: '', quantity: '', unitPrice: '', valuationCurrency: 'SGD', fxToSgd: '1' }

function saveProblem(error) {
  if (error.status === 409) return 'This portfolio changed or the account cannot hold positions. Reload and try again.'
  if (error.status === 422) return error.message || 'Check the positions and try again.'
  if (error.status === 404) return 'This account is not available to you.'
  if (error.status === 503) return 'Client records cannot be verified right now. Try again shortly.'
  return 'The holdings could not be saved. Try again.'
}

function toRow(position) {
  return {
    instrumentId: position.instrumentId,
    quantity: String(position.quantity),
    unitPrice: String(position.unitPrice),
    valuationCurrency: position.valuationCurrency,
    fxToSgd: String(position.fxToSgd),
  }
}

function HoldingsEditor({ accountId }) {
  const [state, setState] = useState({ loading: true, error: '', revision: 0, rows: [], total: null })
  const [saving, setSaving] = useState({ busy: false, error: '', done: '' })

  useEffect(() => {
    let current = true
    getPortfolio(accountId).then(
      (portfolio) => {
        if (current) setState({ loading: false, error: '', revision: portfolio.revision, rows: portfolio.positions.map(toRow), total: portfolio.totalSgd })
      },
      (error) => {
        if (!current) return
        // Nothing recorded yet is not an error.
        if (error.status === 404) setState({ loading: false, error: '', revision: 0, rows: [], total: null })
        else setState({ loading: false, error: 'The holdings could not be loaded.', revision: 0, rows: [], total: null })
      },
    )
    return () => { current = false }
  }, [accountId])

  function setCell(index, field, value) {
    setState((previous) => ({ ...previous, rows: previous.rows.map((row, i) => (i === index ? { ...row, [field]: value } : row)) }))
  }

  async function save(event) {
    event.preventDefault()
    setSaving({ busy: true, error: '', done: '' })
    const valuedAt = new Date().toISOString()
    try {
      const portfolio = await replacePortfolio(accountId, {
        expectedRevision: state.revision,
        sourceLabel: 'agent-entry',
        positions: state.rows.map((row) => ({
          instrumentId: row.instrumentId.trim(),
          quantity: row.quantity,
          unitPrice: row.unitPrice,
          valuationCurrency: row.valuationCurrency.trim().toUpperCase(),
          fxToSgd: row.fxToSgd,
          valuedAt,
        })),
      })
      setState({ loading: false, error: '', revision: portfolio.revision, rows: portfolio.positions.map(toRow), total: portfolio.totalSgd })
      setSaving({ busy: false, error: '', done: `Holdings saved (revision ${portfolio.revision}).` })
    } catch (error) {
      setSaving({ busy: false, error: saveProblem(error), done: '' })
    }
  }

  if (state.loading) return <p className="empty-note" role="status">Loading holdings…</p>

  return (
    <form className="holdings-editor" onSubmit={save} aria-label="Record holdings">
      <p className="holdings-note">
        Dummy project data. Saving records what this account holds; it does not buy or sell anything.
      </p>
      {state.error && <p className="error-text" role="alert">{state.error}</p>}
      {state.rows.length === 0 ? (
        <p className="empty-state">{state.revision === 0 ? 'No holdings recorded yet.' : 'This account is recorded as holding nothing.'}</p>
      ) : (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr><th>Instrument ID</th><th className="num">Quantity</th><th className="num">Unit price</th><th>Currency</th><th className="num">FX to SGD</th><th /></tr>
            </thead>
            <tbody>
              {state.rows.map((row, index) => (
                <tr key={index}>
                  <td><input aria-label={`Instrument ${index + 1}`} required maxLength={32} value={row.instrumentId} onChange={(e) => setCell(index, 'instrumentId', e.target.value)} /></td>
                  <td><input aria-label={`Quantity ${index + 1}`} required inputMode="decimal" value={row.quantity} onChange={(e) => setCell(index, 'quantity', e.target.value)} /></td>
                  <td><input aria-label={`Unit price ${index + 1}`} required inputMode="decimal" value={row.unitPrice} onChange={(e) => setCell(index, 'unitPrice', e.target.value)} /></td>
                  <td><input aria-label={`Currency ${index + 1}`} required maxLength={3} value={row.valuationCurrency} onChange={(e) => setCell(index, 'valuationCurrency', e.target.value)} /></td>
                  <td><input aria-label={`FX to SGD ${index + 1}`} required inputMode="decimal" value={row.fxToSgd} onChange={(e) => setCell(index, 'fxToSgd', e.target.value)} /></td>
                  <td>
                    <button type="button" onClick={() => setState((p) => ({ ...p, rows: p.rows.filter((_, i) => i !== index) }))}>Remove</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {state.total && <p>Recorded total: SGD {state.total}</p>}
      {saving.error && <p className="error-text" role="alert">{saving.error}</p>}
      {saving.done && <p className="success-message" role="status">{saving.done}</p>}
      <div className="holdings-actions">
        <button type="button" disabled={state.rows.length >= 100} onClick={() => setState((p) => ({ ...p, rows: [...p.rows, { ...EMPTY_ROW }] }))}>Add position</button>
        <button type="submit" disabled={saving.busy}>{saving.busy ? 'Saving…' : 'Save holdings'}</button>
      </div>
    </form>
  )
}

export default HoldingsEditor
