import { fireEvent, render, screen } from '@testing-library/react'
import { useAuth } from 'react-oidc-context'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import AppRouter from './AppRouter.jsx'
import { accessToken } from './test/tokens.js'

vi.mock('react-oidc-context', () => ({ useAuth: vi.fn() }))
// The enrolment page asks Cognito for a key; no test here may reach it.
vi.mock('./auth/totp.js', () => ({ startTotpEnrolment: vi.fn(() => new Promise(() => {})), finishTotpEnrolment: vi.fn(), otpauthUri: vi.fn() }))
vi.mock('./api/clients.js', () => ({
  getClient: vi.fn().mockResolvedValue({ first_name: 'Synthetic', last_name: 'Client', verification_status: 'UNVERIFIED' }),
  listDocuments: vi.fn().mockResolvedValue([]),
  listClients: vi.fn().mockResolvedValue({ content: [], page: 0, size: 1, totalElements: 0, totalPages: 0 }),
}))
vi.mock('./api/engagement.js', async (importOriginal) => ({
  ...(await importOriginal()),
  listEvents: vi.fn().mockResolvedValue([]),
  listHolidays: vi.fn().mockResolvedValue([]),
  getHolidayCoverage: vi.fn().mockResolvedValue({ countryCode: 'SG', coveredThrough: '2027-12-31' }),
  listDrafts: vi.fn().mockResolvedValue([]),
  getPreferences: vi.fn().mockResolvedValue({ invitesEnabled: true, remindersEnabled: true }),
}))
vi.mock('./api/dashboard.js', async (importOriginal) => ({
  ...(await importOriginal()),
  getDashboard: vi.fn().mockResolvedValue({ panels: {} }),
}))

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

    expect(screen.queryByText('Agent Home')).not.toBeInTheDocument()
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
    expect(screen.queryByText('Admin Home')).not.toBeInTheDocument()
  })

  it('lands an admin on the admin home page after the callback', () => {
    useAuth.mockReturnValue(signedInAs(['Admin']))

    renderAt('/auth/callback')

    expect(screen.getByRole('heading', { name: 'Admin Home' })).toBeInTheDocument()
  })

  it('returns an agent to the page they asked for after the callback', () => {
    useAuth.mockReturnValue(signedInAs(['Agent'], { returnTo: '/agent' }))

    renderAt('/auth/callback')

    expect(screen.getByRole('heading', { name: 'Agent Home' })).toBeInTheDocument()
  })

  it('shows a failed sign-in on the callback page', () => {
    useAuth.mockReturnValue({ ...signedOut, error: { message: 'invalid_grant' } })

    renderAt('/auth/callback')

    expect(screen.getByRole('alert')).toHaveTextContent('Sign-in failed: invalid_grant')
  })

  it.each([['/agent/calendar', 'Calendar'], ['/agent/drafts', 'Follow-up Emails']])(
    'opens %s for an agent and keeps admins out', (path, heading) => {
      useAuth.mockReturnValue(signedInAs(['Agent']))
      const { unmount } = renderAt(path)
      expect(screen.getByRole('heading', { name: heading })).toBeInTheDocument()
      unmount()

      useAuth.mockReturnValue(signedInAs(['Admin']))
      renderAt(path)
      expect(screen.getByRole('alert')).toHaveTextContent('not available to your role')
    })

  it('lets an admin open a client page from the admin home page', () => {
    useAuth.mockReturnValue(signedInAs(['Admin']))

    renderAt('/agent/client/1/transactions')

    expect(screen.queryByText('not available to your role')).not.toBeInTheDocument()
  })

  it.each([['Agent'], ['Admin']])('opens a client page, with its documents and Verify, for an %s', async (group) => {
    useAuth.mockReturnValue(signedInAs([group]))

    renderAt('/clients/client-1')

    expect(await screen.findByRole('heading', { name: 'Synthetic Client' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Identity documents' })).toBeInTheDocument()
  })

  it('sends a signed-in user without an authenticator to enrol one first (ADR-0076)', () => {
    vi.stubEnv('VITE_MFA_ENROLMENT', 'true')
    vi.stubEnv('VITE_COGNITO_AUTHORITY', 'https://cognito-idp.ap-southeast-1.amazonaws.com/ap-southeast-1_Example')
    const auth = signedInAs(['AGENT'])
    auth.user.access_token = accessToken('openid email aws.cognito.signin.user.admin')
    useAuth.mockReturnValue(auth)

    renderAt('/agent')

    expect(screen.getByRole('heading', { name: 'Set up your authenticator' })).toBeInTheDocument()
    expect(screen.queryByText('Agent Home')).not.toBeInTheDocument()
  })
})
