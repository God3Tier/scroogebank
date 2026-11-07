import {
  CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'

import { axisTick, chartColors, legendStyle } from '../../styles/chartTheme.js'
import { formatDate } from './format.js'

// Daily series. Each line has its own dash pattern as well as its own colour,
// so the chart still reads without colour.
const DASHES = ['', '6 4', '2 3']
const COLORS = [chartColors.teal, chartColors.navy, chartColors.blue]

/**
 * @param data  rows of { date, [series.key]: number }
 * @param series [{ key, label, axis: 'left' | 'right' }]
 */
function TrendChart({ data, series, height = 'md' }) {
  const hasRightAxis = series.some((line) => line.axis === 'right')

  return (
    <div className={`chart-box chart-box-${height}`}>
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: -12 }}>
          <CartesianGrid stroke={chartColors.grid} strokeDasharray="3 3" vertical={false} />
          <XAxis dataKey="date" tickFormatter={formatDate} tick={axisTick} minTickGap={24} />
          <YAxis yAxisId="left" tick={axisTick} allowDecimals={false} />
          {hasRightAxis && <YAxis yAxisId="right" orientation="right" tick={axisTick} allowDecimals={false} />}
          <Tooltip labelFormatter={formatDate} />
          <Legend iconType="plainline" wrapperStyle={legendStyle} />
          {series.map((line, i) => (
            <Line
              key={line.key}
              yAxisId={line.axis ?? 'left'}
              type="monotone"
              dataKey={line.key}
              name={line.label}
              stroke={COLORS[i % COLORS.length]}
              strokeWidth={2}
              strokeDasharray={DASHES[i % DASHES.length]}
              dot={false}
            />
          ))}
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}

export default TrendChart
