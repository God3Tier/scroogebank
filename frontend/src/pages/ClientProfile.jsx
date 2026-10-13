import { useEffect, useState } from 'react'
import { useAuth } from 'react-oidc-context'
import { Link, useParams } from 'react-router-dom'

import { fetchDocument, getClient, listDocuments, uploadDocument, verifyClient } from '../api/clients.js'
import { ADMIN, inAnyGroup } from '../auth/roles.js'

// One client's profile, identity documents and verification (ADR-0041). The
// agent may upload the document the client gave them, opens it, compares it
// with the profile and then verifies the client. Uploading never verifies
// anyone. Documents are optional, but a verification without one is shown as
// such, here and in the record.
const MAX_DOCUMENT_BYTES = 5 * 1024 * 1024
const ACCEPTED_FILES = '.jpg,.jpeg,.png,.pdf,image/jpeg,image/png,application/pdf'
const TYPE_LABELS = { 'image/jpeg': 'JPEG image', 'image/png': 'PNG image', 'application/pdf': 'PDF' }

// Clients verified before ADR-0041 hold "verified" in lower case.
function isVerified(client) {
  return String(client?.verification_status || '').toUpperCase() === 'VERIFIED'
}

function formatDateTime(value) {
  return value ? new Date(value).toLocaleString('en-SG', { dateStyle: 'medium', timeStyle: 'short' }) : ''
}

function formatSize(bytes) {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function uploadProblem(error) {
  if (error.status === 413) return 'The file is larger than 5 MB.'
  if (error.status === 415) return 'Only JPEG, PNG or PDF files can be uploaded.'
  if (error.status === 404) return 'This client is no longer available.'
  return 'The document could not be uploaded. Try again.'
}

function verifyProblem(error) {
  if (error.status === 409) return 'This client is already verified.'
  if (error.status === 404) return 'This client is no longer available.'
  if (error.status === 400) return "Only this client's own documents can be used. Reload the page and check them again."
  return 'The client could not be verified. Try again.'
}

function ClientProfile() {
  const { clientId } = useParams()
  // A new client starts from a fresh page, with nothing checked.
  return <ClientProfileView key={clientId} clientId={clientId} />
}

function ClientProfileView({ clientId }) {
  const auth = useAuth()
  const ownSubject = auth.user?.profile?.sub
  const homePath = inAnyGroup(auth.user, [ADMIN]) ? '/admin' : '/agent'
  const [state, setState] = useState({ status: 'loading', client: null, documents: [], error: null })
  const [file, setFile] = useState(null)
  const [upload, setUpload] = useState({ saving: false, error: '', message: '' })
  const [viewed, setViewed] = useState(() => new Set())
  const [checked, setChecked] = useState(() => new Set())
  const [confirmed, setConfirmed] = useState(false)
  const [verification, setVerification] = useState({ saving: false, error: '' })
  const [preview, setPreview] = useState(null)
  const [previewError, setPreviewError] = useState('')

  useEffect(() => {
    let active = true
    Promise.all([getClient(clientId), listDocuments(clientId)])
      .then(([client, documents]) => {
        if (active) setState({ status: 'ready', client, documents, error: null })
      })
      .catch((error) => {
        if (active) setState({ status: 'error', client: null, documents: [], error })
      })
    return () => {
      active = false
    }
  }, [clientId])

  // The preview's object URL holds the document in memory; release it when
  // the preview changes or the page closes.
  useEffect(() => () => {
    if (preview) URL.revokeObjectURL(preview.url)
  }, [preview])

  async function reload() {
    const [client, documents] = await Promise.all([getClient(clientId), listDocuments(clientId)])
    setState({ status: 'ready', client, documents, error: null })
  }

  // Users are known here only by their Cognito sub.
  function who(subject) {
    if (!subject) return 'an unknown user'
    return subject === ownSubject ? 'you' : `another user (${subject.slice(0, 8)}…)`
  }

  async function handleUpload(event) {
    event.preventDefault()
    const form = event.currentTarget
    if (!file) return
    if (file.size > MAX_DOCUMENT_BYTES) {
      setUpload({ saving: false, error: 'The file is larger than 5 MB.', message: '' })
      return
    }
    setUpload({ saving: true, error: '', message: '' })
    try {
      await uploadDocument(clientId, file)
    } catch (error) {
      setUpload({ saving: false, error: uploadProblem(error), message: '' })
      return
    }
    form.reset()
    setFile(null)
    setUpload({ saving: false, error: '', message: 'Document uploaded. Open it and compare it with the profile.' })
    try {
      await reload()
    } catch {
      setUpload({ saving: false, error: 'The document was uploaded, but the list could not be refreshed. Reload the page.', message: '' })
    }
  }

  async function handleView(doc) {
    setPreviewError('')
    try {
      const blob = await fetchDocument(clientId, doc.document_id)
      const typed = blob.type ? blob : new Blob([blob], { type: doc.content_type })
      setPreview({ documentId: doc.document_id, type: typed.type, url: URL.createObjectURL(typed) })
      setViewed((current) => new Set(current).add(doc.document_id))
    } catch (error) {
      setPreviewError(error.status === 404 ? 'This document is no longer available.' : 'The document could not be opened. Try again.')
    }
  }

  function toggleChecked(documentId) {
    const next = new Set(checked)
    if (next.has(documentId)) next.delete(documentId)
    else next.add(documentId)
    setChecked(next)
    // The confirmation says what was checked, so it is asked again whenever
    // the verification switches between with and without documents.
    if ((checked.size === 0) !== (next.size === 0)) setConfirmed(false)
  }

  async function handleVerify(event) {
    event.preventDefault()
    setVerification({ saving: true, error: '' })
    try {
      await verifyClient(clientId, [...checked])
    } catch (error) {
      setVerification({ saving: false, error: verifyProblem(error) })
      return
    }
    setVerification({ saving: false, error: '' })
    try {
      await reload()
    } catch {
      setVerification({ saving: false, error: 'The client was verified, but the page could not be refreshed. Reload the page.' })
    }
  }

  if (state.status === 'loading') {
    return (
      <div className="page">
        <p className="empty-state" role="status">Loading the client…</p>
      </div>
    )
  }
  if (state.status === 'error') {
    return (
      <div className="page client-profile">
        <div className="back-row"><Link to={homePath}>Back to dashboard</Link></div>
        <p className="error-text" role="alert">
          {state.error?.status === 404
            ? 'This client does not exist, or is not one of your clients.'
            : 'The client could not be loaded. Try again later.'}
        </p>
      </div>
    )
  }

  const { client, documents } = state
  const verified = isVerified(client)
  const withoutDocument = verified && client.verified_with_documents === false
  const verifyWithDocuments = checked.size > 0
  const evidenceCount = documents.filter((doc) => doc.verification_evidence).length
  const initials = `${client.first_name?.charAt(0) || ''}${client.last_name?.charAt(0) || ''}`
  const profile = [
    ['First name', client.first_name],
    ['Last name', client.last_name],
    ['Date of birth', String(client.date_of_birth || '').slice(0, 10)],
    ['Gender', client.gender],
    ['Email', client.email],
    ['Phone number', client.phone_number],
    ['Address', client.address],
    ['City', client.city],
    ['State', client.state],
    ['Country', client.country],
    ['Postal code', client.postal_code],
  ]

  return (
    <div className="page client-profile">
      <div className="back-row"><Link to={homePath}>Back to dashboard</Link></div>

      <header className="card client-header">
        <div className="client-avatar" aria-hidden="true">{initials}</div>
        <div className="client-header__text">
          <h1>{client.first_name} {client.last_name}</h1>
          <p>{client.email}</p>
        </div>
        <div className="client-header__badges">
          <span className={`status-badge ${verified ? 'status-badge--verified' : 'status-badge--attention'}`}>
            <span className="material-symbols-outlined" aria-hidden="true">{verified ? 'verified_user' : 'pending'}</span>
            {verified ? 'Verified' : 'Not verified'}
          </span>
          {withoutDocument && (
            <span className="status-badge status-badge--attention">
              <span className="material-symbols-outlined" aria-hidden="true">description</span>
              Without a document
            </span>
          )}
        </div>
      </header>

      <section className="card" aria-labelledby="profile-title">
        <h2 id="profile-title">Profile</h2>
        <dl className="profile-grid">
          {profile.map(([label, value]) => (
            <div key={label}>
              <dt>{label}</dt>
              <dd>{value || '—'}</dd>
            </div>
          ))}
        </dl>
      </section>

      <section className="card card-flush" aria-labelledby="documents-title">
        <h2 id="documents-title">Identity documents</h2>
        <div className="card-body">
          <p className="section-note">
            Upload the identity document the client gave you, open it and compare it with the profile.
            Uploading a document does not verify the client. Documents are optional, but a verification
            without one is recorded and shown as such.
          </p>
          <form className="document-upload" onSubmit={handleUpload}>
            <label>
              Document file (JPEG, PNG or PDF, up to 5 MB)
              <input type="file" accept={ACCEPTED_FILES} onChange={(event) => setFile(event.target.files?.[0] || null)} />
            </label>
            <button type="submit" disabled={!file || upload.saving}>{upload.saving ? 'Uploading…' : 'Upload'}</button>
          </form>
          {upload.error && <p className="form-error" role="alert">{upload.error}</p>}
          {upload.message && <p className="success-message" role="status">{upload.message}</p>}
        </div>

        {documents.length === 0 ? (
          <p className="empty-state">No documents yet.</p>
        ) : (
          <div className="table-wrap">
            <table className="data-table documents-table">
              <thead>
                <tr>
                  {!verified && <th scope="col">Checked</th>}
                  <th scope="col">Document</th>
                  <th scope="col" className="num">Size</th>
                  <th scope="col">Uploaded</th>
                  <th scope="col">By</th>
                  <th scope="col"><span className="visually-hidden">Open</span></th>
                </tr>
              </thead>
              <tbody>
                {documents.map((doc) => {
                  const typeLabel = TYPE_LABELS[doc.content_type] || doc.content_type
                  const label = `${typeLabel} uploaded ${formatDateTime(doc.uploaded_at)}`
                  const uploader = who(doc.uploaded_by_agent_id)
                  return (
                    <tr key={doc.document_id}>
                      {!verified && (
                        <td>
                          <input
                            type="checkbox"
                            aria-label={`Checked: ${label}`}
                            title={viewed.has(doc.document_id) ? undefined : 'Open the document first'}
                            checked={checked.has(doc.document_id)}
                            disabled={!viewed.has(doc.document_id)}
                            onChange={() => toggleChecked(doc.document_id)}
                          />
                        </td>
                      )}
                      <td>
                        <span className="document-name">{typeLabel}</span>
                        {doc.verification_evidence && (
                          <span className="status-badge status-badge--verified">Used for verification</span>
                        )}
                      </td>
                      <td className="num">{formatSize(doc.size_bytes)}</td>
                      <td className="mono">{formatDateTime(doc.uploaded_at)}</td>
                      <td>{uploader.charAt(0).toUpperCase() + uploader.slice(1)}</td>
                      <td>
                        <button type="button" aria-label={`View ${label}`} onClick={() => handleView(doc)}>View</button>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
        {!verified && documents.length > 0 && (
          <p className="section-note card-body">Open a document before you mark it as checked.</p>
        )}
      </section>

      {previewError && <p className="error-text" role="alert">{previewError}</p>}
      {preview && (
        <section className="card document-preview" aria-labelledby="preview-title">
          <div className="document-preview__header">
            <h2 id="preview-title">Document being checked</h2>
            <button type="button" onClick={() => setPreview(null)}>Close</button>
          </div>
          {preview.type === 'application/pdf'
            ? <iframe title="Identity document" src={preview.url} />
            : <img src={preview.url} alt="Identity document being checked" />}
        </section>
      )}

      <section className="card verify-panel" aria-labelledby="verify-title">
        <h2 id="verify-title">Verification</h2>
        {verified ? (
          <div className="verify-done" role="status">
            <p>
              <span className="material-symbols-outlined" aria-hidden="true">verified_user</span>
              <span>
                {!client.verified_at && 'Verified before verification details were recorded.'}
                {client.verified_at && `Verified ${formatDateTime(client.verified_at)} by ${who(client.verified_by_agent_id)}`}
                {client.verified_at && (withoutDocument
                  ? ', without an uploaded document.'
                  : `, based on ${evidenceCount} ${evidenceCount === 1 ? 'document' : 'documents'}.`)}
              </span>
            </p>
            {withoutDocument && documents.length > 0 && (
              <p className="section-note">Documents uploaded since are not part of this verification.</p>
            )}
          </div>
        ) : (
          <form className="verify-form" onSubmit={handleVerify}>
            <p>
              Verify the client once you have checked their identity against the profile. The verification
              records you, the time and the documents you checked.
            </p>
            {verifyWithDocuments ? (
              <p className="verify-count">
                {checked.size} of {documents.length} {documents.length === 1 ? 'document' : 'documents'} checked
              </p>
            ) : (
              <p className="verify-without">
                <span className="material-symbols-outlined" aria-hidden="true">info</span>
                <span>
                  No document is checked. You can still verify the client, for example after seeing their
                  identity document in person, but the verification will be recorded and shown as done
                  without an uploaded document.
                </span>
              </p>
            )}
            <label className="confirm-check">
              <input
                type="checkbox"
                checked={confirmed}
                onChange={(event) => setConfirmed(event.target.checked)}
              />
              {verifyWithDocuments
                ? "I have checked the selected documents against this client's details."
                : "I have checked this client's identity without an uploaded document."}
            </label>
            {verification.error && <p className="form-error" role="alert">{verification.error}</p>}
            <div className="verify-actions">
              <button type="submit" disabled={!confirmed || verification.saving}>
                {verification.saving ? 'Verifying…' : verifyWithDocuments ? 'Verify client' : 'Verify without a document'}
              </button>
            </div>
          </form>
        )}
      </section>
    </div>
  )
}

export default ClientProfile
