import { UserManager } from 'oidc-client-ts'

import { oidcSettings } from './config.js'

// The app's one UserManager, shared by AuthProvider and the API modules. It
// keeps the signed-in user, tokens included, in the tab's sessionStorage (the
// library's default), so a session lasts only as long as the tab.
export const userManager = new UserManager(oidcSettings())

// The Authorization header for API calls, or none when signed out.
export async function authorizationHeader() {
  const user = await userManager.getUser()
  return user && !user.expired ? { Authorization: `Bearer ${user.access_token}` } : {}
}
