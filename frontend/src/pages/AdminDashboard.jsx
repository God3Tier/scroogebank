import { useState } from 'react'

import admins from '../data/admins.js'
import agents from '../data/agents.js'
import clients from '../data/clients.js'
import transactions from '../data/transactions.js'

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

      <h2>All Transactions</h2>
      <table>
        <thead>
          <tr>
            <th>Transaction ID</th>
            <th>Client</th>
            <th>Type</th>
            <th>Amount</th>
            <th>Date</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          {
            transactions.map((transaction) =>
              {
                const client = clients.find(
                  (client) => client.clientId === transaction.clientId
                )
              
              return (
                <tr key={transaction.transactionId}>
                  <td>{transaction.transactionId}</td>
                  <td>{client ? `${client.firstName} ${client.lastName}`
                  : 'Unknown Client'}
                  </td>
                  <td>{transaction.type}</td>
                  <td>{transaction.amount}</td>
                  <td>{transaction.date}</td>
                  <td>{transaction.status}</td>
                </tr>
              )}
            )
          }
        </tbody>
      </table>
    </div>
  )
}

export default AdminDashboard