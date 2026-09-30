export const initialTasks = [
  {
    id: 'RH-2048',
    client: 'Client SG-1048',
    initials: 'SG',
    segment: 'Premier Banking',
    relationshipSince: '2018',
    assignedAgent: 'Maya Chen',
    severity: 'HIGH',
    status: 'OPEN',
    score: 32,
    confidence: '92%',
    updated: '8 minutes ago',
    analysisWindow: '01 Jun – 28 Sep 2026',
    signals: [
      'RECURRING_CREDIT_INTERRUPTION',
      'TRANSACTION_ACTIVITY_DECLINE',
      'SUSTAINED_NET_OUTFLOW',
    ],
    evidence: [
      {
        title: 'Recurring credit interrupted',
        icon: '↙',
        tone: 'high',
        metric: '67 days',
        detail: 'Since the last expected recurring credit',
        context: 'Previously observed every 30 days across 3 occurrences',
      },
      {
        title: 'Transaction activity declined',
        icon: '↘',
        tone: 'medium',
        metric: '−66.7%',
        detail: 'Activity in the latest 30-day period',
        context: '8 transactions compared with a baseline average of 24',
      },
      {
        title: 'Sustained net outflow',
        icon: '→',
        tone: 'medium',
        metric: '2 periods',
        detail: 'Withdrawals exceeded deposits',
        context: 'Synthetic aggregate net flow: −SGD 4,500',
      },
    ],
    explanation:
      'Client activity has declined over the last 30 days, with a sustained net outflow and interruption in recurring credit activity. This task is recommended so the assigned agent can proactively review the relationship and decide whether follow-up is needed.',
    nextAction:
      'Review the verified account activity and recent service interactions before deciding whether client contact is appropriate.',
    history: [
      { label: 'Task created', detail: '3 signals combined into one relationship task', time: 'Today, 09:42', kind: 'created' },
      { label: 'Analysis completed', detail: 'Ruleset relationship-health-v1', time: 'Today, 09:41', kind: 'analysis' },
    ],
  },
  {
    id: 'RH-2039',
    client: 'Client SG-0821',
    initials: 'SG',
    assignedAgent: 'Maya Chen',
    severity: 'MEDIUM',
    status: 'IN_REVIEW',
    updated: '1 hour ago',
    signals: ['TRANSACTION_ACTIVITY_DECLINE'],
  },
  {
    id: 'RH-2027',
    client: 'Client SG-0614',
    initials: 'SG',
    assignedAgent: 'Maya Chen',
    severity: 'LOW',
    status: 'COMPLETED',
    updated: 'Yesterday',
    signals: ['RECURRING_CREDIT_INTERRUPTION'],
  },
  {
    id: 'RH-2018',
    client: 'Client SG-0392',
    initials: 'SG',
    assignedAgent: 'Maya Chen',
    severity: 'MEDIUM',
    status: 'DISMISSED',
    updated: '2 days ago',
    signals: ['SUSTAINED_NET_OUTFLOW'],
  },
]

export function freshTasks() {
  return structuredClone(initialTasks)
}
