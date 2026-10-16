export const initialClientHealthTasks = [
  {
    id: 'RH-2048',
    client: 'Client •••• 4821',
    segment: 'Premier Banking',
    relationshipSince: '2018',
    severity: 'HIGH',
    status: 'OPEN',
    agent: 'Maya Chen',
    updated: '8 minutes ago',
    score: 32,
    confidence: 92,
    analysisWindow: '01 Sep – 30 Sep 2026',
    signals: ['RECURRING_CREDIT_INTERRUPTION', 'TRANSACTION_ACTIVITY_DECLINE', 'SUSTAINED_NET_OUTFLOW'],
    summary: 'Long-standing premier relationship showing several material changes in routine account activity.',
    explanation: 'Client activity has declined over the last 30 days, with a sustained net outflow and interruption in recurring credit activity. This task is recommended so the assigned agent can proactively review the relationship and decide whether follow-up is needed.',
    nextAction: 'Review the recent activity with the client and schedule a relationship check-in within two business days.',
    evidence: [
      { icon: 'event_busy', label: 'Recurring credit', value: 'Interrupted', detail: 'Expected monthly credit has not appeared for 67 days.' },
      { icon: 'trending_down', label: 'Transaction activity', value: 'Down 67%', detail: '12 transactions this month, compared with a 36-monthly average.' },
      { icon: 'account_balance_wallet', label: 'Net cash flow', value: '−$18,420', detail: 'Two consecutive analysis periods ended with net outflow.' },
    ],
    history: [
      { title: 'Task created', detail: 'Three relationship signals detected', time: 'Today, 09:42' },
      { title: 'Analysis completed', detail: '30-day relationship review', time: 'Today, 09:41' },
    ],
  },
  {
    id: 'RH-2039', client: 'Client •••• 1936', segment: 'Personal Banking', relationshipSince: '2021',
    severity: 'MEDIUM', status: 'IN_REVIEW', agent: 'Daniel Tan', updated: '34 minutes ago', score: 58, confidence: 86,
    analysisWindow: '01 Sep – 30 Sep 2026', signals: ['TRANSACTION_ACTIVITY_DECLINE'],
    summary: 'Everyday banking relationship with a recent reduction in routine card and transfer activity.',
    explanation: 'Transaction activity has declined across the current analysis window compared with the client’s recent baseline. A relationship review is recommended to understand whether their banking needs have changed.',
    nextAction: 'Review the activity trend and prepare a brief check-in for the client’s next scheduled contact.',
    evidence: [
      { icon: 'trending_down', label: 'Transaction activity', value: 'Down 41%', detail: '23 transactions this month, compared with a 39-monthly average.' },
      { icon: 'calendar_month', label: 'Active days', value: '9 days', detail: 'Activity occurred on 9 days, down from a typical 18 days.' },
    ],
    history: [
      { title: 'Task accepted', detail: 'Daniel Tan began relationship review', time: 'Today, 09:16' },
      { title: 'Task created', detail: 'Activity decline signal detected', time: 'Today, 08:58' },
    ],
  },
  {
    id: 'RH-2027', client: 'Client •••• 7750', segment: 'Premier Banking', relationshipSince: '2016',
    severity: 'LOW', status: 'COMPLETED', agent: 'Maya Chen', updated: 'Yesterday', score: 74, confidence: 81,
    analysisWindow: '01 Aug – 31 Aug 2026', signals: ['RECURRING_CREDIT_INTERRUPTION'],
    summary: 'Established premier relationship reviewed after a temporary change in recurring credit activity.',
    explanation: 'A recurring credit arrived outside its usual schedule during the analysis window. The assigned agent reviewed the relationship and confirmed no further follow-up was needed.',
    nextAction: 'No immediate action required. Continue routine relationship monitoring.',
    evidence: [
      { icon: 'schedule', label: 'Recurring credit', value: '18 days late', detail: 'The regular credit resumed after one delayed cycle.' },
      { icon: 'check_circle', label: 'Current pattern', value: 'Restored', detail: 'Subsequent account activity returned to the expected range.' },
    ],
    history: [
      { title: 'Task completed', detail: 'Review completed; no follow-up required', time: 'Yesterday, 16:20' },
      { title: 'Task accepted', detail: 'Maya Chen began relationship review', time: 'Yesterday, 14:05' },
      { title: 'Task created', detail: 'Recurring credit interruption detected', time: 'Yesterday, 11:32' },
    ],
  },
  {
    id: 'RH-2018', client: 'Client •••• 6214', segment: 'Personal Banking', relationshipSince: '2023',
    severity: 'MEDIUM', status: 'DISMISSED', agent: 'Aisha Rahman', updated: '2 days ago', score: 61, confidence: 84,
    analysisWindow: '01 Aug – 31 Aug 2026', signals: ['SUSTAINED_NET_OUTFLOW'],
    summary: 'Personal banking relationship reviewed after two periods of net account outflow.',
    explanation: 'Sustained net outflow was observed during the analysis window. The assigned agent reviewed the activity and recorded that it matched a known planned transfer.',
    nextAction: 'No outreach required. Continue routine monitoring for new relationship changes.',
    evidence: [
      { icon: 'account_balance_wallet', label: 'Net cash flow', value: '−$7,850', detail: 'Two consecutive analysis periods ended with net outflow.' },
      { icon: 'compare_arrows', label: 'Largest transfer', value: '$5,200', detail: 'The primary outflow was reviewed as a planned transfer.' },
    ],
    history: [
      { title: 'Task dismissed', detail: 'Activity matched a known planned transfer', time: '29 Sep, 15:44' },
      { title: 'Task created', detail: 'Sustained net outflow detected', time: '29 Sep, 10:12' },
    ],
  },
]

export const freshClientHealthTasks = () => structuredClone(initialClientHealthTasks)
