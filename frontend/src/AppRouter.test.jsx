import { fireEvent, render, screen } from '@testing-library/react'
import { useAuth } from 'react-oidc-context'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import AppRouter from './AppRouter.jsx'

vi.mock('react-oidc-context', () => ({ useAuth: vi.fn() }))

const signedOut = { isLoading: false, isAuthenticated: false, user: null }

function signedInAs(groups, state) {
  return {
    isLoading: false,
    isAuthenticated: true,
    user: { profile: { 'cognito:groups': groups, email: 'synthetic.user@example.test' }, state },
  }
}

function renderAt(path) {
  return render(<MemoryRouter initialEntries={[path]}><AppRouter /></MemoryRouter>)
}

describe('routes', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
  })

  it('sends a signed-out user to sign in, and back to the page afterwards', () => {
    vi.stubEnv('VITE_COGNITO_AUTHORITY', 'https://cognito-idp.ap-southeast-1.amazonaws.com/ap-southeast-1_Example')
    vi.stubEnv('VITE_COGNITO_CLIENT_ID', 'example-client-id')
    vi.stubEnv('VITE_COGNITO_DOMAIN', 'https://example.auth.ap-southeast-1.amazoncognito.com')
    const signinRedirect = vi.fn()
    useAuth.mockReturnValue({ ...signedOut, signinRedirect })

    renderAt('/agent/alerts')
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(screen.queryByText('Agent Dashboard')).not.toBeInTheDocument()
    expect(signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/agent/alerts' } })
  })

  it('says so when sign-in is not configured', () => {
    vi.stubEnv('VITE_COGNITO_CLIENT_ID', '')
    useAuth.mockReturnValue({ ...signedOut, signinRedirect: vi.fn() })

    renderAt('/login')

    expect(screen.getByRole('alert')).toHaveTextContent('Sign-in is not configured')
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeDisabled()
  })

  it('waits while the sign-in is checked', () => {
    useAuth.mockReturnValue({ ...signedOut, isLoading: true })

    renderAt('/admin')

    expect(screen.getByRole('status')).toHaveTextContent('Checking your sign-in')
  })

  it('keeps an agent out of admin pages', () => {
    useAuth.mockReturnValue(signedInAs(['Agent']))

    renderAt('/admin')

    expect(screen.getByRole('alert')).toHaveTextContent('not available to your role')
    expect(screen.queryByText('Admin Dashboard')).not.toBeInTheDocument()
  })

  it('lands an admin on the admin dashboard after the callback', () => {
    useAuth.mockReturnValue(signedInAs(['Admin']))

    renderAt('/auth/callback')

    expect(screen.getByRole('heading', { name: 'Admin Dashboard' })).toBeInTheDocument()
  })

  it('returns an agent to the page they asked for after the callback', () => {
    useAuth.mockReturnValue(signedInAs(['Agent'], { returnTo: '/agent' }))

    renderAt('/auth/callback')

    expect(screen.getByRole('heading', { name: 'Agent Dashboard' })).toBeInTheDocument()
  })

  it('shows a failed sign-in on the callback page', () => {
    useAuth.mockReturnValue({ ...signedOut, error: { message: 'invalid_grant' } })

    renderAt('/auth/callback')

    expect(screen.getByRole('alert')).toHaveTextContent('Sign-in failed: invalid_grant')
  })

  it('lets an admin open a client page from the admin dashboard', () => {
    useAuth.mockReturnValue(signedInAs(['Admin']))

    renderAt('/agent/client/1/transactions')

    expect(screen.queryByText('not available to your role')).not.toBeInTheDocument()
  })
})
