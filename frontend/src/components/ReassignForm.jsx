import { useState } from 'react'

import { agentName, reassignProblem } from './reassignText.js'

// Picks the agent who receives the clients and confirms the move. agents are
// the enabled agents who may receive them; onSubmit gets the chosen user and
// returns a promise.
function ReassignForm({ agents, submitLabel, onSubmit, onCancel }) {
  const [toUserId, setToUserId] = useState('')
  const [saving, setSaving] = useState({ busy: false, error: '' })

  async function handleSubmit(event) {
    event.preventDefault()
    const receiver = agents.find((agent) => agent.id === toUserId)
    if (!receiver) return
    setSaving({ busy: true, error: '' })
    try {
      await onSubmit(receiver)
    } catch (error) {
      setSaving({ busy: false, error: reassignProblem(error) })
    }
  }

  return (
    <form className="reassign-form" onSubmit={handleSubmit}>
      <label>
        New agent
        <select required value={toUserId} onChange={(event) => setToUserId(event.target.value)}>
          <option value="">Select an agent</option>
          {agents.map((agent) => (
            <option key={agent.id} value={agent.id}>{agentName(agent)} ({agent.email})</option>
          ))}
        </select>
      </label>
      {agents.length === 0 && <p className="section-note">There is no other enabled agent to receive them.</p>}
      {saving.error && <p className="form-error" role="alert">{saving.error}</p>}
      <div className="form-actions">
        <button type="submit" disabled={!toUserId || saving.busy}>{saving.busy ? 'Reassigning…' : submitLabel}</button>
        <button type="button" onClick={onCancel} disabled={saving.busy}>Cancel</button>
      </div>
    </form>
  )
}

export default ReassignForm
