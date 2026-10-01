import { useParams, useNavigate } from "react-router-dom"

// Below imports are seeded data, testing purposes
import clients from '../data/clients.js'
import transactions from '../data/transactions.js'

function ClientTransactions() {

    // Variables and States
    const {clientId} = useParams()
    const navigate = useNavigate()

    const client = clients.find(
        (client) => client.clientId === Number(clientId)
    )

    const clientTransactions = transactions.filter(
        (transaction) => transaction.clientId === Number(clientId)
    )

    if(!client) {
        return (
            <div className="page">
                <div className="card empty-state">
                    <h1>Client not found</h1>
                </div>
            </div>
        )
    }

    return (
        <div className="page">
            <div className="back-row">
                <button onClick={() => navigate('/agent')}>
                    Back
                </button>
            </div>

            <div className="card client-header">
                <div className="client-avatar" aria-hidden="true">
                    {client.firstName.charAt(0)}{client.lastName.charAt(0)}
                </div>
                <div>
                    <h1>{client.firstName} {client.lastName}</h1>

                    <p>{client.email}</p>
                </div>
            </div>

            <section className="card card-flush">
                <h2>Transactions</h2>

                {clientTransactions.length === 0 ?
                    (<p className="empty-state">No transactions found.</p>)
                    :(
                        <ul className="row-list plain-rows">
                            {clientTransactions.map((transaction) => (
                                <li key={transaction.transactionId}>
                                    Transaction ID: {transaction.transactionId}, Amount: {transaction.amount}, Status: {transaction.status}
                                </li>
                            ))}
                        </ul>
                    )}
            </section>
        </div>
    )
}

export default ClientTransactions;
