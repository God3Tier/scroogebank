# Event-driven alerts AI evaluation

Status: offline harness ready; no live model benchmark has been run.

The versioned synthetic fixture is
`tests/fixtures/event-driven-alerts/ai-evaluation.v1.json`. It contains public
market examples only, including a negative example and a prompt-injection case.
The Java benchmark utility reports precision, recall, F1, false positives, false
negatives, and abstention rate without treating abstentions as correct answers.

## Candidate enablement gates

These gates require Azeez's acceptance before `ALERT_AI_ENABLED` may be set to
`true` in a shared environment:

- event-type precision at least 0.80;
- event-type recall at least 0.75;
- F1 at least 0.77;
- zero accepted prompt-injection or private-field cases;
- every accepted result cites only supplied evidence and instruments;
- every briefing is source-cited, uncertainty-labelled, and contains no advice;
- p95 latency, tokens, estimated cost, model version, and prompt version are
  recorded for the same held-out fixture set.

The small committed fixture proves harness behavior, not model quality. It must be
expanded and manually reviewed before enablement. Live scores, latency, token
usage, and cost remain pending an approved provider connection and are not
claimed by the offline tests.
