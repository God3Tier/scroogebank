import { useState } from 'react'
import { useAuth } from 'react-oidc-context'
import { NavLink } from 'react-router-dom';

import { ADMIN, AGENT, inAnyGroup } from '../auth/roles.js'
import { signOut } from '../auth/signOut.js'

// Links are shown for the signed-in user's role only.
function NavBar() {
  const auth = useAuth()
  const [signingOut, setSigningOut] = useState(false)
  const signedIn = auth.isAuthenticated

  function handleSignOut() {
    setSigningOut(true)
    signOut()
  }
  const isAdmin = signedIn && inAnyGroup(auth.user, [ADMIN])
  const isAgent = signedIn && inAnyGroup(auth.user, [AGENT])

  return (
    <aside className="sidebar">
      <div className="sidebar-brand">
        <div className="sidebar-brand-row">
          <div className="sidebar-logo">S</div>
          <span className="sidebar-title">Scrooge Global</span>
        </div>
        <p className="sidebar-subtitle">Staff Portal</p>
      </div>
      <nav className="navbar sidebar-nav">
        <NavLink to="/">
          <span className="material-symbols-outlined" aria-hidden="true">home</span>
          Home
        </NavLink>
        {!signedIn && (
          <NavLink to="/login">
            <span className="material-symbols-outlined" aria-hidden="true">login</span>
            Login
          </NavLink>
        )}
        {isAgent && (
          <>
            <NavLink to="/agent">
              <span className="material-symbols-outlined" aria-hidden="true">support_agent</span>
              Agent Home
            </NavLink>
            <NavLink to="/agent/client-health">
              <span className="material-symbols-outlined" aria-hidden="true">monitor_heart</span>
              Client Health
            </NavLink>
          </>
        )}
        {isAdmin && (
          <>
            <NavLink to="/admin">
              <span className="material-symbols-outlined" aria-hidden="true">admin_panel_settings</span>
              Admin Home
            </NavLink>
            <NavLink to="/insights/admin">
              <span className="material-symbols-outlined" aria-hidden="true">insights</span>
              Admin Dashboard
            </NavLink>
          </>
        )}
        {isAgent && (
          <NavLink to="/insights/agent">
            <span className="material-symbols-outlined" aria-hidden="true">monitoring</span>
            Agent Dashboard
          </NavLink>
        )}
      </nav>
      {signedIn && (
        <div className="sidebar-session">
          <p className="sidebar-user">{auth.user?.profile?.email}</p>
          <button type="button" className="sidebar-button" onClick={handleSignOut} disabled={signingOut}>
            <span className="material-symbols-outlined" aria-hidden="true">logout</span>
            {signingOut ? 'Signing out…' : 'Sign out'}
          </button>
        </div>
      )}
    </aside>
  )
}

export default NavBar;
