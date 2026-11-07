import { cognitoSettings } from './config.js'
import { userManager as sharedUserManager } from './session.js'

const API_URL = (import.meta.env.VITE_API_URL || '').replace(/\/$/, '')

// A slow service must not leave the user stuck signing out.
const STEP_TIMEOUT_MS = 5000

// Signs the user out (ADR-0025 decision 5). Cognito has no sign-out trigger,
// so a logout reaches the audit log only because user-service is told, and it
// is told first, while the access token is certainly valid. Each step runs
// even if the one before fails: an audit failure must never stop a sign-out.
export async function signOut({
  userManager = sharedUserManager,
  settings = cognitoSettings(),
  fetchImpl = fetch,
  navigate = (url) => window.location.assign(url),
} = {}) {
  let user = null
  try {
    user = await userManager.getUser()
  } catch (error) {
    console.warn('Could not read the session while signing out', error)
  }

  // 1. Report the sign-out; user-service audits it as LOGOUT.
  if (user?.access_token) {
    try {
      const response = await fetchImpl(`${API_URL}/api/users/me/logout`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${user.access_token}` },
        signal: AbortSignal.timeout(STEP_TIMEOUT_MS),
      })
      if (!response.ok) console.warn(`The logout was not recorded (${response.status})`)
    } catch (error) {
      console.warn('The logout was not recorded', error)
    }
  }

  // 2. Revoke the refresh token, so it cannot get new tokens. The app client
  // has no secret, so the browser sends only its client ID.
  if (user?.refresh_token) {
    try {
      const response = await fetchImpl(`${settings.domain}/oauth2/revoke`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ token: user.refresh_token, client_id: settings.clientId }),
        signal: AbortSignal.timeout(STEP_TIMEOUT_MS),
      })
      if (!response.ok) console.warn(`The refresh token was not revoked (${response.status})`)
    } catch (error) {
      console.warn('The refresh token was not revoked', error)
    }
  }

  // 3. Drop the tokens, then end the managed login session. Without it, the
  // next sign-in in this browser would skip the password and MFA.
  try {
    await userManager.removeUser()
  } catch (error) {
    console.warn('Could not clear the session', error)
  }
  if (!settings.domain) {
    navigate(settings.logoutUri)
    return
  }
  const query = new URLSearchParams({ client_id: settings.clientId, logout_uri: settings.logoutUri })
  navigate(`${settings.domain}/logout?${query}`)
}
