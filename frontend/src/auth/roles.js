// Roles come from the Cognito groups in the ID token (ADR-0025 decision 1).
// The UI uses them only to choose what to show: every service checks the
// access token's groups itself (ADR-0005).
export const ADMIN = 'ADMIN'
export const AGENT = 'AGENT'

export function groupsOf(user) {
  const groups = user?.profile?.['cognito:groups']
  return Array.isArray(groups) ? groups.map((group) => String(group).toUpperCase()) : []
}

export function inAnyGroup(user, groups) {
  const own = groupsOf(user)
  return groups.some((group) => own.includes(group))
}

export function homeFor(user) {
  const groups = groupsOf(user)
  if (groups.includes(ADMIN)) return '/admin'
  if (groups.includes(AGENT)) return '/agent'
  return '/'
}

// Where to go once signed in: the page the user asked for before signing in,
// if it is a path in this app, otherwise their role's home page.
export function landingPathFor(user, returnTo = user?.state?.returnTo) {
  const local = typeof returnTo === 'string' && returnTo.startsWith('/') && !returnTo.startsWith('//')
  return local && returnTo !== '/login' ? returnTo : homeFor(user)
}
