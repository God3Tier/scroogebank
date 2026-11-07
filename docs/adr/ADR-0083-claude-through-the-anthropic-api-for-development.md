# ADR-0083: Claude through the Anthropic API as the model on a developer machine

- Status: Proposed (2026-11-05; review pending)
- Decision date/time: 2026-11-05 (Asia/Singapore)
- Decision owner(s): Hayyun (proposer and owner); reviewers: Azeez, Neo Jia Wen (owners of the AI provider decisions)
- Drafted/assisted by AI: Yes; Claude Code (Anthropic)
- Feature/branch: `task/event-driven-alerts/search-on-add`
- Related issue/PR: TBD
- Supersedes: none; adds a second provider beside Bedrock under [ADR-0010](ADR-0010-alert-ai-provider-and-safety.md) and [ADR-0050](ADR-0050-event-search-provider-after-scp-block.md). Nothing about the deployed model changes.
- Superseded by: N/A

## Context

The alert pipeline turns a stored article into an alert with a model: it classifies the event and writes the briefing. The only model gateway is Amazon Bedrock (Nova 2 Lite, in Singapore). On a developer machine without AWS access the model step is off, so an article that passes the search filter never becomes an alert, and the Event Alerts page cannot be tried or demonstrated end to end.

## Decision Drivers

- The whole pipeline, from a search to an alert on the page, must be testable on a laptop.
- Only public text may reach the model, as with Bedrock, and the deployed data boundary must not change.
- No credential may be stored in the repository or logged.
- The model must stay off unless someone switches it on.

## Options Considered

1. **Leave the model off locally.** Nothing is learned about alerts without AWS.
2. **A fixed stand-in that writes a canned summary.** Tests the plumbing, not a model.
3. **The Anthropic Messages API as a second gateway, selected by a setting.** Chosen.
4. **Another hosted provider.** The same shape would work, but Claude is what the team already uses.

## Decision

1. A new gateway, `AnthropicAiModelGateway`, implements the existing `AiModelGateway`. It is chosen only when `alert.ai.provider=anthropic`; the default stays `none`, and Bedrock is chosen with `bedrock`.
2. **Same contract as Bedrock.** The model is given the same system instruction and the same public input, and must answer by calling one tool whose input schema is the closed output schema. The reply's tool input is the structured output. Newer Claude models refuse a forced tool choice, so none is sent; a reply without the tool call is an empty response, which the existing retry handles.
3. **Model and key.** The model name is `alert.ai.model` (environment variable `ALERT_AI_MODEL`, default unchanged). The key is `ALERT_ANTHROPIC_API_KEY`, read only from the environment, never written to a file or the repository, never logged, and never put in an error; its `toString` prints only whether it is set.
4. **Not deployable by accident.** The Terraform stacks set the provider to `bedrock` or `none` and have no variable for `anthropic`, so a deployed task cannot use it. Using it in AWS would need a new decision.
5. **Failures are stable codes** as for Bedrock: MODEL_ACCESS_DENIED, MODEL_NOT_FOUND, MODEL_THROTTLED, MODEL_REQUEST_REJECTED, MODEL_UNAVAILABLE, MODEL_TIMEOUT, MODEL_CLIENT_FAILURE, EMPTY_MODEL_RESPONSE.
6. All other controls apply unchanged: the AI safety screen, the model switch, the monthly token quota, the minimum confidence and the source-only citation check.

## Consequences

- With a key set, the local stack finds real news and writes real alerts, and the page can be tried end to end.
- Article text leaves the machine for Anthropic's API. It is public text only, but it is outside the Singapore and AWS boundary of ADR-0031, so this is for development and demonstration, not for production.
- The model is not the one the AI benchmark gates of ADR-0010 were run on. Its quality is not measured here, and the minimum-confidence rule held back articles it was unsure of.
- Each article costs a few hundred to a few thousand tokens, billed to whoever owns the key. The monthly token quota still stops the model when reached.

## Security and Privacy Impact

No client, account, holding or agent data goes to the model; only the public article excerpt and the event vocabulary, exactly as for Bedrock. The key is a secret held in the developer's environment. A key pasted anywhere else, such as a chat or a ticket, should be treated as exposed and rotated.

## Cost Impact

No AWS cost. Anthropic API tokens per article on the owner's key, bounded by the monthly token quota.

## Operational Impact

None in AWS. On a developer machine set `ALERT_AI_ENABLED=true`, `ALERT_AI_PROVIDER=anthropic`, `ALERT_AI_MODEL` and `ALERT_ANTHROPIC_API_KEY`.

## Validation

Unit tests against a local stand-in for the API check the request, the parsing, the failure codes and that the key is never printed. One real call to `claude-sonnet-5-5` classified a headline as EARNINGS in about 1.6 seconds. On a developer machine, one search for an agent's four companies stored 7 real articles; the model turned 5 into alerts with summaries and held 3 back for low confidence. Not verified: classification quality against the ADR-0010 benchmark, and any use in AWS.

## References

- [ADR-0010](ADR-0010-alert-ai-provider-and-safety.md), [ADR-0031](ADR-0031-xf1-alert-processing-search-region-boundary.md), [ADR-0050](ADR-0050-event-search-provider-after-scp-block.md), [ADR-0081](ADR-0081-search-starts-when-an-agent-adds-a-domain.md)
