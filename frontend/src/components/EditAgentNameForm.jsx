import { useState } from 'react'

import { agentName } from './reassignText.js'

function updateProblem(error) {
  if (error.status === 403) return 'You do not have permission to update this agent.'
  if (error.status === 404) return 'This agent could not be found. Reload the list and try again.'
  return 'The name could not be updated. Your changes have been kept. Try again.'
}

function EditAgentNameForm({ user, onSubmit, onCancel }) {
  const [firstName, setFirstName] = useState(user.firstName)
  const [lastName, setLastName] = useState(user.lastName)
  const [saving, setSaving] = useState({ busy: false, error: '' })

  async function handleSubmit(event) {
    event.preventDefault()
    if (saving.busy) return
    const names = { firstName: firstName.trim(), lastName: lastName.trim() }
    if (!names.firstName || !names.lastName) {
      setSaving({ busy: false, error: 'Enter both a first name and a last name.' })
      return
    }
    setSaving({ busy: true, error: '' })
    try {
      await onSubmit(names)
    } catch (error) {
      setSaving({ busy: false, error: updateProblem(error) })
    }
  }

  return (
    <form className="agent-name-form" aria-label={`Edit name for ${agentName(user)}`} autoComplete="off" onSubmit={handleSubmit}>
      <label>
        First name
        <input type="text" required maxLength={50} value={firstName} disabled={saving.busy}
          onChange={(event) => setFirstName(event.target.value)} />
      </label>
      <label>
        Last name
        <input type="text" required maxLength={50} value={lastName} disabled={saving.busy}
          onChange={(event) => setLastName(event.target.value)} />
      </label>
      {saving.error && <p className="form-error" role="alert">{saving.error}</p>}
      <div className="form-actions">
        <button type="submit" disabled={saving.busy}>{saving.busy ? 'Saving…' : 'Save name'}</button>
        <button type="button" disabled={saving.busy} onClick={onCancel}>Cancel</button>
      </div>
    </form>
  )
}

export default EditAgentNameForm
