// An unsigned access token with the given scope claim, for tests: the app
// only reads the scopes, and API Gateway checks the signature.
export function accessToken(scope) {
  const part = (value) => btoa(JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  return `${part({ alg: 'none' })}.${part({ scope })}.signature`
}
