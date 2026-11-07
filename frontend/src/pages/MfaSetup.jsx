import { useEffect, useRef, useState } from 'react'
import { useAuth } from 'react-oidc-context'
import { Navigate, useNavigate } from 'react-router-dom'

import { needsMfaEnrolment } from '../auth/apiAccess.js'
import { homeFor } from '../auth/roles.js'
import { signOut } from '../auth/signOut.js'
import { finishTotpEnrolment, otpauthUri, startTotpEnrolment } from '../auth/totp.js'

// A signed-in user with no authenticator lands here (ADR-0076). The pool's
// MFA is optional, so managed login does not enrol one; the API refuses the
// user's token until they have. Once enrolled, a refreshed token carries the
// API scope, and every later sign-in asks for a code.
function MfaSetup() {
  const auth = useAuth()
  const navigate = useNavigate()
  const [secret, setSecret] = useState(null)
  const [code, setCode] = useState('')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const [signInAgain, setSignInAgain] = useState(false)
  const started = useRef(null)

  const token = auth.user?.access_token
  const pending = auth.isAuthenticated && needsMfaEnrolment(auth.user)

  useEffect(() => {
    // Once per token: a second association would replace the first secret.
    if (!pending || started.current === token) return
    started.current = token
    startTotpEnrolment(token).then(setSecret, (e) => setError(e.message))
  }, [pending, token])

  if (auth.isLoading) {
    return <p className='empty-state' role='status'>Checking your sign-in…</p>
  }
  if (!auth.isAuthenticated) {
    return <Navigate to='/login' replace />
  }
  if (!pending && !signInAgain) {
    return <Navigate to={homeFor(auth.user)} replace />
  }

  async function handleVerify(event) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await finishTotpEnrolment(token, code.trim())
    } catch (e) {
      setError(e.message)
      setBusy(false)
      return
    }
    try {
      // The refreshed token is issued after enrolment, so it carries the scope.
      const user = await auth.signinSilent()
      navigate(homeFor(user), { replace: true })
    } catch {
      setSignInAgain(true)
      setBusy(false)
    }
  }

  return (
    <div className='login-page'>
      <div className='login-container'>
        <div className='login-header'>
          <div className='login-logo'>
            <span className='material-symbols-outlined' aria-hidden='true'>shield_lock</span>
          </div>
          <h1>Set up your authenticator</h1>
          <p>Multi-factor authentication is required to use the CRM.</p>
        </div>
        {signInAgain ? (
          <div className='stack-form'>
            <p role='status'>Your authenticator is set up. Sign in again to continue.</p>
            <button type='button' onClick={() => signOut()}>Sign in again</button>
          </div>
        ) : (
          <form className='stack-form' onSubmit={handleVerify}>
            <p>1. In an authenticator app, add an account with this key, or open the link on your phone.</p>
            {secret ? (
              <>
                <code className='mfa-key' aria-label='Authenticator key'>{secret.match(/.{1,4}/g).join(' ')}</code>
                <a href={otpauthUri(secret, auth.user?.profile?.email ?? 'CRM user')}>Open in authenticator app</a>
              </>
            ) : (
              !error && <p className='empty-state' role='status'>Preparing your key…</p>
            )}
            <label>
              2. Enter the 6-digit code the app shows
              <input
                inputMode='numeric'
                autoComplete='one-time-code'
                pattern='[0-9]{6}'
                maxLength={6}
                value={code}
                onChange={(e) => setCode(e.target.value)}
                required
              />
            </label>
            {error && <p className='error-text' role='alert'>{error}</p>}
            <button type='submit' disabled={!secret || busy}>Verify</button>
            <button type='button' className='mfa-sign-out' onClick={() => signOut()}>Sign out</button>
          </form>
        )}
      </div>
    </div>
  )
}

export default MfaSetup
