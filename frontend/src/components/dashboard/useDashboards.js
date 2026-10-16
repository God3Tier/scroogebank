import { useEffect, useState } from 'react'

import { getDashboard } from '../../api/dashboard.js'

/**
 * Loads several dashboard endpoints at once.
 *
 * @param requests { name: [endpoint, params] }
 * @returns { loading, responses: { name: response | null }, errors: { name: Error } }
 *
 * Each endpoint stands alone: one that fails leaves the others shown, the
 * same way an unavailable panel does. An answer for old requests never
 * overwrites newer ones.
 */
export default function useDashboards(requests) {
  const key = JSON.stringify(requests)
  const [state, setState] = useState({ key: null, responses: {}, errors: {} })

  useEffect(() => {
    let current = true
    const entries = Object.entries(JSON.parse(key))
    Promise.allSettled(entries.map(([, [endpoint, params]]) => getDashboard(endpoint, params)))
      .then((results) => {
        if (!current) return
        const responses = {}
        const errors = {}
        results.forEach((result, i) => {
          const [name] = entries[i]
          responses[name] = result.status === 'fulfilled' ? result.value : null
          if (result.status === 'rejected') errors[name] = result.reason
        })
        setState({ key, responses, errors })
      })
    return () => { current = false }
  }, [key])

  return { loading: state.key !== key, responses: state.responses, errors: state.errors }
}

// The panel named. Undefined while its response loads, so PanelCard renders
// its (empty) body; an unavailable stand-in when the request failed, so
// PanelCard shows "Data unavailable".
export function panelOf(response, name) {
  if (response === undefined) return undefined
  return response?.panels?.[name] ?? { status: 'UNAVAILABLE', data: null, reason: 'upstream_error' }
}

// Status and type keys arrive as the source stores them ("Completed",
// "Deposit"); the dashboard labels them by upper-case key.
export function upperKeys(map) {
  return Object.fromEntries(Object.entries(map ?? {}).map(([key, value]) => [key.toUpperCase(), value]))
}
