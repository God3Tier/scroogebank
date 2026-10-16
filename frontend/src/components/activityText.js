// Turns one activity feed entry into words. Entries carry IDs, never client
// names, because audit events hold no client personal data (ADR-0021 decision
// 3, ADR-0030). What an entry says depends on the service that recorded it:
// client-service and account-service (source CRM) record the route they
// served, user-service the kind of record and the user's role, and the
// Cognito triggers a user's own password reset.
//
// describeActivity returns:
//   text      what was done, in lower case, to follow the actor's name
//   icon      a Material Symbols name
//   tone      create, change, verify or remove, for the icon's colour
//   record    { label, id } of the record acted on, or null
//   note      extra detail, such as how many documents a verification used
//   badge     a warning to show beside the entry, or null
//   clientId  the client whose page the entry can open, or null

const CRM_ROUTES = {
  'POST /api/clients': { text: 'created a client profile', icon: 'person_add', tone: 'create', record: 'Client', opens: true },
  'PUT /api/clients/:clientId': { text: 'updated a client profile', icon: 'edit_note', tone: 'change', record: 'Client', opens: true },
  'DELETE /api/clients/:clientId': { text: 'deleted a client profile', icon: 'person_remove', tone: 'remove', record: 'Client' },
  'POST /api/clients/:clientId/verify': { text: 'verified a client', icon: 'verified_user', tone: 'verify', record: 'Client', opens: true, evidence: true },
  'POST /api/clients/:clientId/documents': { text: 'uploaded an identity document', icon: 'upload_file', tone: 'change', record: 'Client', opens: true },
  'POST /api/accounts': { text: 'opened an account', icon: 'account_balance', tone: 'create', record: 'Client', opens: true },
  // account-service records the account's ID in client_id on this route.
  'DELETE /api/accounts/:accountId': { text: 'closed an account', icon: 'do_not_disturb_on', tone: 'remove', record: 'Account' },
}

const GENERIC = {
  CREATE: { text: 'created a record', icon: 'add_circle', tone: 'create' },
  UPDATE: { text: 'updated a record', icon: 'edit_note', tone: 'change' },
  DELETE: { text: 'deleted a record', icon: 'delete', tone: 'remove' },
}

const ROLE_NAMES = { ADMIN: 'Admin', AGENT: 'Agent' }

export function describeActivity({ sourceService, action, payload }) {
  const details = payload && typeof payload === 'object' ? payload : {}
  const verb = String(action || '').toUpperCase()
  if (sourceService === 'CRM') return describeCrm(verb, details)
  if (sourceService === 'USER_MANAGEMENT') return describeUserChange(verb, details)
  if (sourceService === 'COGNITO_TRIGGERS' && verb === 'PASSWORD_RESET') {
    return activity({ text: 'reset their own password', icon: 'lock_reset', tone: 'change' })
  }
  return fallback(verb)
}

function describeCrm(verb, details) {
  const route = CRM_ROUTES[`${details.http_method} ${details.endpoint}`]
  if (!route) return fallback(verb)
  const id = details.client_id || null
  // Only a verification's documents are evidence; an upload names the one
  // document it stored.
  const documents = route.evidence && Array.isArray(details.document_ids) ? details.document_ids.length : 0
  return activity({
    ...route,
    record: id ? { label: route.record, id } : null,
    note: documents ? `with ${documents} ${documents === 1 ? 'document' : 'documents'}` : null,
    badge: route.evidence && details.without_document ? 'Without a document' : null,
    clientId: route.opens ? id : null,
  })
}

function describeUserChange(verb, details) {
  const id = details.resource_id || null
  if (details.resource_type === 'CORRUPTED_AUDIT_LOG') {
    if (verb === 'CREATE') return activity({ text: 'recorded a tamper finding', icon: 'gpp_maybe', tone: 'change', record: finding(id) })
    if (verb === 'DELETE') return activity({ text: 'deleted a tamper finding', icon: 'gpp_maybe', tone: 'remove', record: finding(id) })
    return fallback(verb)
  }

  const role = ROLE_NAMES[String(details.user_role || '').toUpperCase()]
  const record = id ? { label: 'User', id } : null
  switch (verb) {
    case 'CREATE':
      return activity({ text: role ? `created an ${role.toLowerCase()}` : 'created a user', icon: 'person_add', tone: 'create', record })
    case 'UPDATE':
      return activity({ text: "updated a user's name", icon: 'badge', tone: 'change', record })
    case 'ROLE_CHANGE':
      return activity({ text: role ? `changed a user's role to ${role}` : "changed a user's role", icon: 'manage_accounts', tone: 'change', record })
    case 'DISABLE':
      return activity({ text: 'disabled a user', icon: 'person_off', tone: 'remove', record })
    case 'PASSWORD_RESET':
      return activity({ text: "reset a user's password", icon: 'lock_reset', tone: 'change', record })
    default:
      return fallback(verb)
  }
}

function finding(id) {
  return id ? { label: 'Finding', id } : null
}

function fallback(verb) {
  return activity(GENERIC[verb] || { text: `${verb.toLowerCase().replace(/_/g, ' ') || 'changed'} a record`, icon: 'history', tone: 'change' })
}

function activity({ text, icon, tone, record = null, note = null, badge = null, clientId = null }) {
  return { text, icon, tone, record, note, badge, clientId }
}

// Who acted: the CRM user's name when user-service knows them.
export function actorLabel({ actorId, actorName }) {
  if (actorName) return actorName
  if (String(actorId || '').startsWith('system:')) return 'System'
  return 'Unknown user'
}

// Only CRM users can be picked out: system work has no user to filter on.
export function isUserActor({ actorId }) {
  return Boolean(actorId) && !String(actorId).startsWith('system:')
}

// IDs are long UUIDs; the first 8 characters tell entries apart.
export function shortId(id) {
  return id.length > 8 ? id.slice(0, 8) : id
}

export function sentenceCase(text) {
  return text.charAt(0).toUpperCase() + text.slice(1)
}
