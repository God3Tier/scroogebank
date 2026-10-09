# Event-driven alerts AI evaluation

Status: offline harness, versioned report evaluator, and mocked Bedrock Runtime
adapter ready; no live model benchmark has been run.

The versioned synthetic fixture is
`tests/fixtures/event-driven-alerts/ai-evaluation.v1.json`. It contains 18
synthetic public-market examples covering all seven event types, five negative
or safety cases, prompt injection, and private-field rejection. Its current
`labelStatus` is `AI_DRAFT_REQUIRES_HUMAN_REVIEW`; Azeez and Hayyun must inspect
every label before changing it to `HUMAN_REVIEWED`.
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

The committed fixture proves harness behavior and provides an initial balanced
test set; it does not prove model quality. Machine validation enforces unique IDs,
synthetic identifiers, event-type coverage, negative cases, and expected safety
screen outcomes. Manual label review is still required before enablement. Live
scores, latency, token usage, and cost remain pending an approved provider
connection and are not claimed by the offline tests.

The adapter targets the Bedrock Runtime Converse API with
`global.openai.gpt-5.6-luna`, as documented by the official
[Luna model card](https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-openai-gpt-56-luna.html)
and [AWS SDK for Java Converse example](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/java_bedrock-runtime_code_examples.html).
It remains fail-closed and default-off; its unit tests use a mocked client and do
not prove IAM, routing, availability, latency, quality, or cost.

## Producing a benchmark report

Record one observation for every fixture ID using
`tests/fixtures/event-driven-alerts/ai-benchmark-run.schema.json`. The Bedrock
adapter returns provider-reported input/output tokens, latency, and stop reason;
the run file also records the estimated USD cost for that exact run. Do not put
prompts, source excerpts, credentials, or client data in the run file.

After compiling the service, run the report entry point with the fixture and run
paths as its two arguments:

```text
bank.alert.ai.evaluation.AiBenchmarkReportCli
  tests/fixtures/event-driven-alerts/ai-evaluation.v1.json
  <approved-run.json>
```

The JSON report includes fixture/model/prompt versions, precision, recall, F1,
abstention rate, token totals, p95 latency, estimated cost, safety failures, and
an explicit gate result. Missing usage metadata for an invoked model also fails
the gate. Missing, duplicate, or extra fixture observations fail the report
rather than silently biasing metrics. A technically passing report sets
`eligibleForEnablement=false` unless the fixture is explicitly `HUMAN_REVIEWED`.
It does not replace the IAM/routing check or ADR acceptance.
