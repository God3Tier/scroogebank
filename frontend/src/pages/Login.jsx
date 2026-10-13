import { useAuth } from 'react-oidc-context'
import { Navigate, useLocation } from 'react-router-dom'

import { isAuthConfigured } from '../auth/config.js'
import { landingPathFor } from '../auth/roles.js'

// Sign-in goes through Cognito managed login, which asks for the password and
// MFA (ADR-0025 decision 2). This page only starts it.
function Login() {
    const auth = useAuth()
    const location = useLocation()
    const returnTo = location.state?.returnTo ?? null
    const configured = isAuthConfigured()

    if (auth.isAuthenticated) {
        return <Navigate to={landingPathFor(auth.user, returnTo)} replace />
    }

    function handleSignIn(event) {
        event.preventDefault()
        // Cognito returns to /auth/callback, which then opens returnTo.
        auth.signinRedirect({ state: { returnTo } })
    }

    return (
        <div className='login-page'>
        <div className='login-container'>
            <div className='login-header'>
                <div className='login-logo'>
                    <span className='material-symbols-outlined' aria-hidden='true'>account_balance</span>
                </div>
                <h1>Login</h1>
                <p>Scrooge Global staff access</p>
            </div>
            <form className='stack-form' onSubmit={handleSignIn}>
                {!configured && (
                    <p className='error-text' role='alert'>
                        Sign-in is not configured. Set VITE_COGNITO_AUTHORITY, VITE_COGNITO_CLIENT_ID
                        and VITE_COGNITO_DOMAIN in frontend/.env.
                    </p>
                )}
                {auth.error && (
                    <p className='error-text' role='alert'>Sign-in failed: {auth.error.message}</p>
                )}
                <button type='submit' disabled={!configured || Boolean(auth.activeNavigator)}>
                    Sign in
                </button>
            </form>
        </div>
        </div>
    );
}

export default Login;
