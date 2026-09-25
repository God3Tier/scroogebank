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
                    {/* Add more form fields as needed */}
                </div>
            )}

        </div>
    )
}

export default AgentDashboard;