import { useParams, useNavigate } from "react-router-dom"

{/* Below imports are seeded data, testing purposes */}
import clients from '../data/clients.js'
import transactions from '../data/transactions.js'

function ClientTransactions() {

    {/* Variables and States*/}
    const {clientId} = useParams()
    const navigate = useNavigate()

    const client = clients.find(
        (client) => client.clientId === Number(clientId)
    )

    const clientTransactions = transactions.filter(
        (transaction) => transaction.clientId === Number(clientId)
    )

    if(!client) {
        return <h1>Client not found</h1>
    }

    return (
        <div>
            <button onClick={() => navigate('/agent')}>
                Back
            </button>

            <h1>{client.firstName} {client.lastName}</h1>

            <p>{client.email}</p>

            <h2>Transactions</h2>

            {clientTransactions.length === 0 ? 
                (<p>No transactions found.</p>)
                :(
                    <ul>
                        {clientTransactions.map((transaction) => (
                            <li key={transaction.transactionId}>
                                Transaction ID: {transaction.transactionId}, Amount: {transaction.amount}, Status: {transaction.status}
                            </li>
                        ))}
                    </ul>
                )}
        </div>
    )
}

export default ClientTransactions;