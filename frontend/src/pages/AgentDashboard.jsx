import { useState } from 'react'

function AgentDashboard() {
    const [isOpen, setIsOpen] = useState(false)

    const [openSection, setOpenSection] = useState(null)

    function toggleSection(section) {
        setOpenSection(openSection === section ? null : section)
    }

    return (
        <div>
            <h1>Agent Dashboard</h1>
            
            <button onClick={() => toggleSection('createClientProfile')}>
                Create Client Profile
            </button>

            {openSection === 'createClientProfile' && (
                <div>
                    <h2>Create Client Profile</h2>
                    <label>First Name:<input type="text" /></label>
                    <label>Last Name:<input type="text" /></label>
                    <label>Date of Birth:<input type="date" /></label>
                    <label>Gender:<input type="text" /></label>
                    <label>Email:<input type="email" /></label>
                    <label>Phone Number:<input type="tel" /></label>
                    <label>Address:<input type="text" /></label>
                    <label>City:<input type="text" /></label>
                    <label>State:<input type="text" /></label>
                    <label>Country:<input type="text" /></label>
                    <label>Postal Code:<input type="text" /></label>
                    <button onClick={() => {/* Add functionality to create client profile */}}>Create Profile</button>
                    <button onClick={() => toggleSection('createClientProfile')}>Cancel</button>
                </div>
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
                        <li>
                            <span>John Doe</span>
                            <button onClick={() => {/* Add functionality to view transactions */}}>View Transactions</button>
                            <button onClick={() => {/* Add functionality to edit client profile */}}>Edit</button>
                            <button onClick={() => {/* Add functionality to delete client profile */}}>Delete</button>
                        </li>
                        <li>
                            <span>Jane Smith</span>
                            <button onClick={() => {/* Add functionality to view transactions */}}>View Transactions</button>
                            <button onClick={() => {/* Add functionality to edit client profile */}}>Edit</button>
                            <button onClick={() => {/* Add functionality to delete client profile */}}>Delete</button>
                        </li>
                    </ul>
                </div>
            )}
        </div>
    )
}

export default AgentDashboard;