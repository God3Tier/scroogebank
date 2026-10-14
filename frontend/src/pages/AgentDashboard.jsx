import { useState } from 'react'
import { useNavigate } from 'react-router-dom'

import { createClient } from '../api/clients.js'
// Below imports are seeded data, testing purposes. client-service has no
// endpoint that lists clients, so the list below is still synthetic.
import clients from '../data/clients.js'
import transactions from '../data/transactions.js'
import ActivityFeed from '../components/ActivityFeed.jsx'
import StatCard from '../components/StatCard.jsx'

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

function AgentDashboard() {

    // Variables and States
    const [openSection, setOpenSection] = useState(null)
    const [newClient, setNewClient] = useState(EMPTY_CLIENT)
    const [creating, setCreating] = useState({ saving: false, error: '' })
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
                <StatCard label="My Clients" value={clients.length} icon="groups" />
                <StatCard label="Transactions" value={transactions.length} icon="receipt_long" />
                <StatCard
                    label="Pending Transactions"
                    value={transactions.filter((transaction) => transaction.status === 'Pending').length}
                    icon="pending_actions"
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
                    Manage Client Profiles
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

            {openSection === 'manageProfiles' && (
                <div className="card card-flush">
                    <h2>Manage Client Profiles</h2>
                    {/*
                    Add functionality to manage client profiles
                    - List of client profiles
                    - View Transactions, Edit and delete options for each profile
                    */}
                    <ul className="row-list">
                        {clients.map(client => (
                            <li key={client.clientId}>
                                <span>
                                    {client.firstName} {client.lastName}
                                </span>

                                <button onClick={() => navigate(`/agent/client/${client.clientId}/transactions`)}>
                                    View Transactions
                                </button>
                                <button>Edit</button>
                                <button>Delete</button>
                            </li>
                        ))}
                    </ul>
                </div>
            )}

            <ActivityFeed />
        </div>
    )
}

export default AgentDashboard;
