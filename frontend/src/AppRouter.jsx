import { Routes, Route } from 'react-router-dom'
import Home from './pages/Home.jsx'
import Login from './pages/Login.jsx'
import AgentDashboard from './pages/AgentDashboard.jsx'
import AdminDashboard from './pages/AdminDashboard.jsx'
import ClientTransactions from './pages/ClientTransactions.jsx'
import ClientHealthMonitor from './pages/ClientHealthMonitor.jsx'

function AppRouter() {
  return (
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="/login" element={<Login />} />
      <Route path="/agent" element={<AgentDashboard />} />
      <Route path="/admin" element={<AdminDashboard />} />
      <Route path="/agent/client/:clientId/transactions" element={<ClientTransactions />} />
      <Route path="/agent/client-health" element={<ClientHealthMonitor />} />
    </Routes>
  )
}

export default AppRouter;
