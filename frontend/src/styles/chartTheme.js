// Chart colours for Recharts, taken from the Stitch design tokens in index.css.
// Recharts takes colours as props, so they live here instead of in CSS.

export const chartColors = {
  navy: '#1f3b57',
  teal: '#1e8e7e',
  blue: '#2980b9',
  amber: '#9a6700',
  red: '#ba1a1a',
  grey: '#73777e',
  grid: '#c3c6ce',
  text: '#43474d',
  rowHover: '#f1f5f9',
}

// Colour per status key, so the same status reads the same everywhere.
export const statusColors = {
  COMPLETED: chartColors.teal,
  VERIFIED: chartColors.teal,
  active: chartColors.teal,
  PENDING: chartColors.amber,
  pending: chartColors.amber,
  UNVERIFIED: chartColors.grey,
  inactive: chartColors.grey,
  FAILED: chartColors.red,
  REJECTED: chartColors.red,
  closed: chartColors.red,
  DEPOSIT: chartColors.teal,
  WITHDRAWAL: chartColors.navy,
  me: chartColors.teal,
  median: chartColors.grey,
}

export const axisTick = { fill: chartColors.text, fontSize: 12 }
export const legendStyle = { fontSize: 13 }
export const barCursor = { fill: chartColors.rowHover }
