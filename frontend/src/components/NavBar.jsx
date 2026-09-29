import { BellRing, Landmark, LogIn, ShieldCheck, UserRound } from 'lucide-react'
import { NavLink } from 'react-router-dom'

function NavBar() {
  return (
    <header className="app-header">
      <a className="skip-link" href="#main-content">Skip to main content</a>
      <nav className="navbar" aria-label="Primary navigation">
        <NavLink className="brand" to="/" aria-label="Scrooge CRM home">
          <span className="brand__mark"><Landmark aria-hidden="true" /></span>
          <span><strong>Scrooge</strong><small>Global Bank CRM</small></span>
        </NavLink>
        <div className="nav-links">
          <NavLink to="/agent"><UserRound aria-hidden="true" /> Agent</NavLink>
          <NavLink to="/agent/alerts"><BellRing aria-hidden="true" /> Event alerts</NavLink>
          <NavLink to="/admin"><ShieldCheck aria-hidden="true" /> Admin</NavLink>
        </div>
        <NavLink className="sign-in-link" to="/login"><LogIn aria-hidden="true" /> Sign in</NavLink>
      </nav>
      <div className="environment-strip"><span>Synthetic data environment</span><span>Human decision required</span></div>
    </header>
  )
}

export default NavBar
