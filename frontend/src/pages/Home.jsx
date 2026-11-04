import StaffAccessTicker from "../components/StaffAccessTicker";

import { useNavigate } from 'react-router-dom';

function Home() {
  const navigate = useNavigate();

  return (
    <div className="page home-page">
      <StaffAccessTicker />

      <div className="hero home-hero">
        <div>
          <h1>Scrooge Global Staff Portal</h1>
          <p>Sign in to continue to the workspace assigned to your account.</p>
        </div>

        <button type="button" onClick={() => navigate('/login')}>
          Go to login
          <span className="material-symbols-outlined" aria-hidden="true">
            arrow_forward
          </span>
        </button>
      </div>

      <section className="home-workspaces">
        <h2>Workspaces</h2>

        <div className="home-workspace-grid">
          <article className="home-workspace-card">
            <span className="material-symbols-outlined" aria-hidden="true">
              shield
            </span>
            <h3>Admin workspace</h3>
            <p>Manage CRM users, permissions, and settings.</p>
          </article>

          <article className="home-workspace-card">
            <span className="material-symbols-outlined" aria-hidden="true">
              headset_mic
            </span>
            <h3>Agent workspace</h3>
            <p>Work with customer records and follow-ups.</p>
          </article>
        </div>
      </section>
    </div>
  )
}

export default Home;
