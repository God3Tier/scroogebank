import { Bar, BarChart, Cell, LabelList, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'

import { axisTick, barCursor, chartColors, statusColors } from '../../styles/chartTheme.js'
import { formatNumber } from './format.js'

/**
 * Horizontal bars for a { key: count } breakdown. Every bar is labelled with
 * its value, so the figures do not depend on colour.
 *
 * @param rows [{ key, name, value }]
 */
function BreakdownChart({ title, rows, labelWidth = 96 }) {
  const height = rows.length * 34 + 8

  return (
    <figure className="breakdown">
      {title && <figcaption>{title}</figcaption>}
      <div className="chart-box-fluid">
        <ResponsiveContainer width="100%" height={height}>
          <BarChart data={rows} layout="vertical" margin={{ top: 0, right: 48, bottom: 0, left: 0 }}>
            <XAxis type="number" hide />
            <YAxis type="category" dataKey="name" width={labelWidth} tick={axisTick} axisLine={false} tickLine={false} />
            <Tooltip formatter={(value) => formatNumber(value)} cursor={barCursor} />
            <Bar dataKey="value" barSize={16} radius={[0, 4, 4, 0]} isAnimationActive={false}>
              {rows.map((row) => (
                <Cell key={row.key} fill={statusColors[row.key] ?? chartColors.navy} />
              ))}
              <LabelList dataKey="value" position="right" formatter={(value) => formatNumber(value)} fill={chartColors.text} fontSize={12} />
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
    </figure>
  )
}

export default BreakdownChart
