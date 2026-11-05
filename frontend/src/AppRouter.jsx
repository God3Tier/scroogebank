import { useAuth } from 'react-oidc-context'
import { Routes, Route, Navigate } from 'react-router-dom'
import RequireAuth from './auth/RequireAuth.jsx'
import { ADMIN, AGENT, homeFor } from './auth/roles.js'
import Home from './pages/Home.jsx'
import Login from './pages/Login.jsx'
import SignInCallback from './pages/SignInCallback.jsx'
import MfaSetup from './pages/MfaSetup.jsx'
import AgentDashboard from './pages/AgentDashboard.jsx'
import AdminDashboard from './pages/AdminDashboard.jsx'
import ClientTransactions from './pages/ClientTransactions.jsx'
import ClientProfile from './pages/ClientProfile.jsx'
import ClientHealthMonitor from './pages/ClientHealthMonitor.jsx'
import EventAlerts from './pages/EventAlerts.jsx'
import AdminBusinessDashboard from './pages/AdminBusinessDashboard.jsx'
import AgentBusinessDashboard from './pages/AgentBusinessDashboard.jsx'
import AgentCalendar from './pages/AgentCalendar.jsx'
import AgentDrafts from './pages/AgentDrafts.jsx'
import ListPage from './pages/ListPage.jsx'
import AccountTransactions from './pages/AccountTransactions.jsx'
import AgentPerformance from './pages/AgentPerformance.jsx'
import ActivityFeed from './components/ActivityFeed.jsx'
import ClientList from './components/ClientList.jsx'
import TransactionsTable from './components/TransactionsTable.jsx'
import UserList from './components/UserList.jsx'

// Admins open client pages from their dashboard's action items, and may read
// any client's transactions, so that page is open to both roles. The client
// page is too: client-service lets an admin act on any client and an agent
// only on their own (ADR-0041). The lists on Agent Home and Admin Home show
// their first ten rows and open in full, paged, on their own pages.

function HomeEntry() {
  const auth = useAuth()
  if (!auth.isLoading){
    return(
      <p className="empty-state" role="status">
        Checking your sign-in status...
      </p>
    )
  }

  if (auth.isAuthenticated) {
    const destination = homeFor(auth.user)
    if (destination !== '/') {
      return <Navigate to={destination} replace />
    }
  }
  return <Home />
}

function AppRouter() {
  return (
    <Routes>
      <Route path="/" element={<HomeEntry />} />
      <Route path="/login" element={<Login />} />
      <Route path="/auth/callback" element={<SignInCallback />} />
      <Route path="/mfa-setup" element={<MfaSetup />} />
      <Route path="/agent" element={<RequireAuth groups={[AGENT]}><AgentDashboard /></RequireAuth>} />
      <Route path="/agent/alerts" element={<RequireAuth groups={[AGENT]}><EventAlerts /></RequireAuth>} />
      <Route path="/agent/calendar" element={<RequireAuth groups={[AGENT]}><AgentCalendar /></RequireAuth>} />
      <Route path="/agent/drafts" element={<RequireAuth groups={[AGENT]}><AgentDrafts /></RequireAuth>} />
      <Route path="/admin" element={<RequireAuth groups={[ADMIN]}><AdminDashboard /></RequireAuth>} />
      <Route path="/agent/client/:clientId/transactions" element={<RequireAuth groups={[ADMIN, AGENT]}><ClientTransactions /></RequireAuth>} />
      <Route path="/agent/client/:clientId/accounts/:accountId/transactions" element={<RequireAuth groups={[ADMIN, AGENT]}><AccountTransactions /></RequireAuth>} />
      <Route path="/clients/:clientId" element={<RequireAuth groups={[ADMIN, AGENT]}><ClientProfile /></RequireAuth>} />
      <Route path="/agent/client-health" element={<RequireAuth groups={[AGENT]}><ClientHealthMonitor /></RequireAuth>} />
      <Route path="/admin/client-health" element={<RequireAuth groups={[ADMIN]}><ClientHealthMonitor /></RequireAuth>} />
      <Route path="/admin/alerts" element={<RequireAuth groups={[ADMIN]}><EventAlerts readOnly /></RequireAuth>} />
      <Route path="/insights/admin" element={<RequireAuth groups={[ADMIN]}><AdminBusinessDashboard /></RequireAuth>} />
      <Route path="/insights/admin/agents" element={<RequireAuth groups={[ADMIN]}><AgentPerformance /></RequireAuth>} />
      <Route path="/insights/agent" element={<RequireAuth groups={[AGENT]}><AgentBusinessDashboard /></RequireAuth>} />
      <Route path="/agent/clients" element={<RequireAuth groups={[AGENT]}>
        <ListPage title="All clients" subtitle="Your clients, newest first" back="/agent"><ClientList paged /></ListPage>
      </RequireAuth>} />
      <Route path="/agent/activities" element={<RequireAuth groups={[AGENT]}>
        <ListPage title="All activities" subtitle="Your changes, newest first" back="/agent"><ActivityFeed paged /></ListPage>
      </RequireAuth>} />
      <Route path="/admin/admins" element={<RequireAuth groups={[ADMIN]}>
        <ListPage title="All admins" subtitle="Alphabetical by last name" back="/admin"><UserList role="ADMIN" title="Manage Admins" paged /></ListPage>
      </RequireAuth>} />
      <Route path="/admin/agents" element={<RequireAuth groups={[ADMIN]}>
        <ListPage title="All agents" subtitle="Alphabetical by last name" back="/admin"><UserList role="AGENT" title="Manage Agents" paged /></ListPage>
      </RequireAuth>} />
      <Route path="/admin/activities" element={<RequireAuth groups={[ADMIN]}>
        <ListPage title="All activities" subtitle="Changes CRM users made, newest first" back="/admin"><ActivityFeed admin paged /></ListPage>
      </RequireAuth>} />
      <Route path="/admin/transactions" element={<RequireAuth groups={[ADMIN]}>
        <ListPage title="All transactions" subtitle="Every account's transactions, newest first" back="/admin"><TransactionsTable paged /></ListPage>
      </RequireAuth>} />
    </Routes>
  )
}

export default AppRouter;
