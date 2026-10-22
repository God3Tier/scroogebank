import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { AuthProvider } from 'react-oidc-context'
import { BrowserRouter as Router } from 'react-router-dom'
import './styles/index.css'
import './styles/dashboard.css'
import './styles/engagement.css'

import App from './App.jsx'
import NavBar from './components/NavBar.jsx'
import { userManager } from './auth/session.js'

// AuthProvider completes a sign-in only on the callback page, so a ?code= on
// any other URL is ignored.
createRoot(document.getElementById('root')).render(
  <StrictMode>
    <AuthProvider userManager={userManager} skipSigninCallback={window.location.pathname !== '/auth/callback'}>
      <Router>
        <div className="app-shell">
          <NavBar />
          <main className="main">
            <App />
          </main>
        </div>
      </Router>
    </AuthProvider>
  </StrictMode>
)
