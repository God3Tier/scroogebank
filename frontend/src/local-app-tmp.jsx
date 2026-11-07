// TEMPORARY local page. The whole app runs against local services: the alerts service on
// localhost:8084, a local stand-in for the CRM (users, clients, accounts) on localhost:9100, and a
// fake sign-in server on localhost:9099, all through the vite proxy. Nothing here is mocked in the
// browser; a user you create in the app can be signed in as from the bar at the bottom-right.
// Not committed.
import { useEffect, useMemo, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { AuthContext } from 'react-oidc-context'
import { MemoryRouter } from 'react-router-dom'
import { User } from 'oidc-client-ts'
import '@fontsource-variable/inter'
import '@fontsource-variable/material-symbols-outlined'
import './styles/index.css'
import './styles/dashboard.css'
import './styles/engagement.css'

import App from './App.jsx'
import NavBar from './components/NavBar.jsx'
import { userManager } from './auth/session.js'

const noop = async () => {}
const unsub = () => () => {}
const FALLBACK = [{ sub: 'syn-agent-001', role: 'AGENT', label: 'Alice Agent' }]

async function signInAs(identity) {
  const reply = await fetch(`http://localhost:9099/token?role=${identity.role}&sub=${identity.sub}`)
  const body = await reply.json()
  const user = new User({
    access_token: body.access_token, token_type: 'Bearer', expires_at: body.profile.exp,
    profile: { sub: identity.sub, email: `${identity.sub}@example.test`, name: identity.label, 'cognito:groups': [identity.role] },
  })
  await userManager.storeUser(user)
  return user
}

async function loadIdentities() {
  try {
    const reply = await fetch('http://localhost:9100/_identities')
    return await reply.json()
  } catch {
    return FALLBACK
  }
}

function Root() {
  const [identities, setIdentities] = useState(FALLBACK)
  const [sub, setSub] = useState(new URLSearchParams(location.search).get('as') === 'admin' ? 'syn-admin-001' : 'syn-agent-001')
  const [user, setUser] = useState(null)
  const [problem, setProblem] = useState('')
  const identity = identities.find((item) => item.sub === sub) || identities[0]

  useEffect(() => { loadIdentities().then(setIdentities) }, [])
  // Refresh the list when it is opened, so a user created a moment ago appears.
  const refresh = () => loadIdentities().then(setIdentities)

  useEffect(() => {
    let current = true
    setUser(null)
    setProblem('')
    signInAs(identity).then(
      (signedIn) => { if (current) setUser(signedIn) },
      () => { if (current) setProblem('Cannot reach the fake sign-in server on localhost:9099. Start the backend first.') },
    )
    return () => { current = false }
  }, [identity.sub, identity.role])

  const value = useMemo(() => user && ({
    isAuthenticated: true, isLoading: false, user, error: undefined, activeNavigator: undefined,
    signinRedirect: noop, signoutRedirect: noop, removeUser: noop, signinSilent: async () => user,
    events: { addUserLoaded: unsub, addUserUnloaded: unsub, addAccessTokenExpiring: unsub },
  }), [user])
  const start = new URLSearchParams(location.search).get('start') || (identity.role === 'ADMIN' ? '/admin' : '/agent')

  return (
    <>
      {value ? (
        <AuthContext.Provider value={value}>
          <MemoryRouter key={sub} initialEntries={[start]}>
            <div className="app-shell">
              <NavBar />
              <main className="main"><App /></main>
            </div>
          </MemoryRouter>
        </AuthContext.Provider>
      ) : (
        <p style={{ padding: 24 }}>{problem || 'Signing in to the local fake sign-in server…'}</p>
      )}
      <div style={{ position: 'fixed', right: 16, bottom: 16, zIndex: 9999, display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap',
        maxWidth: 560, background: '#111827', color: '#fff', padding: '8px 12px', borderRadius: 10, boxShadow: '0 4px 16px rgba(0,0,0,.3)', font: '13px sans-serif' }}>
        <span>Local, signed in as:</span>
        {identities.map((item) => (
          <button key={item.sub} type="button" onClick={() => setSub(item.sub)} aria-pressed={sub === item.sub}
            style={{ padding: '4px 10px', borderRadius: 6, border: '1px solid #fff', cursor: 'pointer',
              background: sub === item.sub ? '#fff' : 'transparent', color: sub === item.sub ? '#111827' : '#fff' }}>
            {item.label} ({item.role === 'ADMIN' ? 'admin' : 'agent'})
          </button>
        ))}
        <button type="button" onClick={refresh} title="Reload the list of users"
          style={{ padding: '4px 8px', borderRadius: 6, border: '1px dashed #fff', cursor: 'pointer', background: 'transparent', color: '#fff' }}>↻</button>
      </div>
    </>
  )
}

createRoot(document.getElementById('root')).render(<Root />)
