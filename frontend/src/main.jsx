import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter as Router } from 'react-router-dom'
import './styles/index.css'
import './styles/dashboard.css'

import App from './App.jsx'
import NavBar from './components/NavBar.jsx'

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <Router>
      <div className="app-shell">
        <NavBar />
        <main className="main">
          <App />
        </main>
      </div>
    </Router>
  </StrictMode>
)
