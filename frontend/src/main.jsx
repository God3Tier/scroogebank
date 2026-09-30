import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import ClientHealthMonitor from './ClientHealthMonitor.jsx'
import './styles.css'

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <ClientHealthMonitor />
  </StrictMode>,
)
