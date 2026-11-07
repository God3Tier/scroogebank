// A report sentence with its [n] marks: the number is the source's place in the report's linked-sources list.
export function cited(text, refs, sources) {
  const marks = (refs || [])
    .map((ref) => (sources || []).findIndex((source) => source.id === ref))
    .filter((index) => index >= 0)
    .map((index) => ` [${index + 1}]`)
    .join('')
  return `${String(text ?? '').replace(/\s+/g, ' ').trim()}${marks}`
}
