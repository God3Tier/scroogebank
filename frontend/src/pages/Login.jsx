import { useState } from 'react'
import { useNavigate } from 'react-router-dom'

import admins from '../data/admins.js'
import agents from '../data/agents.js'

function Login() {
    const navigate = useNavigate()
    const [email, setEmail] = useState('')
    const [password, setPassword] = useState('')
    const [error, setError] = useState ('')

    function handleLogin(event){
        event.preventDefault()

        const admin = admins.find(
            (admin) => 
                admin.email === email &&
                admin.password === password
        )

        if(admin) {
            navigate('/admin')
            return
        }

        const agent = agents.find(
            (agent) =>
                agent.email === email &&
                agent.password === password
        )
        if(agent) {
            navigate('/agent')
            return
        }

        setError('Invalid email or password')
    }

    return (
        <div className='login-page'>
        <div className='login-container'>
            <div className='login-header'>
                <div className='login-logo'>
                    <span className='material-symbols-outlined' aria-hidden='true'>account_balance</span>
                </div>
                <h1>Login</h1>
                <p>Scrooge Global staff access</p>
            </div>
            <form className='stack-form' onSubmit={handleLogin}>
                <label>Email:
                    <input
                        type='email'
                        value={email}
                        onChange={(event) => setEmail(event.target.value)}
                    />
                </label>
                <label>Password:
                    <input
                        type='password'
                        value={password}
                        onChange={(event) => setPassword(event.target.value)}
                    />
                </label>
                {error && (
                    <p className='error-text' role='alert'>{error}</p>
                )}
                <button type='submit'>Login</button>
            </form>
        </div>
        </div>
    );
}

export default Login;