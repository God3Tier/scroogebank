import { NavLink } from 'react-router-dom';

function NavBar() {
  return (
    <nav className="navbar">
      <NavLink to="/">Home</NavLink>
      <NavLink to="/login">Login</NavLink>
      <NavLink to="/agent-dashboard">Agent Dashboard</NavLink>
      <NavLink to="/admin-dashboard">Admin Dashboard</NavLink>
    </nav>
  )
}

export default NavBar;