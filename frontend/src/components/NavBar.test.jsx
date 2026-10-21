import { fireEvent, render, screen } from '@testing-library/react'
import { useAuth } from 'react-oidc-context'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'

import { signOut } from '../auth/signOut.js'
import NavBar from './NavBar.jsx'

vi.mock('react-oidc-context', () => ({ useAuth: vi.fn() }))
vi.mock('../auth/signOut.js', () => ({ signOut: vi.fn() }))

function renderNavBar(auth) {
  useAuth.mockReturnValue(auth)
  return render(<MemoryRouter><NavBar /></MemoryRouter>)
}

describe('NavBar', () => {
  it('offers only sign-in when signed out', () => {
    renderNavBar({ isAuthenticated: false, user: null })

    expect(screen.getByRole('link', { name: /Login/ })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Dashboard/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Sign out/ })).not.toBeInTheDocument()
  })

  it("shows an agent their own pages only, and signs them out", () => {
    renderNavBar({ isAuthenticated: true, user: { profile: { 'cognito:groups': ['Agent'], email: 'synthetic.agent@example.test' } } })

    expect(screen.getByRole('link', { name: /Agent Home/ })).toHaveAttribute('href', '/agent')
    expect(screen.getByRole('link', { name: /Agent Dashboard/ })).toHaveAttribute('href', '/insights/agent')
    expect(screen.getByRole('link', { name: /Calendar/ })).toHaveAttribute('href', '/agent/calendar')
    expect(screen.getByRole('link', { name: /Follow-up Emails/ })).toHaveAttribute('href', '/agent/drafts')
    expect(screen.queryByRole('link', { name: /Admin Home/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Admin Dashboard/ })).not.toBeInTheDocument()
    expect(screen.getByText('synthetic.agent@example.test')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /Sign out/ }))

    expect(signOut).toHaveBeenCalledOnce()
    expect(screen.getByRole('button', { name: /Signing out/ })).toBeDisabled()
  })

  it('shows an admin the admin pages only', () => {
    renderNavBar({ isAuthenticated: true, user: { profile: { 'cognito:groups': ['Admin'], email: 'synthetic.admin@example.test' } } })

    expect(screen.getByRole('link', { name: /Admin Home/ })).toHaveAttribute('href', '/admin')
    expect(screen.getByRole('link', { name: /Admin Dashboard/ })).toHaveAttribute('href', '/insights/admin')
    expect(screen.queryByRole('link', { name: /Agent Home/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Agent Dashboard/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Calendar/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Follow-up Emails/ })).not.toBeInTheDocument()
  })
})
