import { NavLink } from 'react-router-dom';

function NavBar() {
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
        <NavLink to="/login">
          <span className="material-symbols-outlined" aria-hidden="true">login</span>
          Login
        </NavLink>
        <NavLink to="/agent">
          <span className="material-symbols-outlined" aria-hidden="true">support_agent</span>
          Agent Dashboard
        </NavLink>
        <NavLink to="/admin">
          <span className="material-symbols-outlined" aria-hidden="true">admin_panel_settings</span>
          Admin Dashboard
        </NavLink>
        <NavLink to="/insights/admin">
          <span className="material-symbols-outlined" aria-hidden="true">insights</span>
          Admin Insights
        </NavLink>
        <NavLink to="/insights/agent">
          <span className="material-symbols-outlined" aria-hidden="true">monitoring</span>
          Agent Insights
        </NavLink>
      </nav>
    </aside>
  )
}

export default NavBar;
