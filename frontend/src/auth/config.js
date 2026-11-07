// Cognito sign-in settings (ADR-0025 decision 2), read from the VITE_COGNITO_*
// variables in .env. None of them is a secret: the app client has no secret.
export function cognitoSettings(env = import.meta.env, origin = window.location.origin) {
  return {
    // The user pool's issuer URL, https://cognito-idp.<region>.amazonaws.com/<pool-id>
    authority: env.VITE_COGNITO_AUTHORITY || '',
    clientId: env.VITE_COGNITO_CLIENT_ID || '',
    // The user pool domain, https://<prefix>.auth.<region>.amazoncognito.com,
    // which serves /oauth2/revoke and /logout.
    domain: (env.VITE_COGNITO_DOMAIN || '').replace(/\/$/, ''),
    // Both must be listed on the app client: the callback URL and the sign-out URL.
    redirectUri: `${origin}/auth/callback`,
    logoutUri: `${origin}/`,
    // Set when the pool's MFA is optional and the token-scope trigger enforces
    // it (ADR-0076): the app then enrols a TOTP authenticator itself.
    mfaEnrolment: env.VITE_MFA_ENROLMENT === 'true',
  }
}

export function isAuthConfigured(settings = cognitoSettings()) {
  return Boolean(settings.authority && settings.clientId && settings.domain)
}

// Settings for oidc-client-ts. With response_type 'code' it adds PKCE (S256)
// to every sign-in. Cognito accepts a sign-in without PKCE, so nothing here may
// set disablePKCE.
export function oidcSettings(settings = cognitoSettings()) {
  return {
    authority: settings.authority,
    client_id: settings.clientId,
    redirect_uri: settings.redirectUri,
    response_type: 'code',
    // aws.cognito.signin.user.admin lets the access token enrol TOTP with
    // Cognito's own API, which only a pool with optional MFA needs.
    scope: settings.mfaEnrolment ? 'openid email aws.cognito.signin.user.admin' : 'openid email',
  }
}
