function formatDate(value) {
    return new Date(value).toLocaleDateString('en-SG', { dateStyle: 'medium' })
}

// One account's transactions, newest first as user-service sends them.
function AccountTransactionTable({ transactions }) {
    return (
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
                    {transactions.map((transaction) => (
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
    )
}

export default AccountTransactionTable
