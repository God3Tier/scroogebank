import { useAuth } from 'react-oidc-context'
import { Link, Navigate } from 'react-router-dom'

import { landingPathFor } from '../auth/roles.js'

// Cognito sends the browser here after sign-in: it is the app client's
// callback URL. AuthProvider exchanges the code for tokens, sending the PKCE
// verifier with it; this page then moves on, replacing the URL so the code
// does not stay in the browser history.
function SignInCallback() {
  const auth = useAuth()

  if (auth.error) {
    return (
      <div className='login-page'>
        <div className='login-container stack-form'>
          <p className='error-text' role='alert'>Sign-in failed: {auth.error.message}</p>
          <Link to='/login'>Try again</Link>
        </div>
      </div>
    )
  }
  if (auth.isLoading) {
    return <p className='empty-state' role='status'>Signing you in…</p>
  }
  return <Navigate to={auth.isAuthenticated ? landingPathFor(auth.user) : '/login'} replace />
}

export default SignInCallback
