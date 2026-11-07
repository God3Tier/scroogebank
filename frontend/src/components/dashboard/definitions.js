// Metric definitions from ADR-0038 (as amended by ADR-0060 and ADR-0062),
// shown in the UI so no figure can be misread (requirement S3). A new figure
// needs a row in ADR-0038 first.

export const DEFINITIONS = {
  clientsOnboarded: {
    term: 'Clients onboarded',
    text: 'Clients created in the window, counted against their current agent.',
    caveat: 'A reassigned client counts for the agent it was moved to.',
  },
  verifiedShare: {
    term: 'Verified share',
    text: 'Verified clients as a share of all clients in scope.',
  },
  verificationRate: {
    term: 'Verification rate',
    text: 'Verified clients divided by all clients created in the window.',
    caveat: 'Excludes clients created before the window.',
  },
  bookedInitialDeposits: {
    term: 'Booked initial deposits',
    text: 'Sum of opening deposits for accounts opened in the window.',
    caveat: 'Opening deposit only, not a current balance. Shown per currency; never added across currencies.',
  },
  accountsOpened: {
    term: 'Accounts opened',
    text: 'Accounts with an opening date in the window, counted against their owning agent.',
  },
  accountsPerClient: {
    term: 'Accounts per client',
    text: 'Accounts divided by clients over the same scope. At most 6, one per account type.',
  },
  failureRate: {
    term: 'Transaction failure rate',
    text: 'Failed transactions divided by all transactions dated in the window.',
    caveat: 'Status is as supplied by the external source.',
  },
  pendingBacklog: {
    term: 'Pending backlog',
    text: 'Pending transactions dated more than 24 hours ago.',
  },
  transactionValue: {
    term: 'Transaction value',
    text: 'Sum of transaction amounts in the window.',
    caveat: 'Shown in its currency; not added up when transactions span more than one.',
  },
  staleUnverified: {
    term: 'Stale unverified clients',
    text: 'Clients not yet verified and created more than 7 days ago.',
    caveat: 'The list shows the oldest 50; the count covers all.',
  },
  teamMedian: {
    term: 'Team median',
    text: 'Median clients onboarded across agents who created a client in the last 12 months. Anonymous.',
    caveat: 'Last 7, 30 or 90 days only, and shown only when the group has at least 5 agents.',
  },
}

// Tooltip text for a tile: definition plus caveat.
export function hintFor(key) {
  const definition = DEFINITIONS[key]
  return definition ? [definition.text, definition.caveat].filter(Boolean).join(' ') : undefined
}

// Always an explicit metric, never a composite score (ADR-0002).
export const SORT_OPTIONS = [
  { value: 'clientsOnboarded', label: 'Clients onboarded' },
  { value: 'verificationRate', label: 'Verification rate' },
  { value: 'accountsOpened', label: 'Accounts opened' },
  { value: 'bookedInitialDeposits', label: 'Booked initial deposits' },
]

export const DEPOSITS = 'bookedInitialDeposits'
// dashboard-service ranks deposits in SGD unless told otherwise (dashboard-api.yaml).
export const DEFAULT_CURRENCY = 'SGD'

// The request parameters for a ranking. Deposits are ranked in one currency at
// a time (ADR-0062); the other metrics take none.
export function rankingParams(sort, currency) {
  return sort === DEPOSITS ? { sort, currency } : { sort }
}
