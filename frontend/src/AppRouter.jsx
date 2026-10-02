import { Routes, Route } from 'react-router-dom'
import Home from './pages/Home.jsx'
import Login from './pages/Login.jsx'
import AgentDashboard from './pages/AgentDashboard.jsx'
import AdminDashboard from './pages/AdminDashboard.jsx'
import ClientTransactions from './pages/ClientTransactions.jsx'
import ClientHealthMonitor from './pages/ClientHealthMonitor.jsx'
import EventAlerts from './pages/EventAlerts.jsx'
import AdminBusinessDashboard from './pages/AdminBusinessDashboard.jsx'
import AgentBusinessDashboard from './pages/AgentBusinessDashboard.jsx'

function AppRouter() {
  return (
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="/login" element={<Login />} />
      <Route path="/agent" element={<AgentDashboard />} />
      <Route path="/agent/alerts" element={<EventAlerts />} />
      <Route path="/admin" element={<AdminDashboard />} />
      <Route path="/agent/client/:clientId/transactions" element={<ClientTransactions />} />
      <Route path="/agent/client-health" element={<ClientHealthMonitor />} />
      <Route path="/admin/alerts" element={<EventAlerts readOnly />} />
      <Route path="/insights/admin" element={<AdminBusinessDashboard />} />
      <Route path="/insights/agent" element={<AgentBusinessDashboard />} />
    </Routes>
  )
}

export default AppRouter;
