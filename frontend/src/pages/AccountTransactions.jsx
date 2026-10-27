import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'

import { listAccountTransactions, listAccounts } from '../api/accounts.js'
import { getClient } from '../api/clients.js'
import AccountTransactionTable from '../components/AccountTransactionTable.jsx'
import { Pager } from '../components/ListFooter.jsx'
import { useArrayPages } from '../components/paging.js'
import ListPage from './ListPage.jsx'

const NO_TRANSACTIONS = []

function loadProblem(error) {
  // client-service and account-service answer 404 for another agent's client too.
  if (error.status === 404) return 'Account not found.'
  return 'The transactions could not be loaded. Try again.'
}

// The account comes from the client's own accounts, so an account ID that is
// not the client's shows as not found rather than under the wrong name.
async function loadAccount(clientId, accountId) {
  const [client, accounts, transactions] = await Promise.all([
    getClient(clientId), listAccounts(clientId), listAccountTransactions(accountId),
  ])
  const account = accounts.find((item) => item.id === accountId)
  if (!account) throw Object.assign(new Error('Account not found'), { status: 404 })
  return { client, account, transactions }
}

// All of one account's transactions, newest first, ten a page; opened from
// the account's View all link. user-service sends them all at once.
function AccountTransactions() {
  const { clientId, accountId } = useParams()
  const key = `${clientId}/${accountId}`
  const [state, setState] = useState({ key: null, error: '', data: null })

  useEffect(() => {
    let current = true
    loadAccount(clientId, accountId).then(
      (data) => { if (current) setState({ key, error: '', data }) },
      (error) => { if (current) setState({ key, error: loadProblem(error), data: null }) },
    )
    return () => { current = false }
  }, [clientId, accountId, key])

  const loading = state.key !== key
  const pages = useArrayPages(state.data?.transactions ?? NO_TRANSACTIONS)
  const { client, account } = state.data ?? {}

  return (
    <ListPage
      title={account ? `${account.account_type} account ${account.id.slice(0, 8)}` : 'Account transactions'}
      subtitle={client ? `${client.first_name} ${client.last_name}'s transactions, newest first` : 'Newest first'}
      back={`/agent/client/${encodeURIComponent(clientId)}/transactions`}
      backLabel="Back to accounts"
    >
      <section className="card card-flush">
        <h2>Transactions</h2>
        {loading && <p className="empty-note" role="status">Loading…</p>}
        {!loading && state.error && <p className="form-error" role="alert">{state.error}</p>}
        {!loading && state.data && (pages.rows.length === 0 ? (
          <p className="empty-state">No transactions found.</p>
        ) : (
          <>
            <AccountTransactionTable transactions={pages.rows} />
            <Pager list={pages} label="Transaction pages" />
          </>
        ))}
      </section>
    </ListPage>
  )
}

export default AccountTransactions
