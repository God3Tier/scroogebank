import { DEFINITIONS } from './definitions.js'

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
      <p className="demo-note">
        Demo mockup: figures are synthetic and follow the dashboard-service API contract.
      </p>
    </details>
  )
}

export default MetricDefinitions
