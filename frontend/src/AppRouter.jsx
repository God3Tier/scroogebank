import { BrowserRouter as Router, Routes, Route } from 'react-router-dom'
import Home from './pages/Home.jsx'
import Login from './pages/Login.jsx'
import ClientDashboard from './pages/ClientDashboard.jsx'
import AgentDashboard from './pages/AgentDashboard.jsx'
import AdminDashboard from './pages/AdminDashboard.jsx'

function AppRouter() {
  return (
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="/login" element={<Login />} />
      <Route path="/client-dashboard" element={<ClientDashboard />} />
      <Route path="/agent-dashboard" element={<AgentDashboard />} />
      <Route path="/admin-dashboard" element={<AdminDashboard />} />
    </Routes>
  )
}

export default AppRouter;