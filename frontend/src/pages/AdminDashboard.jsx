import { useState } from 'react'

import admins from '../data/admins.js'
import agents from '../data/agents.js'
import clients from '../data/clients.js'
import transactions from '../data/transactions.js'
import StatCard from '../components/StatCard.jsx'

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
    <div className="page">
      <header className="page-header">
        <h1>Admin Dashboard</h1>
        <p className="page-subtitle">Manage staff accounts and review transactions</p>
      </header>

      <div className="stat-grid">
        <StatCard label="Total Admins" value={admins.length} icon="admin_panel_settings" />
        <StatCard label="Total Agents" value={agents.length} icon="badge" />
        <StatCard label="Total Clients" value={clients.length} icon="groups" />
        <StatCard label="Transactions" value={transactions.length} icon="receipt_long" />
      </div>

      <div className="action-bar">
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

      {openSection === 'createUser' && (
        <form className="card form-grid" onSubmit={handleCreateAccount}>
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
          <div className="form-actions">
            <button type="submit">Create Account</button>
          </div>
        </form>
      )}

      {openSection === 'manageAdmins' && (
        <div className="card card-flush">
          <h2>Manage Admins</h2>

          <ul className="row-list">
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

      {openSection === 'manageAgents' && (
        <div className="card card-flush">
          <h2>Manage Agents</h2>

          <ul className="row-list">
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

      <section className="card card-flush">
        <h2>All Transactions</h2>
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
              {
                transactions.map((transaction) =>
                  {
                    const client = clients.find(
                      (client) => client.clientId === transaction.clientId
                    )

                  return (
                    <tr key={transaction.transactionId}>
                      <td className="mono">{transaction.transactionId}</td>
                      <td>{client ? `${client.firstName} ${client.lastName}`
                      : 'Unknown Client'}
                      </td>
                      <td>{transaction.type}</td>
                      <td className="num">{transaction.amount}</td>
                      <td>{transaction.date}</td>
                      <td>
                        <span className={`badge badge-${String(transaction.status).toLowerCase()}`}>
                          {transaction.status}
                        </span>
                      </td>
                    </tr>
                  )}
                )
              }
            </tbody>
          </table>
        </div>
      </section>
    </div>
  )
}

export default AdminDashboard
