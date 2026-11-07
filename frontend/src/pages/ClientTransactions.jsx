import { useEffect, useState } from 'react'
import { useAuth } from 'react-oidc-context'
import { Link, useParams } from 'react-router-dom'

import { deleteAccount, listAccountTransactions, listAccounts, updateAccountStatus } from '../api/accounts.js'
import { getClient } from '../api/clients.js'
import HoldingsEditor from '../components/HoldingsEditor.jsx'
import { ADMIN, homeFor, inAnyGroup } from '../auth/roles.js'
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

function deleteProblem(error) {
    if (error.status === 404) return 'This account is no longer available.'
    return 'The account could not be deleted. Try again.'
}

function loadProblem(error) {
    // client-service answers 404 for another agent's client too.
    if (error.status === 404) return 'Client not found.'
    return 'The client could not be loaded. Try again.'
}

// A client's transactions, grouped by account: account-service lists the
// client's accounts, and transaction-service reads each account's transactions
// after checking the caller may (ADR-0035, ADR-0064).
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

// Deletes the account, a soft delete (ADR-0036): it disappears from every list
// but its row and transactions are kept. Agents only, on their own accounts,
// whether or not the client is verified; admins only read accounts (ADR-0059).
// A client cannot be deleted while it has accounts that are not deleted.
function DeleteAccount({ account, onDeleted }) {
    const [deleting, setDeleting] = useState({ busy: false, error: '' })

    async function handleDelete() {
        const name = `${account.account_type} account ${account.id.slice(0, 8)}`
        if (!window.confirm(`Delete the ${name}? It will no longer be shown. This cannot be undone.`)) return
        setDeleting({ busy: true, error: '' })
        try {
            await deleteAccount(account.id)
        } catch (error) {
            setDeleting({ busy: false, error: deleteProblem(error) })
            return
        }
        onDeleted(account.id)
    }

    return (
        <div className="account-delete">
            <button type="button" onClick={handleDelete} disabled={deleting.busy}>
                {deleting.busy ? 'Deleting…' : 'Delete account'}
            </button>
            {deleting.error && <p className="form-error" role="alert">{deleting.error}</p>}
        </div>
    )
}

// The account's newest ten transactions, and a link to all of them when there
// are more.
function AccountTransactions({ clientId, account, statusMode, onStatusChanged, onDeleted }) {
    const [editingHoldings, setEditingHoldings] = useState(false)
    // Agents record holdings on investment accounts; admins are read-only (ADR-0066).
    const canRecordHoldings = statusMode !== 'view' && String(account.account_type).toUpperCase() === 'INVESTMENT'
    return (
        <section className="card card-flush">
            <h2>
                {account.account_type} account <span className="mono" title={account.id}>{account.id.slice(0, 8)}</span>
            </h2>
            <AccountStatus account={account} mode={statusMode} onChanged={onStatusChanged} />
            {statusMode !== 'view' && <DeleteAccount account={account} onDeleted={onDeleted} />}
            {canRecordHoldings && (
                <div className="holdings-panel">
                    <button type="button" aria-expanded={editingHoldings} onClick={() => setEditingHoldings(!editingHoldings)}>
                        {editingHoldings ? 'Hide holdings' : 'Record holdings'}
                    </button>
                    {editingHoldings && <HoldingsEditor accountId={account.id} />}
                </div>
            )}
            {account.transactions.length === 0 ? (
                <p className="empty-state">No transactions found.</p>
            ) : (
                <>
                    <AccountTransactionTable transactions={account.transactions.slice(0, PAGE_SIZE)} />
                    {account.transactions.length > PAGE_SIZE && (
                        <ViewAll
                            to={`/agent/client/${encodeURIComponent(clientId)}/accounts/${encodeURIComponent(account.id)}/transactions`}
                            label="View all transactions"
                        />
                    )}
                </>
            )}
        </section>
    )
}

function ClientTransactions() {
    const { clientId } = useParams()
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

    function handleDeleted(accountId) {
        setState((current) => ({
            ...current,
            data: { ...current.data, accounts: current.data.accounts.filter((account) => account.id !== accountId) },
        }))
    }

    return (
        <div className="page">
            {/* Home, not the browser's last page, which may be one of these accounts' transaction pages. */}
            <div className="back-row"><Link to={homeFor(auth.user)}>Back</Link></div>

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
                            <AccountTransactions key={account.id} clientId={clientId} account={account} statusMode={statusMode} onStatusChanged={handleStatusChanged} onDeleted={handleDeleted} />
                        ))
                    )}
                </>
            )}
        </div>
    )
}

export default ClientTransactions
