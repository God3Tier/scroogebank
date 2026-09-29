import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter as Router } from 'react-router-dom'
import '@fontsource-variable/archivo'
import '@fontsource-variable/source-sans-3'
import '@fontsource/ibm-plex-mono/500.css'
import './styles/index.css'

import App from './App.jsx'
import NavBar from './components/NavBar.jsx'

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <Router>
      <NavBar />
      <App />
    </Router>
  </StrictMode>
)
