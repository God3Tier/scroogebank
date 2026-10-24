import { useEffect, useState } from 'react'
import { useAuth } from 'react-oidc-context'
import { Link, useNavigate, useParams } from 'react-router-dom'

import { listAccountTransactions, listAccounts, updateAccountStatus } from '../api/accounts.js'
import { getClient } from '../api/clients.js'
import { ADMIN, inAnyGroup } from '../auth/roles.js'
import AccountTransactionTable from '../components/AccountTransactionTable.jsx'
import { ViewAll } from '../components/ListFooter.jsx'
import { PAGE_SIZE } from '../components/paging.js'

// account-service's four statuses. An agent may move an account of a verified
// client to any of them, in any direction (ADR-0050).
const ACCOUNT_STATUSES = [
    ['ACTIVE', 'Active'],
    ['INACTIVE', 'Inactive'],
    ['PENDING', 'Pending'],
    ['CLOSED', 'Closed'],
]
const STATUS_LABELS = Object.fromEntries(ACCOUNT_STATUSES)

// Clients verified before ADR-0041 hold "verified" in lower case.
function isVerified(client) {
    return String(client?.verification_status || '').toUpperCase() === 'VERIFIED'
}

function statusChangeProblem(error) {
    if (error.status === 403) return "This client isn't verified, so the status can't be changed."
    if (error.status === 404) return 'This account is no longer available.'
    if (error.status === 503) return 'The client could not be checked just now. Try again shortly.'
    return 'The status could not be changed. Try again.'
}

function loadProblem(error) {
    // client-service answers 404 for another agent's client too.
    if (error.status === 404) return 'Client not found.'
    return 'The client could not be loaded. Try again.'
}

// A client's transactions, grouped by account: account-service lists the
// client's accounts, and user-service reads each account's transactions after
// checking the caller may (ADR-0006, ADR-0035).
async function loadClientTransactions(clientId) {
    const [client, accounts] = await Promise.all([getClient(clientId), listAccounts(clientId)])
    const transactions = await Promise.all(accounts.map((account) => listAccountTransactions(account.id)))
    return { client, accounts: accounts.map((account, i) => ({ ...account, transactions: transactions[i] })) }
}

// An account's status. The agent of a verified client can change it; an
// unverified client's accounts stay as they are until the client is
// verified, and admins do not manage client accounts (ADR-0050).
function AccountStatus({ account, mode, onChanged }) {
    const current = String(account.status).toUpperCase()
    const [choice, setChoice] = useState(current)
    const [saving, setSaving] = useState({ busy: false, error: '' })

    async function handleSave(event) {
        event.preventDefault()
        setSaving({ busy: true, error: '' })
        try {
            await updateAccountStatus(account.id, choice)
        } catch (error) {
            setSaving({ busy: false, error: statusChangeProblem(error) })
            return
        }
        setSaving({ busy: false, error: '' })
        onChanged(account.id, choice)
    }

    return (
        <div className="account-status">
            <span>
                Status: <span className={`badge badge-${current.toLowerCase()}`}>{STATUS_LABELS[current] || account.status}</span>
            </span>
            {mode === 'change' && (
                <form className="account-status-form" onSubmit={handleSave}>
                    <select
                        aria-label={`New status for account ${account.id.slice(0, 8)}`}
                        value={choice}
                        onChange={(event) => setChoice(event.target.value)}
                    >
                        {ACCOUNT_STATUSES.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
                    </select>
                    <button type="submit" disabled={saving.busy || choice === current}>
                        {saving.busy ? 'Saving…' : 'Save status'}
                    </button>
                </form>
            )}
            {mode === 'unverified' && <span className="section-note">Verify this client to change account statuses.</span>}
            {saving.error && <p className="form-error" role="alert">{saving.error}</p>}
        </div>
    )
}

// The account's newest ten transactions, and a link to all of them.
function AccountTransactions({ clientId, account, statusMode, onStatusChanged }) {
    return (
        <section className="card card-flush">
            <h2>
                {account.account_type} account <span className="mono" title={account.id}>{account.id.slice(0, 8)}</span>
            </h2>
            <AccountStatus account={account} mode={statusMode} onChanged={onStatusChanged} />
            {account.transactions.length === 0 ? (
                <p className="empty-state">No transactions found.</p>
            ) : (
                <>
                    <AccountTransactionTable transactions={account.transactions.slice(0, PAGE_SIZE)} />
                    <ViewAll
                        to={`/agent/client/${encodeURIComponent(clientId)}/accounts/${encodeURIComponent(account.id)}/transactions`}
                        label="View all transactions"
                    />
                </>
            )}
        </section>
    )
}

function ClientTransactions() {
    const { clientId } = useParams()
    const navigate = useNavigate()
    const auth = useAuth()
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
    // Agents manage client accounts; admins only see them (requirements Q&A UM18).
    const statusMode = inAnyGroup(auth.user, [ADMIN]) ? 'view' : isVerified(client) ? 'change' : 'unverified'

    function handleStatusChanged(accountId, status) {
        setState((current) => ({
            ...current,
            data: {
                ...current.data,
                accounts: current.data.accounts.map((account) => account.id === accountId ? { ...account, status } : account),
            },
        }))
    }

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
                        state.data.accounts.map((account) => (
                            <AccountTransactions key={account.id} clientId={clientId} account={account} statusMode={statusMode} onStatusChanged={handleStatusChanged} />
                        ))
                    )}
                </>
            )}
        </div>
    )
}

export default ClientTransactions
