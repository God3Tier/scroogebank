import StaffAccessTicker from "../components/StaffAccessTicker";

import { useNavigate } from 'react-router-dom';

function Home() {
  const navigate = useNavigate();

  return (
    
    <div className="page">
      <StaffAccessTicker />
      <h1/>
      <div className="hero">
        <h1>Scrooge Global Staff Portal</h1>
        <p>Sign in to continue to the workspace assigned to your account.</p>
        <button type="button" onClick={() => navigate('/login')}>
          Go To Login →
        </button>
      </div>
    </div>
  )
}

export default Home;
