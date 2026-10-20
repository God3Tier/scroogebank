import { useEffect, useState } from 'react'
import { useAuth } from 'react-oidc-context'
import { Link, useParams } from 'react-router-dom'

import { createAccount, listAccounts, updateAccountStatus } from '../api/accounts.js'
import { fetchDocument, getClient, listDocuments, updateClient, uploadDocument, verifyClient } from '../api/clients.js'
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

function updateProblem(error) {
  if (error.status === 400) return `Check the details: ${error.message.replace(/^bad input:?\s*/i, '')}`
  if (error.status === 404) return 'This client is no longer available.'
  if (error.status === 409) return 'Another client already has this email or phone number.'
  return 'The changes could not be saved. Try again.'
}

// An unverified client's new accounts open as Pending; once the client is
// verified, the agent decides when they become Active (ADR-0050).
const PENDING_UNTIL_VERIFIED = "This client isn't verified, so new accounts open as Pending."

const NO_ACTIVATION = { accounts: [], busy: false, error: '', message: '' }

// account-service answers a validation failure with a bare "bad input", so
// a 400 cannot say which field was wrong. A 403 means the account was not
// Pending for an unverified client.
function openAccountProblem(error) {
  if (error.status === 400) return 'Check the account details and try again.'
  if (error.status === 403) return "This client isn't verified, so the account can only be opened as Pending."
  if (error.status === 404) return 'This client is no longer available.'
  if (error.status === 409) return 'This client already has an account of this type.'
  if (error.status === 503) return 'The client could not be checked just now. Try again shortly.'
  return 'The account could not be opened. Try again.'
}

// The types account-service accepts.
const ACCOUNT_TYPES = [
  ['CHECKING', 'Checking'],
  ['SAVINGS', 'Savings'],
  ['CREDIT', 'Credit'],
  ['LOAN', 'Loan'],
  ['INVESTMENT', 'Investment'],
]

// account-service also accepts INACTIVE and CLOSED, but a new account is
// not opened dormant or closed.
const OPENING_STATUSES = [
  ['ACTIVE', 'Active'],
  ['PENDING', 'Pending'],
]

const EDITABLE_FIELDS = [
  ['first_name', 'First Name:', 'text', 50],
  ['last_name', 'Last Name:', 'text', 50],
  ['date_of_birth', 'Date of Birth:', 'date'],
  ['email', 'Email:', 'email', 255],
  ['phone_number', 'Phone Number:', 'tel', 20],
  ['address', 'Address:', 'text', 100],
  ['city', 'City:', 'text', 50],
  ['state', 'State:', 'text', 50],
  ['country', 'Country:', 'text', 50],
  ['postal_code', 'Postal Code:', 'text', 10],
]

// Edits the profile. client-service replaces every field, so the form sends
// them all; verification state is not editable here.
function ProfileEditor({ client, onSaved, onCancel }) {
  const [form, setForm] = useState(() => ({
    ...Object.fromEntries(EDITABLE_FIELDS.map(([name]) => [name, client[name] || ''])),
    date_of_birth: String(client.date_of_birth || '').slice(0, 10),
    gender: client.gender || '',
  }))
  const [saving, setSaving] = useState({ busy: false, error: '' })

  const setField = (name) => (event) => setForm((current) => ({ ...current, [name]: event.target.value }))

  async function handleSubmit(event) {
    event.preventDefault()
    setSaving({ busy: true, error: '' })
    try {
      await updateClient(client.client_id, { ...form, date_of_birth: `${form.date_of_birth}T00:00:00Z` })
    } catch (error) {
      setSaving({ busy: false, error: updateProblem(error) })
      return
    }
    setSaving({ busy: false, error: '' })
    onSaved()
  }

  return (
    <form className="form-grid" onSubmit={handleSubmit}>
      {EDITABLE_FIELDS.map(([name, label, type, maxLength]) => (
        <label key={name}>{label}
          <input type={type} required maxLength={maxLength} value={form[name]} onChange={setField(name)} />
        </label>
      ))}
      <label>Gender:
        <select required value={form.gender} onChange={setField('gender')}>
          <option value="">Select Gender</option>
          <option value="male">Male</option>
          <option value="female">Female</option>
          <option value="other">Other</option>
        </select>
      </label>
      {saving.error && <p className="form-error form-grid__full" role="alert">{saving.error}</p>}
      <div className="form-actions">
        <button type="submit" disabled={saving.busy}>{saving.busy ? 'Saving…' : 'Save changes'}</button>
        <button type="button" onClick={onCancel}>Cancel</button>
      </div>
    </form>
  )
}

// Opens a bank account for the client. account-service sets the opening date
// and makes the client's owner the account's owner. For an unverified client
// the only status offered is Pending.
function AccountOpener({ clientId, pendingOnly, onOpened, onCancel }) {
  const statuses = pendingOnly ? OPENING_STATUSES.filter(([value]) => value === 'PENDING') : OPENING_STATUSES
  const [form, setForm] = useState({ account_type: '', status: statuses[0][0], initial_deposit: '', currency: 'SGD', branch_id: '' })
  const [saving, setSaving] = useState({ busy: false, error: '' })

  const setField = (name) => (event) => setForm((current) => ({ ...current, [name]: event.target.value }))

  async function handleSubmit(event) {
    event.preventDefault()
    setSaving({ busy: true, error: '' })
    let opened
    try {
      opened = await createAccount({
        ...form,
        client_id: clientId,
        initial_deposit: Number(form.initial_deposit),
        currency: form.currency.toUpperCase(),
        branch_id: form.branch_id.trim(),
      })
    } catch (error) {
      setSaving({ busy: false, error: openAccountProblem(error) })
      return
    }
    setSaving({ busy: false, error: '' })
    onOpened(form.account_type, opened.account_id)
  }

  return (
    <form className="form-grid" onSubmit={handleSubmit}>
      <label>Account Type:
        <select required value={form.account_type} onChange={setField('account_type')}>
          <option value="">Select Account Type</option>
          {ACCOUNT_TYPES.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
      <label>Status:
        <select required value={form.status} onChange={setField('status')}>
          {statuses.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
      {pendingOnly && <p className="section-note form-grid__full">{PENDING_UNTIL_VERIFIED} Verify the client to make them Active.</p>}
      <label>Initial Deposit:
        <input type="number" required min="0" step="0.01" value={form.initial_deposit} onChange={setField('initial_deposit')} />
      </label>
      <label>Currency:
        <input
          type="text"
          required
          minLength={3}
          maxLength={3}
          pattern="[A-Za-z]{3}"
          title="A 3-letter ISO currency code, such as SGD"
          value={form.currency}
          onChange={setField('currency')}
        />
      </label>
      <label>Branch ID:
        <input type="text" required maxLength={50} value={form.branch_id} onChange={setField('branch_id')} />
      </label>
      {saving.error && <p className="form-error form-grid__full" role="alert">{saving.error}</p>}
      <div className="form-actions">
        <button type="submit" disabled={saving.busy}>{saving.busy ? 'Opening…' : 'Open account'}</button>
        <button type="button" onClick={onCancel}>Cancel</button>
      </div>
    </form>
  )
}

function ClientProfile() {
  const { clientId } = useParams()
  // A new client starts from a fresh page, with nothing checked.
  return <ClientProfileView key={clientId} clientId={clientId} />
}

function ClientProfileView({ clientId }) {
  const auth = useAuth()
  const ownSubject = auth.user?.profile?.sub
  const admin = inAnyGroup(auth.user, [ADMIN])
  const homePath = admin ? '/admin' : '/agent'
  const [state, setState] = useState({ status: 'loading', client: null, documents: [], error: null })
  const [file, setFile] = useState(null)
  const [upload, setUpload] = useState({ saving: false, error: '', message: '' })
  const [viewed, setViewed] = useState(() => new Set())
  const [checked, setChecked] = useState(() => new Set())
  const [confirmed, setConfirmed] = useState(false)
  const [verification, setVerification] = useState({ saving: false, error: '' })
  const [preview, setPreview] = useState(null)
  const [previewError, setPreviewError] = useState('')
  const [editing, setEditing] = useState(false)
  const [editMessage, setEditMessage] = useState('')
  const [openingAccount, setOpeningAccount] = useState(false)
  const [accountMessage, setAccountMessage] = useState('')
  // After a verification: the client's Pending accounts, which the agent may
  // make Active (ADR-0050).
  const [activation, setActivation] = useState(NO_ACTIVATION)

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

  async function handleSaved() {
    setEditing(false)
    setEditMessage('Changes saved.')
    try {
      await reload()
    } catch {
      setEditMessage('The changes were saved, but the page could not be refreshed. Reload the page.')
    }
  }

  function handleAccountOpened(accountType, accountId) {
    const typeLabel = Object.fromEntries(ACCOUNT_TYPES)[accountType]
    setOpeningAccount(false)
    setAccountMessage(`${typeLabel} account opened. Account ID: ${accountId}`)
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
      return
    }
    // Agents manage client accounts; admins do not (requirements Q&A UM18).
    if (!admin) await offerActivation()
  }

  // Asks the agent whether the newly verified client's Pending accounts
  // should become Active.
  async function offerActivation() {
    let accounts
    try {
      accounts = await listAccounts(clientId)
    } catch {
      setActivation({ ...NO_ACTIVATION, error: "The client's Pending accounts could not be checked." })
      return
    }
    const pending = accounts.filter((account) => String(account.status).toUpperCase() === 'PENDING')
    if (pending.length > 0) setActivation({ ...NO_ACTIVATION, accounts: pending })
  }

  async function handleActivate() {
    setActivation((current) => ({ ...current, busy: true, error: '' }))
    const results = await Promise.allSettled(activation.accounts.map((account) => updateAccountStatus(account.id, 'ACTIVE')))
    const failed = activation.accounts.filter((_, i) => results[i].status === 'rejected')
    const changed = activation.accounts.length - failed.length
    if (failed.length === 0) {
      setActivation({ ...NO_ACTIVATION, message: `${changed} ${changed === 1 ? 'account is' : 'accounts are'} now Active.` })
      return
    }
    // Only the ones that failed are offered again.
    setActivation({
      ...NO_ACTIVATION,
      accounts: failed,
      error: `${failed.length} ${failed.length === 1 ? 'account' : 'accounts'} could not be made Active. Try again.`,
    })
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
  const accountsPath = `/agent/client/${encodeURIComponent(clientId)}/transactions`
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
          <p><Link to={accountsPath}>Manage accounts and transactions</Link></p>
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
        <div className="document-preview__header">
          <h2 id="profile-title">Profile</h2>
          {!editing && (
            <button type="button" onClick={() => { setEditMessage(''); setEditing(true) }}>Edit</button>
          )}
        </div>
        {editMessage && <p className="success-message" role="status">{editMessage}</p>}
        {editing ? (
          <ProfileEditor client={client} onSaved={handleSaved} onCancel={() => setEditing(false)} />
        ) : (
          <dl className="profile-grid">
            {profile.map(([label, value]) => (
              <div key={label}>
                <dt>{label}</dt>
                <dd>{value || '—'}</dd>
              </div>
            ))}
          </dl>
        )}
      </section>

      <section className="card" aria-labelledby="accounts-title">
        <div className="document-preview__header">
          <h2 id="accounts-title">Accounts</h2>
          {!openingAccount && (
            <button type="button" onClick={() => { setAccountMessage(''); setOpeningAccount(true) }}>Open account</button>
          )}
        </div>
        {accountMessage && <p className="success-message" role="status">{accountMessage}</p>}
        {openingAccount ? (
          <AccountOpener clientId={clientId} pendingOnly={!verified} onOpened={handleAccountOpened} onCancel={() => setOpeningAccount(false)} />
        ) : (
          <p className="section-note">
            Open a bank account for this client.{!verified && ` ${PENDING_UNTIL_VERIFIED}`} To see the accounts they
            hold and change their status, use <Link to={accountsPath}>Manage accounts and transactions</Link>.
          </p>
        )}
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
        {verified && (
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
        )}
        {verified && activation.accounts.length > 0 && (
          <div className="activation-prompt">
            <p>
              This client has {activation.accounts.length} Pending {activation.accounts.length === 1 ? 'account' : 'accounts'}.
              Make {activation.accounts.length === 1 ? 'it' : 'them'} Active?
            </p>
            <div className="verify-actions">
              <button type="button" onClick={handleActivate} disabled={activation.busy}>
                {activation.busy ? 'Making Active…' : 'Make Active'}
              </button>
              <button type="button" onClick={() => setActivation(NO_ACTIVATION)} disabled={activation.busy}>Keep Pending</button>
            </div>
          </div>
        )}
        {activation.error && (
          <p className="form-error" role="alert">
            {activation.error} Account statuses can also be changed on <Link to={accountsPath}>Manage accounts and transactions</Link>.
          </p>
        )}
        {activation.message && <p className="success-message" role="status">{activation.message}</p>}
        {!verified && (
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
