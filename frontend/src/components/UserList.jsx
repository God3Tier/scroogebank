import { useMemo, useState } from 'react'
import { useAuth } from 'react-oidc-context'

import { reassignClients } from '../api/clients.js'
import { disableUser, listUsers, resetPassword, updateUser } from '../api/users.js'
import EditAgentNameForm from './EditAgentNameForm.jsx'
import { Pager, ViewAll } from './ListFooter.jsx'
import ReassignForm from './ReassignForm.jsx'
import { numberedSource, usePagedList } from './paging.js'
import { agentName, movedText } from './reassignText.js'

const LIST_PATHS = { ADMIN: '/admin/admins', AGENT: '/admin/agents' }
const LIST_NAMES = { ADMIN: 'admins', AGENT: 'agents' }
// Every agent who may receive clients, for the reassign form.
const RECEIVER_LIMIT = 100

function disableProblem(error) {
  if (error.status === 403) return 'This user cannot be disabled.'
  return 'The user could not be disabled. Try again.'
}

// The admins or agents, alphabetically by last name as user-service sorts
// them, with Disable. Users are never deleted, and the root admin cannot be
// disabled (user-service answers 403). Each agent, enabled or not, also has
// Reassign clients, which moves all their clients to another enabled agent
// (requirements Q&A UM17, ADR-0060). On Admin Home the first ten show; paged,
// the whole list does, ten at a time.
function UserList({ role, title, paged = false }) {
  const source = useMemo(() => numberedSource(({ page, size }) => listUsers({ role, page, size })), [role])
  const list = usePagedList(source)
  const [problem, setProblem] = useState('')
  const [reassigning, setReassigning] = useState(null)
  const [receivers, setReceivers] = useState({ loading: false, users: [] })
  const [moved, setMoved] = useState('')
  const [resettingId, setResettingId] = useState(null)
  const [resetMessage, setResetMessage] = useState('')
  const [editingId, setEditingId] = useState(null)
  const [updatedMessage, setUpdatedMessage] = useState('')
  const auth = useAuth()
  const currentSub = auth.user?.profile?.sub

  function disableReason(user) {
    if (user.deletable === false) return 'The root admin cannot be disabled.'
    if (!currentSub) return 'Your signed-in identity is still loading.'
    if (user.idpSub === currentSub) return 'You cannot disable your own account.'
  }

  async function handleDisable(user) {
    if (disableReason(user)) return
    setProblem('')
    try {
      await disableUser(user.id)
      await list.reload()
    } catch (error) {
      setProblem(disableProblem(error))
    }
  }

  async function handleResetPassword(user) {
    if (role !== 'AGENT' || resettingId !== null) return
    setProblem('')
    setResetMessage('')
    setResettingId(user.id)
    try {
      await resetPassword(user.id)
      setResetMessage(`${agentName(user)}: password reset requested. Cognito will email a reset code.`)
    } catch (error) {
      setProblem(error.status === 403
        ? 'You do not have permission to reset this password.'
        : 'The password reset could not be requested. Try again.')
    } finally {
      setResettingId(null)
    }
  }

  function openEditName(user) {
    setProblem('')
    setUpdatedMessage('')
    setReassigning(null)
    setEditingId(user.id)
  }

  async function handleUpdateName(user, names) {
    await updateUser(user.id, names)
    setEditingId((current) => current === user.id ? null : current)
    setUpdatedMessage(`${names.firstName} ${names.lastName}: name updated.`)
    await list.reload()
  }

  // The receivers are every enabled agent, not only the ones on this page.
  async function openReassign(user) {
    setMoved('')
    setProblem('')
    setEditingId(null)
    setReassigning(user.id)
    setReceivers({ loading: true, users: [] })
    try {
      const page = await listUsers({ role: 'AGENT', enabled: true, size: RECEIVER_LIMIT })
      setReceivers({ loading: false, users: page.content })
    } catch {
      setReassigning(null)
      setProblem('The agents could not be loaded. Try again.')
    }
  }

  async function handleReassign(from, receiver) {
    const result = await reassignClients({ toUserId: receiver.id, fromUserId: from.id })
    setReassigning(null)
    setMoved(`${agentName(from)}: ${movedText(result.reassigned, receiver)}`)
  }

  const agentList = role === 'AGENT'
  const empty = !list.loading && !list.error && list.rows.length === 0
  return (
    <div className="card card-flush">
      <h2>{title}</h2>
      {list.loading && <p className="empty-note">Loading…</p>}
      {list.error && <p className="form-error" role="alert">Users could not be loaded.</p>}
      {problem && <p className="form-error" role="alert">{problem}</p>}
      {moved && <p className="success-message" role="status">{moved}</p>}
      {resetMessage && <p className="success-message" role="status">{resetMessage}</p>}
      {updatedMessage && <p className="success-message" role="status">{updatedMessage}</p>}
      {empty && <p className="empty-note">None yet.</p>}
      <ul className="row-list">
        {list.rows.map((user) => (
          <li key={user.id}>
            <span>
              {user.firstName} {user.lastName} ({user.email})
            </span>
            {agentList && (
              <button type="button" className="agent-name-edit" aria-expanded={editingId === user.id}
                disabled={editingId === user.id} onClick={() => openEditName(user)}>
                Edit name
              </button>
            )}
            {agentList && (
              <button type="button" disabled={resettingId !== null} onClick={() => handleResetPassword(user)}>
                {resettingId === user.id ? 'Requesting reset…' : 'Reset password'}
              </button>
            )}
            {agentList && reassigning !== user.id && (
              <button type="button" onClick={() => openReassign(user)}>Reassign clients</button>
            )}
            {user.enabled ? (
              <button 
                type="button" 
                disabled={Boolean(disableReason(user))}
                title={disableReason(user)}
                onClick={() => handleDisable(user)}
              >
                Disable
              </button>
            ) : (
              <span className="badge">Disabled</span>
            )}
            {agentList && editingId === user.id && (
              <EditAgentNameForm
                key={user.id}
                user={user}
                onSubmit={(names) => handleUpdateName(user, names)}
                onCancel={() => setEditingId(null)}
              />
            )}
            {agentList && reassigning === user.id && receivers.loading && <p className="empty-note">Loading agents…</p>}
            {agentList && reassigning === user.id && !receivers.loading && (
              <ReassignForm
                agents={receivers.users.filter((agent) => agent.enabled && agent.id !== user.id)}
                submitLabel={`Move all of ${agentName(user)}'s clients`}
                onSubmit={(receiver) => handleReassign(user, receiver)}
                onCancel={() => setReassigning(null)}
              />
            )}
          </li>
        ))}
      </ul>
      {!empty && paged && <Pager list={list} label={`${title} pages`} />}
      {!paged && list.hasNext && <ViewAll to={LIST_PATHS[role]} label={`View all ${LIST_NAMES[role]}`} />}
    </div>
  )
}

export default UserList
