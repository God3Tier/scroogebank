// Mock business-dashboard responses for the demo mockup.
//
// Shaped like docs/api/dashboard-api.yaml and docs/api/dashboard-pushdown.yaml
// on feat/business-dashboard: every response is an envelope of
// { generatedAt, window, scope, panels }, and each panel carries its own
// status. Figures are synthetic but deterministic for a given window.
//
// A few fields are demo extensions that the contract does not define yet.
// They are marked "demo extension" below.

import agents from './agents.js'

const STALE_DAYS_DEFAULT = 7
const CURRENT_AGENT_ID = 1 // Sarah Lee, the seed agent who can log in
const TEAM_MIN_COHORT = 5 // TeamComparison.MIN_COHORT in dashboard-service

// Seed agents plus synthetic colleagues, so the team comparison has a cohort.
const roster = [
  ...agents.map((agent) => ({
    id: String(agent.agentId),
    firstName: agent.firstName,
    lastName: agent.lastName,
    email: agent.email,
    role: 'AGENT',
    enabled: agent.status === 'Active',
  })),
  { id: '4', firstName: 'Priya', lastName: 'Nair', email: 'priya.agent@example.com', role: 'AGENT', enabled: true },
  { id: '5', firstName: 'Marcus', lastName: 'Chen', email: 'marcus.agent@example.com', role: 'AGENT', enabled: true },
  { id: '6', firstName: 'Elena', lastName: 'Baskin', email: 'elena.agent@example.com', role: 'AGENT', enabled: true },
  { id: '7', firstName: 'Daniel', lastName: 'Koh', email: 'daniel.agent@example.com', role: 'AGENT', enabled: true },
  { id: '8', firstName: 'Aisha', lastName: 'Rahman', email: 'aisha.agent@example.com', role: 'AGENT', enabled: true },
]

const ACCOUNT_TYPES = ['savings', 'checking', 'business', 'credit', 'loan', 'investment']
const ACCOUNT_STATUSES = ['active', 'inactive', 'pending', 'closed']
const BRANCHES = ['Raffles Place', 'Jurong East', 'Tampines', 'Woodlands']

// ---------- Deterministic random numbers ----------

function hash(text) {
  let h = 2166136261
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return h >>> 0
}

function randomFor(seedText) {
  let state = hash(seedText)
  return () => {
    state = (state + 0x6d2b79f5) >>> 0
    let t = state
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

function between(random, min, max) {
  return min + random() * (max - min)
}

// Splits a total into integer parts that follow the given weights.
function split(total, weights) {
  const sum = weights.reduce((a, b) => a + b, 0)
  const parts = weights.map((w) => Math.floor((total * w) / sum))
  let rest = total - parts.reduce((a, b) => a + b, 0)
  for (let i = 0; rest > 0; i = (i + 1) % parts.length, rest--) parts[i]++
  return parts
}

function toMap(keys, values) {
  return Object.fromEntries(keys.map((key, i) => [key, values[i]]))
}

// ---------- Dates ----------

function isoDate(date) {
  return date.toISOString().slice(0, 10)
}

function addDays(iso, days) {
  const date = new Date(`${iso}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() + days)
  return isoDate(date)
}

function daysBetween(from, to) {
  return Math.round((new Date(`${to}T00:00:00Z`) - new Date(`${from}T00:00:00Z`)) / 86400000) + 1
}

export function windowForDays(days, today = isoDate(new Date())) {
  return { from: addDays(today, -(days - 1)), to: today }
}

// ---------- Figures ----------

// Per-agent figures for a window. Each agent has a fixed "style" (pace,
// verification habit, deposit size) so rankings stay stable across windows.
function agentFigures(agent, range) {
  const days = daysBetween(range.from, range.to)
  const style = randomFor(`style-${agent.id}`)
  const noise = randomFor(`range-${agent.id}-${range.from}-${range.to}`)

  const pace = agent.enabled ? between(style, 0.18, 0.55) : 0.04
  const verifyRate = between(style, 0.55, 0.86)
  const rejectRate = between(style, 0.03, 0.09)
  const accountsPerClient = between(style, 1.2, 1.7)
  const averageDeposit = between(style, 6000, 24000)

  const onboarded = Math.max(0, Math.round(days * pace * between(noise, 0.85, 1.15)))
  const verified = Math.round(onboarded * verifyRate)
  const rejected = Math.min(onboarded - verified, Math.round(onboarded * rejectRate))
  const unverified = onboarded - verified - rejected
  const accountsOpened = Math.round(onboarded * accountsPerClient)
  const deposits = Math.round((accountsOpened * averageDeposit * between(noise, 0.9, 1.1)) / 100) * 100

  return {
    agentId: agent.id,
    name: `${agent.firstName} ${agent.lastName}`,
    enabled: agent.enabled,
    clientsOnboarded: onboarded,
    verified,
    unverified,
    rejected,
    verificationRate: onboarded === 0 ? 0 : verified / onboarded,
    accountsOpened,
    bookedInitialDeposits: deposits.toFixed(2),
  }
}

function dailySeries(range, total, seedText, valuePerItem) {
  const days = daysBetween(range.from, range.to)
  const random = randomFor(seedText)
  const weights = Array.from({ length: days }, (_, i) => {
    const weekday = new Date(`${addDays(range.from, i)}T00:00:00Z`).getUTCDay()
    const weekend = weekday === 0 || weekday === 6
    return (weekend ? 0.45 : 1) * between(random, 0.7, 1.3)
  })
  const counts = split(total, weights)
  return counts.map((count, i) => ({
    date: addDays(range.from, i),
    count,
    value: valuePerItem ? (count * valuePerItem * between(random, 0.8, 1.2)).toFixed(2) : null,
  }))
}

function clientStats(range, scope, figures) {
  const random = randomFor(`clients-${scope}-${range.from}-${range.to}`)
  const createdInWindow = figures.reduce((sum, row) => sum + row.clientsOnboarded, 0)
  const base = scope === 'ADMIN' ? 1180 : 112
  const total = base + createdInWindow
  const [verified, pending, unverified, rejected] = split(total, [
    between(random, 0.68, 0.74),
    between(random, 0.06, 0.09),
    between(random, 0.14, 0.19),
    between(random, 0.04, 0.06),
  ])

  return {
    total,
    createdInWindow,
    byVerificationStatus: { VERIFIED: verified, PENDING: pending, UNVERIFIED: unverified, REJECTED: rejected },
    dailyCreated: dailySeries(range, createdInWindow, `daily-clients-${scope}-${range.from}`),
  }
}

function accountStats(range, scope, figures, clients) {
  const random = randomFor(`accounts-${scope}-${range.from}-${range.to}`)
  const openedInWindow = figures.reduce((sum, row) => sum + row.accountsOpened, 0)
  const deposits = figures.reduce((sum, row) => sum + Number(row.bookedInitialDeposits), 0)
  const total = Math.round(clients.total * between(random, 1.36, 1.44))

  return {
    total,
    openedInWindow,
    bookedInitialDeposits: deposits.toFixed(2),
    byType: toMap(ACCOUNT_TYPES, split(total, [46, 28, 11, 8, 4, 3])),
    byStatus: toMap(ACCOUNT_STATUSES, split(total, [82, 7, 6, 5])),
    byCurrency: { SGD: total },
    byBranch: toMap(BRANCHES, split(total, [38, 24, 21, 17])),
    accountsPerClient: Math.round((total / clients.total) * 10) / 10,
  }
}

function transactionStats(range, scope) {
  const days = daysBetween(range.from, range.to)
  const random = randomFor(`transactions-${scope}-${range.from}-${range.to}`)
  const perDay = scope === 'ADMIN' ? between(random, 38, 46) : between(random, 3.5, 5)
  const total = Math.round(days * perDay)
  const averageValue = between(random, 850, 1250)
  const [completed, pending, failed] = split(total, [between(random, 93, 95), 4, between(random, 1.5, 2.5)])
  const [deposit, withdrawal] = split(total, [58, 42])
  const daily = dailySeries(range, total, `daily-transactions-${scope}-${range.from}`, averageValue)

  return {
    total,
    totalValue: daily.reduce((sum, day) => sum + Number(day.value), 0).toFixed(2),
    byStatus: { COMPLETED: completed, PENDING: pending, FAILED: failed },
    byType: { DEPOSIT: deposit, WITHDRAWAL: withdrawal },
    pendingBacklog: Math.max(0, Math.round(pending * 0.35)),
    daily,
  }
}

// ---------- Action items ----------

function clientRef(clientId, firstName, lastName, daysAgo, today, extra = {}) {
  return {
    clientId,
    firstName,
    lastName,
    createdAt: `${addDays(today, -daysAgo)}T09:00:00Z`,
    ...extra,
  }
}

// Clients "1" and "2" are the seed clients in src/data/clients.js, so their
// rows can link to the existing client page.
function actionItems(scope, staleDays, today) {
  if (scope === 'AGENT') {
    return {
      staleUnverified: [
        clientRef('2', 'Jane', 'Smith', staleDays + 12, today),
        clientRef('c-51a9', 'Hannah', 'Lim', staleDays + 5, today),
        clientRef('c-7e20', 'Omar', 'Farouk', staleDays + 1, today),
      ],
      withoutAccount: [clientRef('c-3b44', 'Grace', 'Teo', 4, today)],
      rejectedVerification: [],
    }
  }

  return {
    staleUnverified: [
      clientRef('2', 'Jane', 'Smith', staleDays + 12, today, { agentName: 'Sarah Lee' }),
      clientRef('c-9f10', 'Wei Jie', 'Tan', staleDays + 9, today, { agentName: 'Marcus Chen' }),
      clientRef('c-51a9', 'Hannah', 'Lim', staleDays + 5, today, { agentName: 'Sarah Lee' }),
      clientRef('c-2c87', 'Arjun', 'Pillai', staleDays + 3, today, { agentName: 'Priya Nair' }),
      clientRef('c-7e20', 'Omar', 'Farouk', staleDays + 1, today, { agentName: 'Sarah Lee' }),
    ],
    withoutAccount: [
      clientRef('c-3b44', 'Grace', 'Teo', 4, today, { agentName: 'Sarah Lee' }),
      clientRef('c-8d02', 'Nurul', 'Huda', 6, today, { agentName: 'Elena Baskin' }),
      clientRef('c-4a61', 'Kenji', 'Sato', 11, today, { agentName: 'Daniel Koh' }),
    ],
    rejectedVerification: [
      clientRef('c-6e75', 'Liam', 'Walsh', 3, today, { agentName: 'Michael Ng' }),
      clientRef('c-1f38', 'Mei Ling', 'Ong', 8, today, { agentName: 'Aisha Rahman' }),
    ],
  }
}

// Demo extension: the contract's action-items endpoint returns transaction
// totals only. The G3 queue also needs the clients behind failed or
// long-pending transactions, so the mock adds them here.
function flaggedTransactions(scope) {
  const rows = [
    { clientId: '1', firstName: 'John', lastName: 'Doe', transactionId: 1003, status: 'FAILED', agentName: 'Sarah Lee' },
    { clientId: '1', firstName: 'John', lastName: 'Doe', transactionId: 1002, status: 'PENDING', agentName: 'Sarah Lee' },
    { clientId: 'c-9f10', firstName: 'Wei Jie', lastName: 'Tan', transactionId: 1187, status: 'FAILED', agentName: 'Marcus Chen' },
  ]
  return scope === 'AGENT' ? rows.filter((row) => row.agentName === 'Sarah Lee') : rows
}

// Demo extension: most active clients by transaction value, for G4.
function topClients(scope, range) {
  const random = randomFor(`top-${scope}-${range.from}-${range.to}`)
  const names = [
    ['1', 'John', 'Doe'],
    ['2', 'Jane', 'Smith'],
    ['c-51a9', 'Hannah', 'Lim'],
    ['c-3b44', 'Grace', 'Teo'],
    ['c-7e20', 'Omar', 'Farouk'],
  ]
  return names
    .map(([clientId, firstName, lastName]) => {
      const count = Math.round(between(random, 3, 18))
      return { clientId, firstName, lastName, count, value: (count * between(random, 700, 2600)).toFixed(2) }
    })
    .sort((a, b) => Number(b.value) - Number(a.value))
}

// ---------- Envelope ----------

function ok(data) {
  return { status: 'OK', data, reason: null }
}

const SORT_KEYS = {
  clientsOnboarded: (row) => row.clientsOnboarded,
  verificationRate: (row) => row.verificationRate,
  accountsOpened: (row) => row.accountsOpened,
  bookedInitialDeposits: (row) => Number(row.bookedInitialDeposits),
}

/**
 * Returns a mock response for one dashboard endpoint.
 *
 * @param {'overview'|'portfolio'|'transactions'|'action-items'|'agents'} endpoint
 * @param {{ role: 'ADMIN'|'AGENT', from: string, to: string, staleDays?: number, sort?: string }} options
 */
export function getDashboard(endpoint, { role, from, to, staleDays = STALE_DAYS_DEFAULT, sort = 'clientsOnboarded' }) {
  const range = { from, to }
  const scope = role === 'ADMIN' ? 'ADMIN' : 'AGENT'
  const allFigures = roster.map((agent) => agentFigures(agent, range))
  const figures = scope === 'ADMIN'
    ? allFigures
    : allFigures.filter((row) => row.agentId === String(CURRENT_AGENT_ID))

  const clients = clientStats(range, scope, figures)
  const accounts = accountStats(range, scope, figures, clients)
  const transactions = transactionStats(range, scope)
  const panels = {}

  if (endpoint === 'overview') {
    panels.clients = ok(clients)
    panels.accounts = ok(accounts)
    panels.transactions = ok(transactions)
    if (scope === 'ADMIN') {
      panels.agents = ok(roster)
    } else {
      // Demo extension: the anonymous team comparison (G5).
      const cohort = allFigures.filter((row) => row.enabled).map((row) => row.clientsOnboarded).sort((a, b) => a - b)
      const mid = Math.floor(cohort.length / 2)
      const median = cohort.length % 2 ? cohort[mid] : (cohort[mid - 1] + cohort[mid]) / 2
      panels.team = ok({
        metric: 'clientsOnboarded',
        mine: figures[0].clientsOnboarded,
        teamMedian: cohort.length >= TEAM_MIN_COHORT ? median : null,
        cohortSize: cohort.length,
        minCohort: TEAM_MIN_COHORT,
      })
    }
  } else if (endpoint === 'portfolio') {
    panels.clients = ok(clients)
    panels.accounts = ok(accounts)
  } else if (endpoint === 'transactions') {
    panels.transactions = ok({ ...transactions, topClients: topClients(scope, range) })
    if (scope === 'ADMIN') {
      const ageSeconds = 12 * 60
      panels.ingestion = ok({
        lastSyncedAt: new Date(Date.now() - ageSeconds * 1000).toISOString(),
        ageSeconds,
        warnAfterSeconds: 60 * 60,
      })
    }
  } else if (endpoint === 'action-items') {
    panels.clients = ok(actionItems(scope, staleDays, to))
    panels.transactions = ok({ ...transactions, flagged: flaggedTransactions(scope) })
  } else if (endpoint === 'agents') {
    if (scope !== 'ADMIN') {
      throw new Error('403: /api/dashboard/agents is admin only')
    }
    const byMetric = SORT_KEYS[sort] ?? SORT_KEYS.clientsOnboarded
    panels.agents = ok({
      sortedBy: sort,
      rows: [...allFigures].sort((a, b) => byMetric(b) - byMetric(a)),
    })
  }

  return {
    generatedAt: new Date().toISOString(),
    window: range,
    scope,
    panels,
  }
}
