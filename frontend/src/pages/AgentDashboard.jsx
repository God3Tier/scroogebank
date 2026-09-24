import { useState } from 'react'

function AgentDashboard() {
    const [isOpen, setIsOpen] = useState(false)

    return (
        <div>
            <h1>Agent Dashboard</h1>
            
            <div className="accordion">
                <button
                className="accordion-header"
                onClick={() => setIsOpen(!isOpen)}
                >
                Create Client Profile
                <span>{isOpen ? '▲' : '▼'}</span>
                </button>

                {isOpen && (
                <div className="accordion-content">
                    <label htmlFor="firstName">First Name:</label>
                    <input type="text" id="firstName" name="firstName" />
                    <label htmlFor="lastName">Last Name:</label>
                    <input type="text" id="lastName" name="lastName" />
                    <label htmlFor="dateOfBirth">Date of Birth:</label>
                    <input type="date" id="dateOfBirth" name="dateOfBirth" />
                    <label htmlFor="gender">Gender:</label>
                    <select id="gender" name="gender">
                        <option value="male">Male</option>
                        <option value="female">Female</option>
                        <option value="other">Other</option>
                    </select>
                    <label htmlFor="email">Email:</label>
                    <input type="email" id="email" name="email" />
                    <label htmlFor="phone">Phone:</label>
                    <input type="tel" id="phone" name="phone" />
                    <label htmlFor="address">Address:</label>
                    <input type="text" id="address" name="address" />
                    <label htmlFor="city">City:</label>
                    <input type="text" id="city" name="city" />
                    <label htmlFor="state">State:</label>
                    <input type="text" id="state" name="state" />
                    <label htmlFor="postalCode">Postal Code:</label>
                    <input type="text" id="postalCode" name="postalCode" />
                    <button type="submit">Create Profile</button>
                </div>
                )}

                
            </div>
        </div>
    )
}

export default AgentDashboard;