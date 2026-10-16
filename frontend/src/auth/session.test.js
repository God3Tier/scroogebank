import { describe, expect, it } from 'vitest'

import { authorizationHeader, userManager } from './session.js'

// Tests run with no VITE_COGNITO_* settings, as a checkout without .env does.
// The app must still load, so the Login page can say sign-in is not configured.
describe('session without Cognito settings', () => {
  it('has no user and sends no Authorization header', async () => {
    expect(await userManager.getUser()).toBeNull()
    expect(await authorizationHeader()).toEqual({})
  })
})
