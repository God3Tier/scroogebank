import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import { createClient, listClients } from '../api/clients.js'
import { validateClient } from '../components/clientValidation.js'
import { getDashboard, panelData, windowForDays } from '../api/dashboard.js'
import ActivityFeed from '../components/ActivityFeed.jsx'
import ClientList from '../components/ClientList.jsx'
import StatCard from '../components/StatCard.jsx'
import UpcomingMeetings from '../components/engagement/UpcomingMeetings.jsx'
import { formatNumber } from '../components/dashboard/format.js'

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
    // Clients are Singapore residents (brief Appendix 2, Q&A CPM29), and
    // Singapore is a city-state; the agent can still change these.
    city: 'Singapore',
    state: 'Singapore',
    country: 'Singapore',
    postal_code: '',
}

function createProblem(error) {
    if (error.status === 409) return 'A client with this email or phone number already exists.'
    if (error.status === 400) return `Check the details: ${error.message.replace(/^bad input:?\s*/i, '')}`
    return 'The client could not be created. Try again.'
}

// "My Clients" from the client list; transaction counts from the business
// dashboard, which serves them for a window.
function useAgentTotals(version) {
    const [totals, setTotals] = useState({ loading: true, clients: null, transactions: null, pending: null })

    useEffect(() => {
        let current = true
        Promise.allSettled([
            listClients({ size: 1 }),
            getDashboard('transactions', windowForDays(TRANSACTION_DAYS)),
        ]).then(([clients, dashboard]) => {
            if (!current) return
            const tx = dashboard.status === 'fulfilled' ? panelData(dashboard.value.panels?.transactions) : null
            setTotals({
                loading: false,
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
        const problem = validateClient(newClient)
        if (problem) {
            setCreating({ saving: false, error: problem })
            return
        }
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
                <h1>Agent Home</h1>
                <p className="page-subtitle">Create and manage client profiles</p>
            </header>

            <div className="stat-grid">
                <StatCard loading={totals.loading} label="My Clients" value={formatNumber(totals.clients)} icon="groups" />
                <StatCard
                    loading={totals.loading}
                    label="Transactions"
                    value={formatNumber(totals.transactions)}
                    icon="receipt_long"
                    caption={`Last ${TRANSACTION_DAYS} days`}
                />
                <StatCard
                    loading={totals.loading}
                    label="Pending Transactions"
                    value={formatNumber(totals.pending)}
                    icon="pending_actions"
                    caption={`Last ${TRANSACTION_DAYS} days`}
                />
            </div>

            <UpcomingMeetings />

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
                    <label>Phone Number:<input type="tel" required maxLength={20} placeholder="+6591234567" value={newClient.phone_number} onChange={setField('phone_number')} /></label>
                    <label>Address:<input type="text" required maxLength={100} value={newClient.address} onChange={setField('address')} /></label>
                    <label>City:<input type="text" required maxLength={50} value={newClient.city} onChange={setField('city')} /></label>
                    <label>State:<input type="text" required maxLength={50} value={newClient.state} onChange={setField('state')} /></label>
                    <label>Country:<input type="text" required maxLength={50} value={newClient.country} onChange={setField('country')} /></label>
                    <label>Postal Code:<input type="text" required maxLength={10} placeholder="6 digits in Singapore" value={newClient.postal_code} onChange={setField('postal_code')} /></label>
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
