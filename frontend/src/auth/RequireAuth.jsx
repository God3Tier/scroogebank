import { useAuth } from 'react-oidc-context'
import { Navigate, useLocation } from 'react-router-dom'

import { inAnyGroup } from './roles.js'

// Shows its page only to a signed-in user and, when groups are given, only to
// a member of one of them. Anyone else is sent to sign in, or told the page is
// not for their role.
function RequireAuth({ groups, children }) {
  const auth = useAuth()
  const location = useLocation()

  if (auth.isLoading) {
    return <p className="empty-state" role="status">Checking your sign-in…</p>
  }
  if (!auth.isAuthenticated) {
    return <Navigate to="/login" replace state={{ returnTo: location.pathname + location.search }} />
  }
  if (groups && !inAnyGroup(auth.user, groups)) {
    return (
      <div className="page">
        <p className="empty-state" role="alert">This page is not available to your role.</p>
      </div>
    )
  }
  return children
}

export default RequireAuth
