import { useState } from 'react'
import { useAuth } from 'react-oidc-context'
import { Link, NavLink, useLocation } from 'react-router-dom';

import { ADMIN, AGENT, inAnyGroup } from '../auth/roles.js'
import { signOut } from '../auth/signOut.js'

// Served from public/ at the site root. A plain path, not an import, so tests
// on Windows can load this module.
const logo = '/scrooge-global-bank-logo.svg'


// The agent pages under /agent with a link of their own.
const AGENT_PAGES = [
  { to: '/agent/client-health', icon: 'monitor_heart', label: 'Client Health' },
  { to: '/agent/calendar', icon: 'calendar_month', label: 'Calendar' },
  { to: '/agent/drafts', icon: 'drafts', label: 'Follow-up Emails' },
  { to: '/agent/alerts', icon: 'notifications_active', label: 'Event Driven Alerts' },
]

// A home link is current on its own page and on the pages under it without a
// link of their own, such as alerts; never alongside one of those links.
function HomeLink({ to, others, children }) {
  const { pathname } = useLocation()
  const under = (path) => pathname === path || pathname.startsWith(`${path}/`)
  const current = under(to) && !others.some(under)
  return (
    <Link to={to} className={current ? 'active' : undefined} aria-current={current ? 'page' : undefined}>
      {children}
    </Link>
  )
}

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
          <img src={logo} alt="Scrooge Global Bank Logo" className="sidebar-logo" />
          <span className="sidebar-title">Scrooge Global</span>
        </div>
        <p className="sidebar-subtitle">Staff Portal</p>
      </div>
      <nav className="navbar sidebar-nav">
        {!auth.isLoading && !signedIn &&(
          <NavLink to="/">
            <span className="material-symbols-outlined" aria-hidden="true">home</span>
            Home
          </NavLink>
        )}
        {!signedIn && (
          <NavLink to="/login">
            <span className="material-symbols-outlined" aria-hidden="true">login</span>
            Login
          </NavLink>
        )}
        {isAgent && (
          <>
            <HomeLink to="/agent" others={AGENT_PAGES.map((page) => page.to)}>
              <span className="material-symbols-outlined" aria-hidden="true">support_agent</span>
              Agent Home
            </HomeLink>
            {AGENT_PAGES.map((page) => (
              <NavLink key={page.to} to={page.to}>
                <span className="material-symbols-outlined" aria-hidden="true">{page.icon}</span>
                {page.label}
              </NavLink>
            ))}
          </>
        )}
        {isAdmin && (
          <>
            <HomeLink to="/admin" others={['/admin/client-health', '/admin/alerts', '/admin/audit-logs']}>
              <span className="material-symbols-outlined" aria-hidden="true">admin_panel_settings</span>
              Admin Home
            </HomeLink>
            <NavLink to="/admin/client-health">
              <span className="material-symbols-outlined" aria-hidden="true">monitor_heart</span>
              Client Health
            </NavLink>
            <NavLink to="/admin/alerts">
              <span className="material-symbols-outlined" aria-hidden="true">notifications_active</span>
              Event Driven Alerts
            </NavLink>
            <NavLink to="/insights/admin">
              <span className="material-symbols-outlined" aria-hidden="true">insights</span>
              Admin Dashboard
            </NavLink>
            <NavLink to="/admin/audit-logs">
              <span className="material-symbols-outlined" aria-hidden="true">history</span>
              Audit Activity
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
