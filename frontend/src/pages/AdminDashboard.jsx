import { useState } from 'react'

import admins from '../data/admins.js'
import agents from '../data/agents.js'

function AdminDashboard() {
  const [openSection, setOpenSection] = useState(null)

  function toggleSection(section) {
    setOpenSection(openSection === section ? null : section)
  }

  function handleCreateAccount(event){
    event.preventDefault()

    console.log('Creating Admin/Agent...')
  }

  return (
    <div>
      <h1>Admin Dashboard</h1>

      <button onClick={() => toggleSection('createUser')}>
        Create Admin / Agent
      </button>

      {openSection === 'createUser' && (
        <form onSubmit={handleCreateAccount}>
          <h2>Create Admin / Agent</h2>
          <label>First Name:<input type="text" /></label>
          <label>Last Name:<input type="text" /></label>
          <label>Email:<input type="text" /></label>
          <label>Phone Number:<input type="text" /></label>
          <label>Role:
            <select>
              <option value="">Select Role</option>
              <option value="admin">Admin</option>
              <option value="agent">Agent</option>
            </select>
          </label>
          <button type="submit">Create Account</button>
        </form>
      )}

      <button onClick={() => toggleSection('manageAdmins')}>
        Manage Admins
      </button>

      {openSection === 'manageAdmins' && (
        <div>
          <h2>Manage Admins</h2>

          <ul>
            {admins.map((admin) => (
              <li key={admin.adminId}>
                <span>
                  {admin.firstName} {admin.lastName}
                </span>

                <button>Edit</button>
                <button>Delete</button>
              </li>
            ))}
          </ul>
        </div>
      )}

      <button onClick={() => toggleSection('manageAgents')}>
        Manage Agents
      </button>

      {openSection === 'manageAgents' && (
        <div>
          <h2>Manage Agents</h2>

          <ul>
            {agents.map((agent) => (
              <li key={agent.agentId}>
                <span>
                  {agent.firstName} {agent.lastName}
                </span>

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

export default AdminDashboard