// Words for client reassignment (ADR-0060).

// What a refused reassignment means to the admin. Nothing moves when any of
// these is answered.
export function reassignProblem(error) {
  if (error.status === 400) return 'Clients can only go to another enabled agent.'
  if (error.status === 404) return 'That agent or client no longer exists. Reload the page.'
  if (error.status === 409) return 'This agent has too many clients to move at once.'
  if (error.status === 503) return 'A service is unavailable, so nothing was moved. Try again.'
  return 'The clients could not be reassigned. Try again.'
}

export function agentName(user) {
  return `${user.firstName || ''} ${user.lastName || ''}`.trim() || user.email
}

export function movedText(count, receiver) {
  if (count === 0) return `Nothing to move: ${agentName(receiver)} already has these clients.`
  return `${count} ${count === 1 ? 'client' : 'clients'} moved to ${agentName(receiver)}, with their accounts.`
}
