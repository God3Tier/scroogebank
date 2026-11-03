import { describe, expect, it } from 'vitest'

import { accessToken } from '../test/tokens.js'
import { needsMfaEnrolment, scopesOf } from './apiAccess.js'

const enrolling = { mfaEnrolment: true }

describe('API access (ADR-0076)', () => {
  it('reads the scopes from an access token', () => {
    expect(scopesOf(accessToken('openid email crm/api'))).toEqual(['openid', 'email', 'crm/api'])
    expect(scopesOf('not a token')).toEqual([])
  })

  it('sends a user whose token lacks crm/api to enrol an authenticator', () => {
    expect(needsMfaEnrolment({ access_token: accessToken('openid email aws.cognito.signin.user.admin') }, enrolling)).toBe(true)
    expect(needsMfaEnrolment({ access_token: accessToken('openid email crm/api') }, enrolling)).toBe(false)
  })

  it('leaves enrolment to managed login when the pool requires MFA', () => {
    expect(needsMfaEnrolment({ access_token: accessToken('openid email') }, { mfaEnrolment: false })).toBe(false)
  })
})
