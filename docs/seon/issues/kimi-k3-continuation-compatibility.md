---
type: issue
status: open
severity: friction
tags: [issue, agent, capability]
---

# Prove Kimi K3 completion and continuation compatibility

## Problem

Kimi K3's OpenAI-compatible single-response completion, deadline, and evidence
contracts work. Provider-native multi-turn tool continuation remains
unimplemented and unproven.

Commit `003251ef` closes the one-shot completion, deadline, and evidence
portion in source. The exact-artifact live gate passed at `6e3b741d`; this issue
remains open only for any future provider-native tool continuation.

- K3 documents `max_completion_tokens` and deprecates `max_tokens`. The named
  planning variant now selects that field as ordinary capability data while
  DeepSeek retains `max_tokens`.
- K3 requires the complete assistant message, including reasoning content, on
  a later tool-call turn. The adapter deliberately excludes
  `reasoning_content` from agent-visible text and Seon's agent loop does not
  currently execute a provider-native continuation loop. Existing tool tests
  prove only request emission and response parsing.
- The shipped `:planning` variant now carries a 300-second adapter timeout and
  a distinct 360-second outer attempt fence; ordinary agents retain the
  process-default outer bound.
- A response with `finish_reason="length"`, empty visible content, and only
  reasoning content previously returned success. The paid 4,096-token probe
  demonstrated exactly this shape: 4,093 completion/reasoning tokens, no code.
  The turn then records no evals and can spend more turns on the ordinary
  no-form stop path instead of returning a truncation error.
- Finish reason, truncation, completion-limit field, usage, and both timeout
  layers now persist on attempt facts, including provider-returned failures.
- `:batch` now uses one nonstreaming Chat Completion. `:stream` alone uses the
  SDK stream. Single-response visible text works; K3 continuation remains
  unimplemented.

## Evidence

- [Kimi K3 quickstart](https://platform.kimi.ai/docs/guide/kimi-k3-quickstart)
  documents the complete-message continuation rule.
- The same quickstart's “Important limits” section documents
  `max_completion_tokens`; the accepted deprecated compatibility field is
  visible in the API reference reached from its documentation index.
- `docs/prds/generate-code/research/design-seam-audit-2026-07-19.md` records two
  paid planning calls plus the final isolated exact-artifact normal and forced-
  truncation calls. The normal call evaluated `42`; the 32-token call recorded
  `length`, truncation, retained usage, and no evals. Neither exercises tool
  continuation.
- A config-free isolated restart preserved the two distinct per-agent profiles
  and both complete attempt facts. The isolated cluster then closed cleanly
  with its pod absent; default and ACME were untouched.

## Owner

The existing provider-neutral request/response contract in `seon.ai` and the
one OpenAI-compatible adapter. Do not add a Kimi-specific provider or a second
agent loop.

## Dependency grounding

- `openai` 6.42.0 is selected in `bun.lock`; the maintained source is pinned at
  `reference-code/openai-node` commit `6f849f4f`.
- `resources/chat/completions/completions.ts` exposes the ordinary
  nonstreaming `create` overload and both completion-limit fields.
- `lib/ChatCompletionStream.ts` concatenates known visible-content deltas but
  assigns unknown delta fields onto the snapshot, so it is not an authority
  for reconstructing Kimi's multi-delta `reasoning_content`.
- The first-party owners are `seon.ai/resolved-config-from-rows`,
  `seon.ai.openai-compat/request-params` and `complete`, plus
  `seon.agent.turn/bounded-llm-attempt!` and `attempt-row`.

## Acceptance

- Model capability data selects the correct completion-limit wire field without
  changing DeepSeek's request shape.
- The selected agent/variant deadline can exceed the cluster default without a
  model-name branch, while lifecycle cancellation still aborts promptly.
- Empty length-limited output is a non-retryable truncation error carrying the
  provider usage. Nonempty length-limited output remains salvageable text but
  is explicitly marked truncated.
- Attempt facts retain finish reason, truncation, usage, and the effective
  adapter and outer deadlines for successes and provider-returned failures.
- `:batch` uses one nonstreaming Chat Completions response; `:stream` remains
  the only mode that requests a stream. Tests cover reasoning-before-content,
  terminal usage, `stop`, `length`, and `tool_calls` without feeding reasoning
  text into the REPL parser.
- A live K3 test proves the configured output cap is honored. **Passed at
  `6e3b741d`:** the agent-local cap of 32 produced exactly 32 completion tokens,
  `finish_reason=length`, `truncated? true`, retained usage, and the expected
  no-visible-text error.
- If provider-native tool continuation becomes an agent-loop feature, a live K3
  test returns the complete assistant message unchanged and pairs every tool
  result with its call ID.
- Until that proof exists, maintained documentation claims only
  single-response K3 compatibility.
