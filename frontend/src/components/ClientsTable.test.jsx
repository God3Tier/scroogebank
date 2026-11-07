import { fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import ClientsTable from './ClientsTable.jsx'

const STATUS = { NEW: 'New', SENT_OUT: 'Sent Out' }
const make = (count) => Array.from({ length: count }, (_, i) => ({
  alertId: `a${i}`, clientRef: `c${i}`, portfolioPercentage: 10, amountSgd: 1000, score: 61, status: 'NEW',
}))
const names = Object.fromEntries(Array.from({ length: 12 }, (_, i) => [`c${i}`, `Client ${String.fromCharCode(65 + i)}`]))
const label = (client, map) => map[client.clientRef] || client.clientRef

function Harness({ clients }) {
  const [selected, setSelected] = useState({})
  return (
    <>
      <ClientsTable clients={clients} names={names} selected={selected} setSelected={setSelected}
        selectable={(c) => c.status !== 'SENT_OUT'} statusLabels={STATUS} clientLabel={label} />
      <output data-testid="ticked">{Object.values(selected).filter(Boolean).length}</output>
    </>
  )
}

describe('ClientsTable', () => {
  it('shows every client with no search or pages when there are five or fewer', () => {
    render(<Harness clients={make(5)} />)
    expect(screen.queryByLabelText('Search clients')).toBeNull()
    expect(screen.queryByRole('navigation', { name: 'Client pages' })).toBeNull()
    expect(screen.getAllByRole('checkbox')).toHaveLength(5)
  })

  it('pages five at a time and keeps ticks across pages', () => {
    render(<Harness clients={make(12)} />)
    expect(screen.getAllByRole('checkbox')).toHaveLength(5)
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select Client A' }))
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(screen.getByText('Page 2 of 3')).toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: 'Select Client A' })).toBeNull()
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select Client F' }))
    fireEvent.click(screen.getByRole('button', { name: 'Previous' }))
    expect(screen.getByRole('checkbox', { name: 'Select Client A' })).toBeChecked()
    expect(screen.getByTestId('ticked')).toHaveTextContent('2')
  })

  it('searches by name, goes back to page one, and says when nothing matches', () => {
    render(<Harness clients={make(12)} />)
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    fireEvent.change(screen.getByLabelText('Search clients'), { target: { value: 'client k' } })
    expect(screen.getByText('Page 1 of 1')).toBeInTheDocument()
    expect(screen.getAllByRole('checkbox')).toHaveLength(1)
    expect(screen.getByRole('checkbox', { name: 'Select Client K' })).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Search clients'), { target: { value: 'zzz' } })
    expect(screen.getByText('No client matches "zzz".')).toBeInTheDocument()
  })

  it('does not let a Sent Out client be ticked', () => {
    const clients = make(2)
    clients[0].status = 'SENT_OUT'
    render(<Harness clients={clients} />)
    expect(screen.getByRole('checkbox', { name: 'Select Client A' })).toBeDisabled()
  })
})
