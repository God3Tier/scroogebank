import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'

import { listClients } from '../api/clients.js'
import { listTransactions } from '../api/transactions.js'
import { createUser, disableUser, listUsers } from '../api/users.js'
import ActivityFeed from '../components/ActivityFeed.jsx'
import StatCard from '../components/StatCard.jsx'
import { NO_FIGURE, formatNumber } from '../components/dashboard/format.js'

const EMPTY_USER = { firstName: '', lastName: '', email: '', role: '' }
const TRANSACTION_PAGE = 20

function createProblem(error) {
  if (error.status === 409) return 'A user with this email already exists.'
  if (error.status === 400) return `Check the details: ${error.message}`
  return 'The account could not be created. Try again.'
}

function disableProblem(error) {
  if (error.status === 403) return 'This user cannot be disabled.'
  return 'The user could not be disabled. Try again.'
}

// Totals come from a one-row page of each list.
async function totalOf(load) {
  try {
    return (await load()).totalElements
  } catch {
    return null
  }
}

function shortId(id) {
  return id ? String(id).slice(0, 8) : NO_FIGURE
}

function formatDate(value) {
  return new Date(value).toLocaleDateString('en-SG', { dateStyle: 'medium' })
}

function CreateUserForm({ onCreated }) {
  const [user, setUser] = useState(EMPTY_USER)
  const [saving, setSaving] = useState({ busy: false, error: '', created: '' })

  const setField = (name) => (event) => setUser((current) => ({ ...current, [name]: event.target.value }))

  async function handleSubmit(event) {
    event.preventDefault()
    setSaving({ busy: true, error: '', created: '' })
    try {
      await createUser(user)
      setSaving({ busy: false, error: '', created: user.email })
      setUser(EMPTY_USER)
      onCreated()
    } catch (error) {
      setSaving({ busy: false, error: createProblem(error), created: '' })
    }
  }

  return (
    <form className="card form-grid" onSubmit={handleSubmit}>
      <h2>Create Admin / Agent</h2>
      <label>First Name:<input type="text" required value={user.firstName} onChange={setField('firstName')} /></label>
      <label>Last Name:<input type="text" required value={user.lastName} onChange={setField('lastName')} /></label>
      <label>Email:<input type="email" required value={user.email} onChange={setField('email')} /></label>
      <label>Role:
        <select required value={user.role} onChange={setField('role')}>
          <option value="">Select Role</option>
          <option value="ADMIN">Admin</option>
          <option value="AGENT">Agent</option>
        </select>
      </label>
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

// The admins or agents, with Disable. Users are never deleted, and the root
// admin cannot be disabled (user-service answers 403).
function UserList({ role, title, version }) {
  const [state, setState] = useState({ loading: true, error: '', users: [] })
  const [problem, setProblem] = useState('')

  const load = useCallback(async () => {
    try {
      const page = await listUsers({ role, size: 100 })
      setState({ loading: false, error: '', users: page.content })
    } catch {
      setState({ loading: false, error: 'Users could not be loaded.', users: [] })
    }
  }, [role])

  useEffect(() => {
    // The list is server-owned; fetching it is this panel's external synchronization.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load()
  }, [load, version])

  async function handleDisable(user) {
    setProblem('')
    try {
      await disableUser(user.id)
      await load()
    } catch (error) {
      setProblem(disableProblem(error))
    }
  }

  return (
    <div className="card card-flush">
      <h2>{title}</h2>
      {state.loading && <p className="empty-note">Loading…</p>}
      {state.error && <p className="form-error" role="alert">{state.error}</p>}
      {problem && <p className="form-error" role="alert">{problem}</p>}
      {!state.loading && !state.error && state.users.length === 0 && <p className="empty-note">None yet.</p>}
      <ul className="row-list">
        {state.users.map((user) => (
          <li key={user.id}>
            <span>
              {user.firstName} {user.lastName} ({user.email})
            </span>
            {user.enabled ? (
              <button type="button" onClick={() => handleDisable(user)}>Disable</button>
            ) : (
              <span className="badge">Disabled</span>
            )}
          </li>
        ))}
      </ul>
    </div>
  )
}

// Every account's transactions, newest first (ADR-0043).
function TransactionsTable() {
  const [rows, setRows] = useState([])
  const [nextCursor, setNextCursor] = useState(null)
  const [state, setState] = useState({ loading: true, error: '' })

  const load = useCallback(async (cursor) => {
    setState({ loading: true, error: '' })
    try {
      const page = await listTransactions({ cursor, size: TRANSACTION_PAGE })
      setRows((current) => (cursor ? [...current, ...page.content] : page.content))
      setNextCursor(page.nextCursor)
      setState({ loading: false, error: '' })
    } catch {
      setState({ loading: false, error: 'Transactions could not be loaded.' })
    }
  }, [])

  useEffect(() => {
    // The list is server-owned; fetching it is this panel's external synchronization.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load(null)
  }, [load])

  return (
    <section className="card card-flush">
      <h2>All Transactions</h2>
      {state.error && <p className="form-error" role="alert">{state.error}</p>}
      <div className="table-wrap">
        <table className="data-table">
          <thead>
            <tr>
              <th>Transaction ID</th>
              <th>Client</th>
              <th>Type</th>
              <th className="num">Amount</th>
              <th>Date</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((transaction) => (
              <tr key={transaction.transaction_id}>
                <td className="mono">{transaction.transaction_id}</td>
                <td>
                  {transaction.client_id ? (
                    <Link to={`/clients/${encodeURIComponent(transaction.client_id)}`} title={transaction.client_id}>
                      <span className="mono">{shortId(transaction.client_id)}</span>
                    </Link>
                  ) : NO_FIGURE}
                </td>
                <td>{transaction.transaction_type}</td>
                <td className="num">{transaction.currency ? `${transaction.currency} ` : ''}{transaction.amount}</td>
                <td>{formatDate(transaction.transaction_date)}</td>
                <td>
                  <span className={`badge badge-${String(transaction.status).toLowerCase()}`}>{transaction.status}</span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {!state.loading && !state.error && rows.length === 0 && <p className="empty-note">No transactions yet.</p>}
      {state.loading && <p className="empty-note">Loading…</p>}
      {nextCursor && !state.loading && (
        <div className="form-actions">
          <button type="button" onClick={() => load(nextCursor)}>Load more</button>
        </div>
      )}
    </section>
  )
}

function AdminDashboard() {
  const [openSection, setOpenSection] = useState(null)
  const [totals, setTotals] = useState({ admins: null, agents: null, clients: null })
  // Bumped when a user is created, so open lists and totals reload.
  const [version, setVersion] = useState(0)
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

  function toggleSection(section) {
    setOpenSection(openSection === section ? null : section)
  }

  return (
    <div className="page">
      <header className="page-header">
        <h1>Admin Dashboard</h1>
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
        <button
          onClick={() => toggleSection('createUser')}
          aria-expanded={openSection === 'createUser'}
        >
          Create Admin / Agent
        </button>
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

      {openSection === 'createUser' && <CreateUserForm onCreated={() => setVersion((v) => v + 1)} />}
      {openSection === 'manageAdmins' && <UserList role="ADMIN" title="Manage Admins" version={version} />}
      {openSection === 'manageAgents' && <UserList role="AGENT" title="Manage Agents" version={version} />}

      <ActivityFeed admin />

      <TransactionsTable />
    </div>
  )
}

export default AdminDashboard
