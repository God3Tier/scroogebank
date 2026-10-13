import { InMemoryWebStorage, OidcClient, WebStorageStateStore } from 'oidc-client-ts'
import { describe, expect, it } from 'vitest'

import { cognitoSettings, isAuthConfigured, oidcSettings } from './config.js'

const env = {
  VITE_COGNITO_AUTHORITY: 'https://cognito-idp.ap-southeast-1.amazonaws.com/ap-southeast-1_Example',
  VITE_COGNITO_CLIENT_ID: 'example-client-id',
  VITE_COGNITO_DOMAIN: 'https://example.auth.ap-southeast-1.amazoncognito.com/',
}
const origin = 'http://localhost:5173'

// The two endpoints a sign-in needs from the discovery document, given here so
// the test makes no network call.
const metadata = {
  issuer: env.VITE_COGNITO_AUTHORITY,
  authorization_endpoint: 'https://example.auth.ap-southeast-1.amazoncognito.com/oauth2/authorize',
  token_endpoint: 'https://example.auth.ap-southeast-1.amazoncognito.com/oauth2/token',
}

async function sha256Base64Url(text) {
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text)))
  return btoa(String.fromCharCode(...digest)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

describe('Cognito sign-in settings', () => {
  it('sends a PKCE S256 challenge with every sign-in (ADR-0025 decision 2)', async () => {
    const client = new OidcClient({
      ...oidcSettings(cognitoSettings(env, origin)),
      metadata,
      stateStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
    })

    const request = await client.createSigninRequest({})
    const url = new URL(request.url)
    const params = url.searchParams

    expect(`${url.origin}${url.pathname}`).toBe(metadata.authorization_endpoint)
    expect(params.get('response_type')).toBe('code')
    expect(params.get('client_id')).toBe('example-client-id')
    expect(params.get('redirect_uri')).toBe('http://localhost:5173/auth/callback')
    expect(params.get('scope')).toBe('openid email')
    expect(params.get('code_challenge_method')).toBe('S256')
    // The challenge is the SHA-256 of the verifier kept for the token request,
    // so a stolen code cannot be exchanged without that verifier.
    expect(params.get('code_challenge')).toMatch(/^[A-Za-z0-9_-]{43}$/)
    expect(params.get('code_challenge')).toBe(await sha256Base64Url(request.state.code_verifier))
    expect(params.has('client_secret')).toBe(false)
  })

  it('needs the issuer, the app client and the domain', () => {
    expect(isAuthConfigured(cognitoSettings(env, origin))).toBe(true)
    expect(isAuthConfigured(cognitoSettings({ ...env, VITE_COGNITO_CLIENT_ID: '' }, origin))).toBe(false)
    expect(isAuthConfigured(cognitoSettings({ ...env, VITE_COGNITO_DOMAIN: '' }, origin))).toBe(false)
  })

  it('builds the callback and sign-out URLs from the page origin', () => {
    expect(cognitoSettings(env, origin)).toMatchObject({
      domain: 'https://example.auth.ap-southeast-1.amazoncognito.com',
      redirectUri: 'http://localhost:5173/auth/callback',
      logoutUri: 'http://localhost:5173/',
    })
  })
})
