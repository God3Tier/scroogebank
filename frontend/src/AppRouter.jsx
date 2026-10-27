import { Routes, Route } from 'react-router-dom'
import RequireAuth from './auth/RequireAuth.jsx'
import { ADMIN, AGENT } from './auth/roles.js'
import Home from './pages/Home.jsx'
import Login from './pages/Login.jsx'
import SignInCallback from './pages/SignInCallback.jsx'
import AgentDashboard from './pages/AgentDashboard.jsx'
import AdminDashboard from './pages/AdminDashboard.jsx'
import ClientTransactions from './pages/ClientTransactions.jsx'
import ClientProfile from './pages/ClientProfile.jsx'
import ClientHealthMonitor from './pages/ClientHealthMonitor.jsx'
import EventAlerts from './pages/EventAlerts.jsx'
import AdminBusinessDashboard from './pages/AdminBusinessDashboard.jsx'
import AgentBusinessDashboard from './pages/AgentBusinessDashboard.jsx'

// Admins open client pages from their dashboard's action items, and may read
// any client's transactions, so that page is open to both roles. The client
// page is too: client-service lets an admin act on any client and an agent
// only on their own (ADR-0041).
function AppRouter() {
  return (
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="/login" element={<Login />} />
      <Route path="/auth/callback" element={<SignInCallback />} />
      <Route path="/agent" element={<RequireAuth groups={[AGENT]}><AgentDashboard /></RequireAuth>} />
      <Route path="/agent/alerts" element={<RequireAuth groups={[AGENT]}><EventAlerts /></RequireAuth>} />
      <Route path="/admin" element={<RequireAuth groups={[ADMIN]}><AdminDashboard /></RequireAuth>} />
      <Route path="/agent/client/:clientId/transactions" element={<RequireAuth groups={[ADMIN, AGENT]}><ClientTransactions /></RequireAuth>} />
      <Route path="/clients/:clientId" element={<RequireAuth groups={[ADMIN, AGENT]}><ClientProfile /></RequireAuth>} />
      <Route path="/agent/client-health" element={<RequireAuth groups={[AGENT]}><ClientHealthMonitor /></RequireAuth>} />
      <Route path="/admin/client-health" element={<RequireAuth groups={[ADMIN]}><ClientHealthMonitor /></RequireAuth>} />
      <Route path="/admin/alerts" element={<RequireAuth groups={[ADMIN]}><EventAlerts readOnly /></RequireAuth>} />
      <Route path="/insights/admin" element={<RequireAuth groups={[ADMIN]}><AdminBusinessDashboard /></RequireAuth>} />
      <Route path="/insights/agent" element={<RequireAuth groups={[AGENT]}><AgentBusinessDashboard /></RequireAuth>} />
    </Routes>
  )
}

export default AppRouter;
