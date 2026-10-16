import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'

import { listAccountTransactions, listAccounts } from '../api/accounts.js'
import { getClient } from '../api/clients.js'

function loadProblem(error) {
    // client-service answers 404 for another agent's client too.
    if (error.status === 404) return 'Client not found.'
    return 'The client could not be loaded. Try again.'
}

function formatDate(value) {
    return new Date(value).toLocaleDateString('en-SG', { dateStyle: 'medium' })
}

// A client's transactions, grouped by account: account-service lists the
// client's accounts, and user-service reads each account's transactions after
// checking the caller may (ADR-0006, ADR-0035).
async function loadClientTransactions(clientId) {
    const [client, accounts] = await Promise.all([getClient(clientId), listAccounts(clientId)])
    const transactions = await Promise.all(accounts.map((account) => listAccountTransactions(account.id)))
    return { client, accounts: accounts.map((account, i) => ({ ...account, transactions: transactions[i] })) }
}

function AccountTransactions({ account }) {
    return (
        <section className="card card-flush">
            <h2>
                {account.account_type} account <span className="mono" title={account.id}>{account.id.slice(0, 8)}</span>
            </h2>
            {account.transactions.length === 0 ? (
                <p className="empty-state">No transactions found.</p>
            ) : (
                <div className="table-wrap">
                    <table className="data-table">
                        <thead>
                            <tr>
                                <th>Transaction ID</th>
                                <th>Type</th>
                                <th className="num">Amount</th>
                                <th>Date</th>
                                <th>Status</th>
                            </tr>
                        </thead>
                        <tbody>
                            {account.transactions.map((transaction) => (
                                <tr key={transaction.transaction_id}>
                                    <td className="mono">{transaction.transaction_id}</td>
                                    <td>{transaction.transaction_type}</td>
                                    <td className="num">{transaction.currency ? `${transaction.currency} ` : ''}{transaction.amount}</td>
                                    <td>{formatDate(transaction.transaction_date)}</td>
                                    <td>
                                        <span className={`badge badge-${String(transaction.status).toLowerCase()}`}>{transaction.status}</span>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}
        </section>
    )
}

function ClientTransactions() {
    const { clientId } = useParams()
    const navigate = useNavigate()
    const [state, setState] = useState({ clientId: null, error: '', data: null })

    useEffect(() => {
        let current = true
        loadClientTransactions(clientId).then(
            (data) => { if (current) setState({ clientId, error: '', data }) },
            (error) => { if (current) setState({ clientId, error: loadProblem(error), data: null }) },
        )
        return () => { current = false }
    }, [clientId])

    const loading = state.clientId !== clientId
    const client = state.data?.client

    return (
        <div className="page">
            <div className="back-row">
                <button onClick={() => navigate(-1)}>
                    Back
                </button>
            </div>

            {loading && <p className="empty-note" role="status">Loading…</p>}
            {!loading && state.error && (
                <div className="card empty-state">
                    <h1>{state.error}</h1>
                </div>
            )}

            {!loading && client && (
                <>
                    <div className="card client-header">
                        <div className="client-avatar" aria-hidden="true">
                            {client.first_name.charAt(0)}{client.last_name.charAt(0)}
                        </div>
                        <div>
                            <h1>{client.first_name} {client.last_name}</h1>
                            <p>{client.email}</p>
                            <p><Link to={`/clients/${encodeURIComponent(clientId)}`}>Open client profile</Link></p>
                        </div>
                    </div>

                    {state.data.accounts.length === 0 ? (
                        <section className="card">
                            <h2>Transactions</h2>
                            <p className="empty-state">This client has no accounts, so no transactions.</p>
                        </section>
                    ) : (
                        state.data.accounts.map((account) => <AccountTransactions key={account.id} account={account} />)
                    )}
                </>
            )}
        </div>
    )
}

export default ClientTransactions
