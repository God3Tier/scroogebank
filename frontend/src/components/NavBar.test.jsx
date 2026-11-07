import { fireEvent, render, screen } from '@testing-library/react'
import { useAuth } from 'react-oidc-context'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'

import { signOut } from '../auth/signOut.js'
import NavBar from './NavBar.jsx'

vi.mock('react-oidc-context', () => ({ useAuth: vi.fn() }))
vi.mock('../auth/signOut.js', () => ({ signOut: vi.fn() }))

function renderNavBar(auth, path = '/') {
  useAuth.mockReturnValue(auth)
  return render(<MemoryRouter initialEntries={[path]}><NavBar /></MemoryRouter>)
}

const AGENT_AUTH = { isAuthenticated: true, user: { profile: { 'cognito:groups': ['Agent'], email: 'synthetic.agent@example.test' } } }
const ADMIN_AUTH = { isAuthenticated: true, user: { profile: { 'cognito:groups': ['Admin'], email: 'synthetic.admin@example.test' } } }

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
    expect(screen.getByRole('link', { name: /Event Driven Alerts/ })).toHaveAttribute('href', '/agent/alerts')
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
    expect(screen.getByRole('link', { name: /Event Driven Alerts/ })).toHaveAttribute('href', '/admin/alerts')
    expect(screen.queryByRole('link', { name: /Agent Home/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Agent Dashboard/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Calendar/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Follow-up Emails/ })).not.toBeInTheDocument()
  })

  it("highlights only the agent's current page, not Agent Home as well", () => {
    renderNavBar(AGENT_AUTH, '/agent/drafts')

    expect(screen.getByRole('link', { name: /Follow-up Emails/ })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: /Agent Home/ })).not.toHaveAttribute('aria-current')
  })

  it('highlights Agent Home on its own page', () => {
    renderNavBar(AGENT_AUTH, '/agent')

    expect(screen.getByRole('link', { name: /Agent Home/ })).toHaveAttribute('aria-current', 'page')
  })

  it.each([['/agent/alerts', /Event Driven Alerts/], ['/agent/drafts', /Follow-up Emails/]])(
    'highlights only the link for %s',
    (path, name) => {
      renderNavBar(AGENT_AUTH, path)

      expect(screen.getByRole('link', { name })).toHaveAttribute('aria-current', 'page')
      expect(screen.getAllByRole('link').filter((link) => link.getAttribute('aria-current'))).toHaveLength(1)
    },
  )

  it.each(['/agent/clients', '/agent/client/client-1/transactions'])(
    'highlights Agent Home on %s, which has no link of its own',
    (path) => {
      renderNavBar(AGENT_AUTH, path)

      expect(screen.getByRole('link', { name: /Agent Home/ })).toHaveAttribute('aria-current', 'page')
      expect(screen.getAllByRole('link').filter((link) => link.getAttribute('aria-current'))).toHaveLength(1)
    },
  )

  it('highlights Admin Home on the admin pages under it', () => {
    renderNavBar(ADMIN_AUTH, '/admin/agents')

    expect(screen.getByRole('link', { name: /Admin Home/ })).toHaveAttribute('aria-current', 'page')
  })
})
