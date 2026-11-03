import { describe, expect, it, vi } from 'vitest'

import { finishTotpEnrolment, otpauthUri, startTotpEnrolment } from './totp.js'

const settings = { authority: 'https://cognito-idp.ap-southeast-1.amazonaws.com/ap-southeast-1_Example' }

function respond(...bodies) {
  const fetchImpl = vi.fn()
  for (const [status, body] of bodies) {
    fetchImpl.mockResolvedValueOnce({ ok: status < 400, status, json: async () => body })
  }
  return fetchImpl
}

describe('TOTP enrolment (ADR-0076)', () => {
  it("asks the pool's endpoint for a new secret with the user's access token", async () => {
    const fetchImpl = respond([200, { SecretCode: 'SECRETKEY234' }])

    await expect(startTotpEnrolment('token-1', { settings, fetchImpl })).resolves.toBe('SECRETKEY234')

    const [url, request] = fetchImpl.mock.calls[0]
    expect(url).toBe('https://cognito-idp.ap-southeast-1.amazonaws.com/')
    expect(request.headers['X-Amz-Target']).toBe('AWSCognitoIdentityProviderService.AssociateSoftwareToken')
    expect(JSON.parse(request.body)).toEqual({ AccessToken: 'token-1' })
  })

  it('verifies the code, then makes TOTP the preferred MFA', async () => {
    const fetchImpl = respond([200, { Status: 'SUCCESS' }], [200, {}])

    await finishTotpEnrolment('token-1', '123456', { settings, fetchImpl })

    const targets = fetchImpl.mock.calls.map(([, request]) => request.headers['X-Amz-Target'])
    expect(targets).toEqual([
      'AWSCognitoIdentityProviderService.VerifySoftwareToken',
      'AWSCognitoIdentityProviderService.SetUserMFAPreference',
    ])
    expect(JSON.parse(fetchImpl.mock.calls[0][1].body)).toMatchObject({ UserCode: '123456' })
    expect(JSON.parse(fetchImpl.mock.calls[1][1].body).SoftwareTokenMfaSettings).toEqual({ Enabled: true, PreferredMfa: true })
  })

  it("does not turn MFA on when Cognito rejects the code, and passes on Cognito's message", async () => {
    const fetchImpl = respond([400, { __type: 'CodeMismatchException', message: 'Invalid code received for user' }])

    await expect(finishTotpEnrolment('token-1', '000000', { settings, fetchImpl })).rejects.toThrow('Invalid code received for user')
    expect(fetchImpl).toHaveBeenCalledTimes(1)
  })

  it('builds the link an authenticator app opens', () => {
    expect(otpauthUri('ABC234', 'agent1@crm.com')).toBe('otpauth://totp/Scrooge%20CRM:agent1%40crm.com?secret=ABC234&issuer=Scrooge%20CRM')
  })
})
