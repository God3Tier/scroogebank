import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { authorizationHeader } from '../auth/session.js'
import { createClient, fetchDocument, listDocuments, uploadDocument, verifyClient } from './clients.js'

vi.mock('../auth/session.js', () => ({ authorizationHeader: vi.fn() }))

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('client API', () => {
  beforeEach(() => {
    authorizationHeader.mockResolvedValue({ Authorization: 'Bearer synthetic-token' })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('sends the access token and the new client as JSON', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ client_id: 'client-1' }, 201))
    vi.stubGlobal('fetch', fetchMock)

    await expect(createClient({ first_name: 'Synthetic' })).resolves.toEqual({ client_id: 'client-1' })

    const [url, options] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/clients')
    expect(options.method).toBe('POST')
    expect(options.headers).toMatchObject({ Authorization: 'Bearer synthetic-token', 'Content-Type': 'application/json' })
    expect(JSON.parse(options.body)).toEqual({ first_name: 'Synthetic' })
  })

  it('uploads the file as the multipart field "file" and leaves the boundary to the browser', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ document_id: 'doc-1' }, 201))
    vi.stubGlobal('fetch', fetchMock)
    const file = new File(['synthetic'], 'synthetic.png', { type: 'image/png' })

    await uploadDocument('client-1', file)

    const [url, options] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/clients/client-1/documents')
    expect(options.body).toBeInstanceOf(FormData)
    expect(options.body.get('file')).toBeInstanceOf(File)
    expect(options.headers).not.toHaveProperty('Content-Type')
    expect(options.headers).toMatchObject({ Authorization: 'Bearer synthetic-token' })
  })

  it('names the documents the agent checked when verifying', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ status: 'ok' }))
    vi.stubGlobal('fetch', fetchMock)

    await verifyClient('client-1', ['doc-1', 'doc-2'])

    const [url, options] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/clients/client-1/verify')
    expect(JSON.parse(options.body)).toEqual({ document_ids: ['doc-1', 'doc-2'] })
  })

  it('reads a document as a blob', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('synthetic', { headers: { 'Content-Type': 'image/png' } })))

    const blob = await fetchDocument('client-1', 'doc-1')

    expect(blob.type).toBe('image/png')
  })

  it("puts the status and the service's message on errors", async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('client is already verified\n', { status: 409 })))

    await expect(listDocuments('client-1')).rejects.toMatchObject({ status: 409, message: 'client is already verified' })
  })
})
