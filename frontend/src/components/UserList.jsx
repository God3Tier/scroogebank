import { useMemo, useState } from 'react'
import { useAuth } from 'react-oidc-context'

import { listClients, reassignClients } from '../api/clients.js'
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
  const [picking, setPicking] = useState(null)
  const [pickedClients, setPickedClients] = useState([])
  const [selectedClientIds, setSelectedClientIds] = useState([])
  const [pickLoading, setPickLoading] = useState(false)
  const [pickError, setPickError] = useState('')
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

  async function loadAgentClients(user) {
    const collected = []
    let page = 0

    while (true) {
      const result = await listClients({ page, size: 100 })
      collected.push(...result.content.filter((client) => client.assigned_agent_id === user.idpSub))
      if (page + 1 >= (result.totalPages || 1)) break
      page += 1
    }
    return collected
  }

  async function openPickClients(user) {
    setMoved('')
    setProblem('')
    setEditingId(null)
    setReassigning(null)
    setPicking(user.id)
    setPickedClients([])
    setSelectedClientIds([])
    setPickLoading(true)
    setPickError('')
    setReceivers({ loading: true, users: [] })
    try {
      const clients = await loadAgentClients(user)
      setPickedClients(clients)
    } catch {
      setPickError('The clients could not be loaded. Try again.')
    } finally {
      setPickLoading(false)
    }
    try {
      const page = await listUsers({ role: 'AGENT', enabled: true, size: RECEIVER_LIMIT })
      setReceivers({ loading: false, users: page.content })
    } catch {
      setReceivers({ loading: false, users: [] })
      setPickError('The agents could not be loaded. Try again.')
    }
  }

  function closePickClients() {
    setPicking(null)
    setPickedClients([])
    setSelectedClientIds([])
    setPickLoading(false)
    setPickError('')
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
              {user.role === 'ROOT_ADMIN' ? 'Root Admin' : `${user.firstName} ${user.lastName}`} ({user.email})
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
            {agentList && reassigning !== user.id && picking !== user.id && (
              <button type="button" onClick={() => openReassign(user)}>Reassign clients</button>
            )}
            {agentList && reassigning !== user.id && picking !== user.id && (
              <button type="button" onClick={() => openPickClients(user)}>Reassign selected clients</button>
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
            {agentList && picking === user.id && (
              <div className="reassign-picker">
                {pickLoading && <p className="empty-note">Loading clients…</p>}
                {pickError && <p className="form-error" role="alert">{pickError}</p>}
                {!pickLoading && !pickError && pickedClients.length === 0 && (
                  <p className="empty-note">This agent has no clients to reassign.</p>
                )}
                {!pickLoading && pickedClients.length > 0 && (
                  <div className="reassign-picker__clients">
                    <label>
                      Clients to reassign
                      <select
                        multiple
                        size={Math.min(8, pickedClients.length)}
                        value={selectedClientIds}
                        onChange={(event) => setSelectedClientIds(Array.from(event.target.selectedOptions, (option) => option.value))}
                      >
                        {pickedClients.map((client) => (
                          <option key={client.client_id} value={client.client_id}>
                            {client.first_name} {client.last_name}
                          </option>
                        ))}
                      </select>
                    </label>
                    <p className="section-note">Hold Ctrl/Cmd to select more than one client.</p>
                    {!receivers.loading && (
                      <ReassignForm
                        agents={receivers.users.filter((agent) => agent.enabled && agent.idpSub !== user.idpSub)}
                        submitLabel={`Reassign ${selectedClientIds.length || 'selected'} client${selectedClientIds.length === 1 ? '' : 's'}`}
                        onSubmit={async (receiver) => {
                          if (selectedClientIds.length === 0) return
                          const result = await reassignClients({ toUserId: receiver.id, clientIds: selectedClientIds })
                          setPickedClients((current) => current.filter((item) => !selectedClientIds.includes(item.client_id)))
                          setSelectedClientIds([])
                          setMoved(`${result.reassigned} client${result.reassigned === 1 ? '' : 's'} moved to ${agentName(receiver)}, with their accounts.`)
                        }}
                        onCancel={closePickClients}
                      />
                    )}
                  </div>
                )}
                <div className="form-actions">
                  <button type="button" onClick={closePickClients}>Done</button>
                </div>
              </div>
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
