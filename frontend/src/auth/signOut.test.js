import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { signOut } from './signOut.js'

vi.mock('./session.js', () => ({ userManager: {} }))

const settings = {
  clientId: 'example-client-id',
  domain: 'https://example.auth.ap-southeast-1.amazoncognito.com',
  logoutUri: 'http://localhost:5173/',
}
const logoutUrl = `${settings.domain}/logout?client_id=example-client-id&logout_uri=http%3A%2F%2Flocalhost%3A5173%2F`
const signedIn = { access_token: 'synthetic-access-token', refresh_token: 'synthetic-refresh-token' }

// Runs signOut with fakes that record every step, in order.
async function runSignOut({ user = signedIn, logout = 204, revoke = 200 } = {}) {
  const steps = []
  const respond = (outcome) => (outcome instanceof Error
    ? Promise.reject(outcome)
    : Promise.resolve(new Response(null, { status: outcome })))
  const fetchImpl = vi.fn((url) => {
    steps.push(url)
    return respond(url.endsWith('/oauth2/revoke') ? revoke : logout)
  })
  const userManager = {
    getUser: vi.fn().mockResolvedValue(user),
    removeUser: vi.fn(async () => { steps.push('removeUser') }),
  }
  const navigate = vi.fn((url) => { steps.push(url) })

  await signOut({ userManager, settings, fetchImpl, navigate })
  return { steps, fetchImpl }
}

describe('signOut', () => {
  beforeEach(() => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('reports the logout, then revokes the refresh token, then ends the Cognito session', async () => {
    const { steps, fetchImpl } = await runSignOut()

    expect(steps).toEqual(['/api/users/me/logout', `${settings.domain}/oauth2/revoke`, 'removeUser', logoutUrl])

    const [, logoutRequest] = fetchImpl.mock.calls[0]
    expect(logoutRequest.method).toBe('POST')
    expect(logoutRequest.headers).toEqual({ Authorization: 'Bearer synthetic-access-token' })

    const [, revokeRequest] = fetchImpl.mock.calls[1]
    expect(revokeRequest.method).toBe('POST')
    expect(revokeRequest.headers).toEqual({ 'Content-Type': 'application/x-www-form-urlencoded' })
    expect(revokeRequest.body.toString()).toBe('token=synthetic-refresh-token&client_id=example-client-id')
  })

  it.each([
    ['refuses it', 500],
    ['cannot be reached', new TypeError('Failed to fetch')],
  ])('still signs out when user-service %s', async (_, logout) => {
    const { steps } = await runSignOut({ logout })

    expect(steps).toEqual(['/api/users/me/logout', `${settings.domain}/oauth2/revoke`, 'removeUser', logoutUrl])
  })

  it('still signs out when the revocation fails', async () => {
    const { steps } = await runSignOut({ revoke: new TypeError('Failed to fetch') })

    expect(steps.slice(-2)).toEqual(['removeUser', logoutUrl])
  })

  it('revokes nothing when there is no refresh token', async () => {
    const { steps } = await runSignOut({ user: { access_token: 'synthetic-access-token' } })

    expect(steps).toEqual(['/api/users/me/logout', 'removeUser', logoutUrl])
  })

  it('still ends the Cognito session when the tab has no tokens left', async () => {
    const { steps } = await runSignOut({ user: null })

    expect(steps).toEqual(['removeUser', logoutUrl])
  })
})
