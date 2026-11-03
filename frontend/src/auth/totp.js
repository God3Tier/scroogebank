import { cognitoSettings } from './config.js'

// TOTP enrolment through Cognito's user API, authorised by the user's own
// access token (ADR-0076). The pool's endpoint is the issuer's origin, which
// the Content-Security-Policy already allows.
async function cognitoCall(action, body, { settings = cognitoSettings(), fetchImpl = fetch } = {}) {
  const response = await fetchImpl(`${new URL(settings.authority).origin}/`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-amz-json-1.1',
      'X-Amz-Target': `AWSCognitoIdentityProviderService.${action}`,
    },
    body: JSON.stringify(body),
  })
  const data = await response.json().catch(() => ({}))
  if (!response.ok) {
    throw Object.assign(new Error(data.message || `Cognito could not complete ${action} (${response.status})`), {
      status: response.status,
      type: data.__type,
    })
  }
  return data
}

// Starts enrolment and returns the new authenticator's secret key.
export async function startTotpEnrolment(accessToken, options) {
  const { SecretCode } = await cognitoCall('AssociateSoftwareToken', { AccessToken: accessToken }, options)
  return SecretCode
}

// Checks a code from the authenticator, then makes TOTP the user's MFA, so
// every later sign-in asks for a code.
export async function finishTotpEnrolment(accessToken, code, options) {
  const verified = await cognitoCall('VerifySoftwareToken',
    { AccessToken: accessToken, UserCode: code, FriendlyDeviceName: 'Scrooge CRM' }, options)
  if (verified.Status !== 'SUCCESS') {
    throw new Error('That code was not accepted. Check the time on your device and try a new code.')
  }
  await cognitoCall('SetUserMFAPreference',
    { AccessToken: accessToken, SoftwareTokenMfaSettings: { Enabled: true, PreferredMfa: true } }, options)
}

// The link an authenticator app opens to add the account.
export function otpauthUri(secret, account, issuer = 'Scrooge CRM') {
  const label = `${encodeURIComponent(issuer)}:${encodeURIComponent(account)}`
  return `otpauth://totp/${label}?secret=${secret}&issuer=${encodeURIComponent(issuer)}`
}
