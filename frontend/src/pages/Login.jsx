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
            navigate('agent')
            return
        }

        setError('Invalid email or password')
    }

    return (
        <div className='login-container'>
            <h1>Login</h1>
            <form onSubmit={handleLogin}>
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
                    <p>{error}</p>
                )}
                <button type='submit'>Login</button>
            </form>
        </div>
    );
}

export default Login;