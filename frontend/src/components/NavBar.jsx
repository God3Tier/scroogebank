import { NavLink } from 'react-router-dom';

function NavBar() {
  return (
    <nav className="navbar">
      <NavLink to="/">Home</NavLink>
      <NavLink to="/login">Login</NavLink>
      <NavLink to="/agent">Agent Dashboard</NavLink>
      <NavLink to="/admin">Admin Dashboard</NavLink>
    </nav>
  )
}

export default NavBar;