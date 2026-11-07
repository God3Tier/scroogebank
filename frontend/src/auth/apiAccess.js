import { cognitoSettings } from './config.js'

// API Gateway lets an access token through only with this scope, which the
// token-scope trigger adds for a user with a TOTP authenticator enrolled or
// exempt from MFA (ADR-0076).
export const API_SCOPE = 'crm/api'

// The scopes in an access token. The app only reads them to choose a page:
// API Gateway checks the signature and the scope.
export function scopesOf(accessToken) {
  try {
    const payload = accessToken.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    const scope = JSON.parse(atob(payload)).scope
    return typeof scope === 'string' ? scope.split(' ') : []
  } catch {
    return []
  }
}

// True when the signed-in user must enrol an authenticator before using the
// app. Only a pool with optional MFA leaves that to the app.
export function needsMfaEnrolment(user, settings = cognitoSettings()) {
  return settings.mfaEnrolment && Boolean(user?.access_token) && !scopesOf(user.access_token).includes(API_SCOPE)
}
