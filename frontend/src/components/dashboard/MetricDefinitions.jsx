import { DEFINITIONS, NOT_SHOWN } from './definitions.js'

// Collapsed by default: the definitions are reference material, and each tile
// already carries its own definition as a tooltip.
function MetricDefinitions({ keys }) {
  return (
    <details className="definitions">
      <summary>
        <span className="material-symbols-outlined" aria-hidden="true">info</span>
        Metric definitions
        <span className="definitions-hint">{keys.length} definitions</span>
        <span className="material-symbols-outlined definitions-chevron" aria-hidden="true">expand_more</span>
      </summary>
      <dl>
        {keys.map((key) => {
          const definition = DEFINITIONS[key]
          return (
            <div key={key}>
              <dt>{definition.term}</dt>
              <dd>
                {definition.text}
                {definition.caveat && <span className="caveat"> {definition.caveat}</span>}
              </dd>
            </div>
          )
        })}
      </dl>
      <div className="definitions-omitted">
        <h3>Not shown, and why</h3>
        <dl>
          {NOT_SHOWN.map(({ term, reason }) => (
            <div key={term}>
              <dt>{term}</dt>
              <dd>{reason}</dd>
            </div>
          ))}
        </dl>
      </div>
    </details>
  )
}

export default MetricDefinitions
