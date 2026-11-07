import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useAuth } from 'react-oidc-context'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { accessToken } from '../test/tokens.js'
import { finishTotpEnrolment, startTotpEnrolment } from '../auth/totp.js'
import MfaSetup from './MfaSetup.jsx'

vi.mock('react-oidc-context', () => ({ useAuth: vi.fn() }))
vi.mock('../auth/totp.js', async (importOriginal) => ({
  ...(await importOriginal()),
  startTotpEnrolment: vi.fn(),
  finishTotpEnrolment: vi.fn(),
}))
vi.mock('../auth/signOut.js', () => ({ signOut: vi.fn() }))

function signedIn(scope, extra = {}) {
  return {
    isLoading: false,
    isAuthenticated: true,
    user: { access_token: accessToken(scope), profile: { 'cognito:groups': ['AGENT'], email: 'agent1@crm.com' } },
    ...extra,
  }
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/mfa-setup']}>
      <Routes>
        <Route path="/mfa-setup" element={<MfaSetup />} />
        <Route path="/agent" element={<p>Agent Home</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('MFA enrolment page (ADR-0076)', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_MFA_ENROLMENT', 'true')
    vi.stubEnv('VITE_COGNITO_AUTHORITY', 'https://cognito-idp.ap-southeast-1.amazonaws.com/ap-southeast-1_Example')
  })
  afterEach(() => {
    vi.unstubAllEnvs()
    vi.clearAllMocks()
  })

  it('shows the new key, verifies a code and continues with a refreshed token', async () => {
    startTotpEnrolment.mockResolvedValue('ABCDEFGHIJKL')
    finishTotpEnrolment.mockResolvedValue()
    const signinSilent = vi.fn().mockResolvedValue({ profile: { 'cognito:groups': ['AGENT'] } })
    useAuth.mockReturnValue(signedIn('openid email aws.cognito.signin.user.admin', { signinSilent }))

    renderPage()
    expect(await screen.findByLabelText('Authenticator key')).toHaveTextContent('ABCD EFGH IJKL')
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '123456' } })
    fireEvent.click(screen.getByRole('button', { name: 'Verify' }))

    expect(await screen.findByText('Agent Home')).toBeInTheDocument()
    expect(finishTotpEnrolment).toHaveBeenCalledWith(expect.any(String), '123456')
    expect(signinSilent).toHaveBeenCalled()
    expect(startTotpEnrolment).toHaveBeenCalledTimes(1)
  })

  it('shows why a code was refused and stays on the page', async () => {
    startTotpEnrolment.mockResolvedValue('ABCDEFGHIJKL')
    finishTotpEnrolment.mockRejectedValue(new Error('Invalid code received for user'))
    useAuth.mockReturnValue(signedIn('openid email', { signinSilent: vi.fn() }))

    renderPage()
    await screen.findByLabelText('Authenticator key')
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '000000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Verify' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid code received for user')
  })

  it('asks the user to sign in again when the token cannot be refreshed', async () => {
    startTotpEnrolment.mockResolvedValue('ABCDEFGHIJKL')
    finishTotpEnrolment.mockResolvedValue()
    useAuth.mockReturnValue(signedIn('openid email', { signinSilent: vi.fn().mockRejectedValue(new Error('no refresh token')) }))

    renderPage()
    await screen.findByLabelText('Authenticator key')
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '123456' } })
    fireEvent.click(screen.getByRole('button', { name: 'Verify' }))

    expect(await screen.findByRole('button', { name: 'Sign in again' })).toBeInTheDocument()
  })

  it('sends a user who already has API access home', async () => {
    useAuth.mockReturnValue(signedIn('openid email crm/api'))

    renderPage()

    await waitFor(() => expect(screen.getByText('Agent Home')).toBeInTheDocument())
    expect(startTotpEnrolment).not.toHaveBeenCalled()
  })
})
