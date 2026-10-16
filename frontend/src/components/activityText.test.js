import { describe, expect, it } from 'vitest'

import { actorLabel, describeActivity, isUserActor } from './activityText.js'

const CLIENT_ID = '3f9c2a1e-0000-4000-8000-000000000101'

// Payloads as client-service and account-service record them (source CRM).
function crm(action, httpMethod, endpoint, extra = {}) {
  return {
    sourceService: 'CRM',
    action,
    payload: { attribute_name: 'endpoint_call', http_method: httpMethod, endpoint, status_code: 200, ...extra },
  }
}

// Payloads as user-service records them.
function userChange(action, extra = {}) {
  return { sourceService: 'USER_MANAGEMENT', action, payload: { resource_type: 'USER', resource_id: 'user-0001', ...extra } }
}

describe('describing client and account changes', () => {
  it('describes a new client, which has no ID in its event', () => {
    expect(describeActivity(crm('CREATE', 'POST', '/api/clients'))).toMatchObject({
      text: 'created a client profile',
      record: null,
      clientId: null,
    })
  })

  it('opens the client an update was made to', () => {
    expect(describeActivity(crm('UPDATE', 'PUT', '/api/clients/:clientId', { client_id: CLIENT_ID }))).toMatchObject({
      text: 'updated a client profile',
      record: { label: 'Client', id: CLIENT_ID },
      clientId: CLIENT_ID,
    })
  })

  it('does not open a deleted client', () => {
    expect(describeActivity(crm('DELETE', 'DELETE', '/api/clients/:clientId', { client_id: CLIENT_ID }))).toMatchObject({
      text: 'deleted a client profile',
      clientId: null,
    })
  })

  it('says how many documents a verification used', () => {
    const verified = describeActivity(crm('UPDATE', 'POST', '/api/clients/:clientId/verify', {
      client_id: CLIENT_ID, document_ids: ['doc-1', 'doc-2'],
    }))
    expect(verified).toMatchObject({ text: 'verified a client', note: 'with 2 documents', badge: null })
  })

  it('flags a verification without a document', () => {
    const verified = describeActivity(crm('UPDATE', 'POST', '/api/clients/:clientId/verify', {
      client_id: CLIENT_ID, without_document: true,
    }))
    expect(verified).toMatchObject({ text: 'verified a client', note: null, badge: 'Without a document' })
  })

  it('describes an uploaded identity document', () => {
    expect(describeActivity(crm('CREATE', 'POST', '/api/clients/:clientId/documents', {
      client_id: CLIENT_ID, document_ids: ['doc-1'],
    }))).toMatchObject({ text: 'uploaded an identity document', note: null, clientId: CLIENT_ID })
  })

  it("names the account, not a client, when an account is closed", () => {
    expect(describeActivity(crm('DELETE', 'DELETE', '/api/accounts/:accountId', { client_id: 'account-0001' }))).toMatchObject({
      text: 'closed an account',
      record: { label: 'Account', id: 'account-0001' },
      clientId: null,
    })
  })

  it('falls back to the action for a route it does not know', () => {
    expect(describeActivity(crm('UPDATE', 'PATCH', '/api/clients/:clientId/notes'))).toMatchObject({
      text: 'updated a record',
      record: null,
    })
  })
})

describe('describing user changes', () => {
  it("names the new user's role", () => {
    expect(describeActivity(userChange('CREATE', { user_role: 'AGENT' })).text).toBe('created an agent')
    expect(describeActivity(userChange('CREATE', { user_role: 'ADMIN' })).text).toBe('created an admin')
  })

  it('names the new role of a role change', () => {
    expect(describeActivity(userChange('ROLE_CHANGE', { user_role: 'ADMIN' }))).toMatchObject({
      text: "changed a user's role to Admin",
      record: { label: 'User', id: 'user-0001' },
    })
  })

  it.each([
    ['UPDATE', "updated a user's name"],
    ['DISABLE', 'disabled a user'],
    ['PASSWORD_RESET', "reset a user's password"],
  ])('describes %s', (action, text) => {
    expect(describeActivity(userChange(action)).text).toBe(text)
  })

  it('describes tamper findings', () => {
    expect(describeActivity({
      sourceService: 'USER_MANAGEMENT',
      action: 'DELETE',
      payload: { resource_type: 'CORRUPTED_AUDIT_LOG', resource_id: 'finding-1' },
    })).toMatchObject({ text: 'deleted a tamper finding', record: { label: 'Finding', id: 'finding-1' } })
  })

  it("describes a user's own password reset through Cognito", () => {
    expect(describeActivity({ sourceService: 'COGNITO_TRIGGERS', action: 'PASSWORD_RESET', payload: {} }).text)
      .toBe('reset their own password')
  })

  it('copes with a payload that is not an object', () => {
    expect(describeActivity({ sourceService: 'CRM', action: 'CREATE', payload: 'not json' }).text).toBe('created a record')
  })
})

describe('the actor', () => {
  it("uses the CRM user's name, or says who is unknown", () => {
    expect(actorLabel({ actorId: 'agent-sub-0001', actorName: 'Synthetic Agent' })).toBe('Synthetic Agent')
    expect(actorLabel({ actorId: 'agent-sub-0001', actorName: null })).toBe('Unknown user')
    expect(actorLabel({ actorId: 'system:root-admin-initializer', actorName: null })).toBe('System')
  })

  it('can filter on users but not on system work', () => {
    expect(isUserActor({ actorId: 'agent-sub-0001' })).toBe(true)
    expect(isUserActor({ actorId: 'system:root-admin-initializer' })).toBe(false)
  })
})
