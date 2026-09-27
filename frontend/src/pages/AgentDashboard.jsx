import { useState } from 'react'
import { useNavigate } from 'react-router-dom'

{/* Below imports are seeded data, testing purposes */}
import clients from '../data/clients.js'

function AgentDashboard() {

    {/* Variables and States */}
    const [openSection, setOpenSection] = useState(null)
    const navigate = useNavigate()

    {/* Functions */}
    function toggleSection(section) {
        setOpenSection(openSection === section ? null : section)
    }

    function handleCreateClient(event) {
        event.preventDefault()

        console.log('Creating client profile...')
    }

    {/* Render */}
    return (
        <div>
            <h1>Agent Dashboard</h1>

            <button onClick={() => navigate('/agent/alerts')}>
                Review Event Alerts
            </button>
            
            <button onClick={() => toggleSection('createClientProfile')}>
                Create Client Profile
            </button>

            {openSection === 'createClientProfile' && (
                <form onSubmit={handleCreateClient}>
                    <h2>Create Client Profile</h2>
                    <label>First Name:<input type="text" /></label>
                    <label>Last Name:<input type="text" /></label>
                    <label>Date of Birth:<input type="date" /></label>
                    <label>Gender:
                        <select>
                            <option value="">Select Gender</option>
                            <option value="male">Male</option>
                            <option value="female">Female</option>
                            <option value="other">Other</option>
                        </select>
                    </label>
                    <label>Email:<input type="email" /></label>
                    <label>Phone Number:<input type="tel" /></label>
                    <label>Address:<input type="text" /></label>
                    <label>City:<input type="text" /></label>
                    <label>State:<input type="text" /></label>
                    <label>Country:<input type="text" /></label>
                    <label>Postal Code:<input type="text" /></label>
                    <button type="submit">Create Profile</button>
                    <button onClick={() => toggleSection('createClientProfile')}>Cancel</button>
                </form>
            )}

            <button onClick={() => toggleSection('manageProfiles')}>
                Manage Client Profiles
            </button>

            {openSection === 'manageProfiles' && (
                <div>
                    <h2>Manage Client Profiles</h2>
                    {/* 
                    Add functionality to manage client profiles 
                    - List of client profiles
                    - View Transactions, Edit and delete options for each profile
                    */}
                    <ul>
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
        </div>
    )
}

export default AgentDashboard;
