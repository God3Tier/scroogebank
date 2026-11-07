import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import { listClients } from '../api/clients.js'
import { createUser, getCurrentAdmin, listUsers } from '../api/users.js'
import ActivityFeed from '../components/ActivityFeed.jsx'
import StatCard from '../components/StatCard.jsx'
import TransactionsTable from '../components/TransactionsTable.jsx'
import UserList from '../components/UserList.jsx'
import { formatNumber } from '../components/dashboard/format.js'

const EMPTY_USER = { firstName: '', lastName: '', email: '', role: '' }

function createProblem(error) {
  if (error.status === 409) return 'A user with this email already exists.'
  if (error.status === 400) return `Check the details: ${error.message}`
  return 'The account could not be created. Try again.'
}

// Totals come from a one-row page of each list.
async function totalOf(load) {
  try {
    return (await load()).totalElements
  } catch {
    return null
  }
}

function CreateUserForm({ onCreated, rootAdmin }) {
  const [user, setUser] = useState({ ...EMPTY_USER, role: rootAdmin ? '' : 'AGENT' })
  const [saving, setSaving] = useState({ busy: false, error: '', created: '' })

  const setField = (name) => (event) => setUser((current) => ({ ...current, [name]: event.target.value }))

  async function handleSubmit(event) {
    event.preventDefault()
    setSaving({ busy: true, error: '', created: '' })
    try {
      await createUser(user)
      setSaving({ busy: false, error: '', created: user.email })
      setUser({ ...EMPTY_USER, role: rootAdmin ? '' : 'AGENT' })
      onCreated()
    } catch (error) {
      setSaving({ busy: false, error: createProblem(error), created: '' })
    }
  }

  return (
    <form className="card form-grid" onSubmit={handleSubmit}>
      <h2>{rootAdmin ? 'Create Admin / Agent' : 'Create Agent'}</h2>
      <label>First Name:<input type="text" required value={user.firstName} onChange={setField('firstName')} /></label>
      <label>Last Name:<input type="text" required value={user.lastName} onChange={setField('lastName')} /></label>
      <label>Email:<input type="email" required value={user.email} onChange={setField('email')} /></label>
      {rootAdmin && <label>Role:
        <select required value={user.role} onChange={setField('role')}>
          <option value="">Select Role</option>
          <option value="ADMIN">Admin</option>
          <option value="AGENT">Agent</option>
        </select>
      </label>}
      {saving.error && <p className="form-error form-grid__full" role="alert">{saving.error}</p>}
      {saving.created && (
        <p className="form-grid__full" role="status">
          Created {saving.created}. Cognito has e-mailed them a temporary password.
        </p>
      )}
      <div className="form-actions">
        <button type="submit" disabled={saving.busy}>{saving.busy ? 'Creating…' : 'Create Account'}</button>
      </div>
    </form>
  )
}

function AdminDashboard() {
  const [openSection, setOpenSection] = useState(null)
  const [totals, setTotals] = useState({ admins: null, agents: null, clients: null })
  // Bumped when a user is created, so open lists and totals reload.
  const [version, setVersion] = useState(0)
  const [rootAdmin, setRootAdmin] = useState(false)
  const navigate = useNavigate()

  useEffect(() => {
    let current = true
    Promise.all([
      totalOf(() => listUsers({ role: 'ADMIN', size: 1 })),
      totalOf(() => listUsers({ role: 'AGENT', size: 1 })),
      totalOf(() => listClients({ size: 1 })),
    ]).then(([admins, agents, clients]) => {
      if (current) setTotals({ admins, agents, clients })
    })
    return () => { current = false }
  }, [version])

  useEffect(() => {
    let current = true
    getCurrentAdmin().then((user) => {
      if (current) setRootAdmin(user.role === 'ROOT_ADMIN')
    }).catch(() => {
      if (current) setRootAdmin(false)
    })
    return () => { current = false }
  }, [])

  function toggleSection(section) {
    setOpenSection(openSection === section ? null : section)
  }

  return (
    <div className="page">
      <header className="page-header">
        <h1>Admin Home</h1>
        <p className="page-subtitle">Manage staff accounts and review transactions</p>
      </header>

      <div className="stat-grid">
        <StatCard label="Total Admins" value={formatNumber(totals.admins)} icon="admin_panel_settings" />
        <StatCard label="Total Agents" value={formatNumber(totals.agents)} icon="badge" />
        <StatCard label="Total Clients" value={formatNumber(totals.clients)} icon="groups" />
      </div>

      <div className="action-bar">
        <button onClick={() => navigate('/admin/alerts')}>
          Review Event Alerts
        </button>
        {rootAdmin && <button
          onClick={() => toggleSection('createUser')}
          aria-expanded={openSection === 'createUser'}
        >
          Create Admin / Agent
        </button>}
        {!rootAdmin && <button
          onClick={() => toggleSection('createUser')}
          aria-expanded={openSection === 'createUser'}
        >Create Agent</button>}
        <button
          onClick={() => toggleSection('manageAdmins')}
          aria-expanded={openSection === 'manageAdmins'}
        >
          Manage Admins
        </button>
        <button
          onClick={() => toggleSection('manageAgents')}
          aria-expanded={openSection === 'manageAgents'}
        >
          Manage Agents
        </button>
      </div>

      {openSection === 'createUser' && <CreateUserForm rootAdmin={rootAdmin} onCreated={() => setVersion((v) => v + 1)} />}
      {openSection === 'manageAdmins' && <UserList key={`admins-${version}`} role="ADMIN" title="Manage Admins" />}
      {openSection === 'manageAgents' && <UserList key={`agents-${version}`} role="AGENT" title="Manage Agents" />}

      <ActivityFeed admin />

      <TransactionsTable />
    </div>
  )
}

export default AdminDashboard
