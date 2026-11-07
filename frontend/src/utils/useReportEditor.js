import { useState } from 'react'

import { saveReportEdit } from '../api/alertEvents.js'
import { fromText, lineOf, sectionName, toText } from './reportText.js'

/**
 * The Edit / Preview toggle for one report (news-alert-drafts plan 5.2). Edit shows the whole report as one text box;
 * Preview reads it back, has the server check it, saves it and shows the report again. A mistake keeps the agent in
 * Edit with what failed, by line.
 *
 * @param review  the server's review of the report: original and saved sections, version, approval
 * @param onSaved called after a save, so the page reloads the review
 */
export function useReportEditor({ eventId, review, onSaved }) {
  const [editing, setEditing] = useState(false)
  const [text, setText] = useState('')
  const [errors, setErrors] = useState([])
  const [saving, setSaving] = useState(false)

  const base = review?.editedSections || review?.originalSections || null
  const canEdit = Boolean(review?.editable && base && !review?.approval?.valid)

  function start() {
    setText(toText(base))
    setErrors([])
    setEditing(true)
  }

  async function preview() {
    const parsed = fromText(text, review.originalSections)
    if (parsed.errors) {
      setErrors(parsed.errors)
      return
    }
    if (JSON.stringify(parsed.sections) === JSON.stringify(base)) {
      setErrors([])
      setEditing(false)
      return
    }
    setSaving(true)
    try {
      await saveReportEdit(eventId, review.reportVersion, parsed.sections)
      setErrors([])
      setEditing(false)
      onSaved()
    } catch (e) {
      if (e.status === 422 && e.problem?.section) {
        const where = `${sectionName(e.problem.section)}${e.problem.index >= 0 ? `, item ${e.problem.index + 1}` : ''}`
        setErrors([{ line: lineOf(text, e.problem.section, e.problem.index), message: `${where}: ${e.message || 'This did not pass the checks.'} Nothing was saved.` }])
      } else if (e.status === 409) {
        setErrors([{ line: 0, message: e.message || 'The report changed or was approved. Reload it.' }])
      } else {
        setErrors([{ line: 0, message: 'The edit could not be saved. Try again.' }])
      }
    } finally {
      setSaving(false)
    }
  }

  return { editing, text, setText, errors, saving, canEdit, toggle: editing ? preview : start }
}
