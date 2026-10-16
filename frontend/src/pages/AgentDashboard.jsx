import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import { createClient, deleteClient, listClients } from '../api/clients.js'
import { getDashboard, panelData, windowForDays } from '../api/dashboard.js'
import ActivityFeed from '../components/ActivityFeed.jsx'
import StatCard from '../components/StatCard.jsx'
import { formatNumber } from '../components/dashboard/format.js'

const CLIENT_PAGE = 20
// Transaction totals come from the business dashboard, which counts over a
// window (at most 365 days), so the tiles say which.
const TRANSACTION_DAYS = 90

const EMPTY_CLIENT = {
    first_name: '',
    last_name: '',
    date_of_birth: '',
    gender: '',
    email: '',
    phone_number: '',
    address: '',
    city: '',
    state: '',
    country: '',
    postal_code: '',
}

function createProblem(error) {
    if (error.status === 409) return 'A client with this email or phone number already exists.'
    if (error.status === 400) return `Check the details: ${error.message.replace(/^bad input:?\s*/i, '')}`
    return 'The client could not be created. Try again.'
}

function deleteProblem(error) {
    if (error.status === 409) return 'This client still has accounts. Delete them first.'
    if (error.status === 503) return 'The client could not be checked for accounts. Try again.'
    return 'The client could not be deleted. Try again.'
}

// The agent's own clients, newest first (ADR-0043), with their actions.
function ClientList({ onChanged }) {
    const [page, setPage] = useState(0)
    const [state, setState] = useState({ loading: true, error: '', data: null })
    const [problem, setProblem] = useState('')
    const navigate = useNavigate()

    const load = useCallback(async () => {
        try {
            const data = await listClients({ page, size: CLIENT_PAGE })
            setState({ loading: false, error: '', data })
        } catch {
            setState({ loading: false, error: 'Clients could not be loaded.', data: null })
        }
    }, [page])

    useEffect(() => {
      // The list is server-owned; fetching it is this panel's external synchronization.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      void load()
    }, [load])

    async function handleDelete(client) {
        if (!window.confirm(`Delete ${client.first_name} ${client.last_name}? This cannot be undone.`)) return
        setProblem('')
        try {
            await deleteClient(client.client_id)
            await load()
            onChanged()
        } catch (error) {
            setProblem(deleteProblem(error))
        }
    }

    const clients = state.data?.content ?? []
    const totalPages = state.data?.totalPages ?? 0

    return (
        <div className="card card-flush">
            <h2>Manage Client Profiles and Accounts</h2>
            {state.loading && <p className="empty-note">Loading…</p>}
            {state.error && <p className="form-error" role="alert">{state.error}</p>}
            {problem && <p className="form-error" role="alert">{problem}</p>}
            {!state.loading && !state.error && clients.length === 0 && (
                <p className="empty-note">No clients yet. Create one to get started.</p>
            )}
            <ul className="row-list">
                {clients.map((client) => (
                    <li key={client.client_id}>
                        <span>
                            {client.first_name} {client.last_name}
                        </span>

                        <button onClick={() => navigate(`/agent/client/${client.client_id}/transactions`)}>
                            View Accounts and Transactions
                        </button>
                        <button onClick={() => navigate(`/clients/${client.client_id}`)}>Edit</button>
                        <button onClick={() => handleDelete(client)}>Delete</button>
                    </li>
                ))}
            </ul>
            {totalPages > 1 && (
                <div className="form-actions">
                    <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
                    <span>Page {page + 1} of {totalPages}</span>
                    <button type="button" disabled={page + 1 >= totalPages} onClick={() => setPage(page + 1)}>Next</button>
                </div>
            )}
        </div>
    )
}

// "My Clients" from the client list; transaction counts from the business
// dashboard, which serves them for a window.
function useAgentTotals(version) {
    const [totals, setTotals] = useState({ clients: null, transactions: null, pending: null })

    useEffect(() => {
        let current = true
        Promise.allSettled([
            listClients({ size: 1 }),
            getDashboard('transactions', windowForDays(TRANSACTION_DAYS)),
        ]).then(([clients, dashboard]) => {
            if (!current) return
            const tx = dashboard.status === 'fulfilled' ? panelData(dashboard.value.panels?.transactions) : null
            setTotals({
                clients: clients.status === 'fulfilled' ? clients.value.totalElements : null,
                transactions: tx ? tx.total : null,
                pending: tx ? (tx.byStatus?.Pending ?? 0) : null,
            })
        })
        return () => { current = false }
    }, [version])

    return totals
}

function AgentDashboard() {

    // Variables and States
    const [openSection, setOpenSection] = useState(null)
    const [newClient, setNewClient] = useState(EMPTY_CLIENT)
    const [creating, setCreating] = useState({ saving: false, error: '' })
    // Bumped when a client is deleted, so the totals reload.
    const [version, setVersion] = useState(0)
    const totals = useAgentTotals(version)
    const navigate = useNavigate()

    // Functions
    function toggleSection(section) {
        setOpenSection(openSection === section ? null : section)
    }

    function setField(name) {
        return (event) => setNewClient((current) => ({ ...current, [name]: event.target.value }))
    }

    // The new client opens on its own page, where the agent uploads the
    // identity document and verifies the client (ADR-0041).
    async function handleCreateClient(event) {
        event.preventDefault()
        setCreating({ saving: true, error: '' })
        try {
            const created = await createClient({
                ...newClient,
                date_of_birth: `${newClient.date_of_birth}T00:00:00Z`,
            })
            navigate(`/clients/${created.client_id}`)
        } catch (error) {
            setCreating({ saving: false, error: createProblem(error) })
        }
    }

    // Render
    return (
        <div className="page">
            <header className="page-header">
                <h1>Agent Dashboard</h1>
                <p className="page-subtitle">Create and manage client profiles</p>
            </header>

            <div className="stat-grid">
                <StatCard label="My Clients" value={formatNumber(totals.clients)} icon="groups" />
                <StatCard
                    label="Transactions"
                    value={formatNumber(totals.transactions)}
                    icon="receipt_long"
                    caption={`Last ${TRANSACTION_DAYS} days`}
                />
                <StatCard
                    label="Pending Transactions"
                    value={formatNumber(totals.pending)}
                    icon="pending_actions"
                    caption={`Last ${TRANSACTION_DAYS} days`}
                />
            </div>

            <div className="action-bar">
                <button onClick={() => navigate('/agent/alerts')}>
                    Review Event Alerts
                </button>
                <button
                    onClick={() => toggleSection('createClientProfile')}
                    aria-expanded={openSection === 'createClientProfile'}
                >
                    Create Client Profile
                </button>
                <button
                    onClick={() => toggleSection('manageProfiles')}
                    aria-expanded={openSection === 'manageProfiles'}
                >
                    Manage Client Profiles and Accounts
                </button>
            </div>

            {openSection === 'createClientProfile' && (
                <form className="card form-grid" onSubmit={handleCreateClient}>
                    <h2>Create Client Profile</h2>
                    <label>First Name:<input type="text" required maxLength={50} value={newClient.first_name} onChange={setField('first_name')} /></label>
                    <label>Last Name:<input type="text" required maxLength={50} value={newClient.last_name} onChange={setField('last_name')} /></label>
                    <label>Date of Birth:<input type="date" required value={newClient.date_of_birth} onChange={setField('date_of_birth')} /></label>
                    <label>Gender:
                        <select required value={newClient.gender} onChange={setField('gender')}>
                            <option value="">Select Gender</option>
                            <option value="male">Male</option>
                            <option value="female">Female</option>
                            <option value="other">Other</option>
                        </select>
                    </label>
                    <label>Email:<input type="email" required maxLength={255} value={newClient.email} onChange={setField('email')} /></label>
                    <label>Phone Number:<input type="tel" required maxLength={20} value={newClient.phone_number} onChange={setField('phone_number')} /></label>
                    <label>Address:<input type="text" required maxLength={100} value={newClient.address} onChange={setField('address')} /></label>
                    <label>City:<input type="text" required maxLength={50} value={newClient.city} onChange={setField('city')} /></label>
                    <label>State:<input type="text" required maxLength={50} value={newClient.state} onChange={setField('state')} /></label>
                    <label>Country:<input type="text" required maxLength={50} value={newClient.country} onChange={setField('country')} /></label>
                    <label>Postal Code:<input type="text" required maxLength={10} value={newClient.postal_code} onChange={setField('postal_code')} /></label>
                    {creating.error && <p className="form-error form-grid__full" role="alert">{creating.error}</p>}
                    <div className="form-actions">
                        <button type="submit" disabled={creating.saving}>{creating.saving ? 'Creating…' : 'Create Profile'}</button>
                        <button type="button" onClick={() => toggleSection('createClientProfile')}>Cancel</button>
                    </div>
                </form>
            )}

            {openSection === 'manageProfiles' && <ClientList onChanged={() => setVersion((v) => v + 1)} />}

            <ActivityFeed />
        </div>
    )
}

export default AgentDashboard;
