import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import { deleteClient, listClients } from '../api/clients.js'
import { Pager, ViewAll } from './ListFooter.jsx'
import { numberedSource, usePagedList } from './paging.js'

function deleteProblem(error) {
    if (error.status === 409) return 'This client still has accounts. Delete them under View Accounts and Transactions first.'
    if (error.status === 503) return 'The client could not be checked for accounts. Try again.'
    return 'The client could not be deleted. Try again.'
}

// The agent's own clients, newest first (ADR-0043), with their actions. On
// Agent Home the newest ten show; paged, the whole list does, ten at a time.
// onChanged is told when a client is deleted.
function ClientList({ onChanged, paged = false }) {
    const source = useMemo(() => numberedSource(listClients), [])
    const list = usePagedList(source)
    const [problem, setProblem] = useState('')
    const navigate = useNavigate()

    async function handleDelete(client) {
        if (!window.confirm(`Delete ${client.first_name} ${client.last_name}? This cannot be undone.`)) return
        setProblem('')
        try {
            await deleteClient(client.client_id)
            await list.reload()
            onChanged?.()
        } catch (error) {
            setProblem(deleteProblem(error))
        }
    }

    const empty = !list.loading && !list.error && list.rows.length === 0

    return (
        <div className="card card-flush">
            <h2>Manage Client Profiles and Accounts</h2>
            {list.loading && <p className="empty-note">Loading…</p>}
            {list.error && <p className="form-error" role="alert">Clients could not be loaded.</p>}
            {problem && <p className="form-error" role="alert">{problem}</p>}
            {empty && <p className="empty-note">No clients yet. Create one to get started.</p>}
            <ul className="row-list">
                {list.rows.map((client) => (
                    <li key={client.client_id}>
                        <span>
                            {client.first_name} {client.last_name}
                        </span>

                        <button onClick={() => navigate(`/agent/client/${client.client_id}/transactions`)}>
                            View Accounts and Transactions
                        </button>
                        {/* The client page is where the agent edits, verifies and opens accounts. */}
                        <button onClick={() => navigate(`/clients/${client.client_id}`)}>Edit Profile, Verify and Open Accounts</button>
                        <button onClick={() => handleDelete(client)}>Delete</button>
                    </li>
                ))}
            </ul>
            {!empty && paged && <Pager list={list} label="Client pages" />}
            {!paged && list.hasNext && <ViewAll to="/agent/clients" label="View all clients" />}
        </div>
    )
}

export default ClientList
