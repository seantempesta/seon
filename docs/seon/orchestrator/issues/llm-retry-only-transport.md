---
type: issue
status: open
severity: friction
tags: [issue, agent, ai]
---
# LLM-call retry covers only transport errors, not 429/503/timeout

## Problem

The agent loop's LLM-call retry (`seon.agent/call-llm!`) retries exactly
ONCE, after a 2s backoff, and ONLY when the error is transport-shaped
(`:seon.ai/transport?` — a connection that failed before any HTTP
status, e.g. DNS blip or dropped socket). Every other transient failure
class is dropped on the first attempt:

- HTTP 429 (rate limited) — NOT retried
- HTTP 503 (overloaded / temporarily unavailable) — NOT retried
- Wall-clock timeout (`:seon.ai/timeout?`) — NOT retried (explicitly
  excluded; `transport-error?` checks only `:transport?`)
- Any other HTTP 5xx — NOT retried

This is currently INTENTIONAL and locked by tests: the adapter
(`seon.ai.openai-compat/error->envelope`,
`seon.ai.anthropic`) marks HTTP-status errors NON-retryable on purpose
(`maxRetries 0` on the SDK client — "the agent loop is the sole retry
authority"), and `test/seon/agent_retry_test.cljs` asserts
"HTTP/processing/timeout errors NEVER retry". So this is a deliberate
policy that needs revisiting, not an accidental omission.

## Impact

The most common transient LLM failures in practice are 429 rate-limits
and 503 overloaded responses (and, less often, gateway timeouts) — NONE
of which retry today. A single transient 429/503 ends the wake with a
"LLM call failed" system line, when one backoff-retry would very likely
have succeeded. The agent recovers to `:idle` (it does not go deaf — see
below), but the human gets a failed turn for a blip that should have
been invisible.

## What is NOT broken (verified 2026-06-17)

State recovery on LLM failure is correct: an `:seon.ai/error` response
flows through `ask-and-eval!`'s error branch (no throw), closes the turn
`:error` with a visible "⚠ LLM call failed — <msg>" self-message, and
`with-turn!` resets `:seon.agent/state` to `:idle`. As of the
deaf-after-one-message fix (same date), even a FAILED close-tx funnels
through `seon.agent/ensure-idle!`, so an LLM failure can never leave the
agent stuck `:running`. The gap here is purely about NOT RETRYING
recoverable failures — not about a crash or a stuck agent.

## Design Direction

- Add a retryable-HTTP-status predicate (429, 503, and optionally 500/502
  with care) alongside the existing `transport-error?` check in
  `call-llm!`; retry those once with backoff, same as transport today.
  429s should honor `Retry-After` when present.
- Decide the timeout policy deliberately: a gateway timeout MAY be worth
  one retry, but it doubles worst-case latency — measure before enabling.
- Consider >1 retry with exponential backoff + jitter for 429/503
  specifically (rate limits often need more than one wait).
- This crosses a tested invariant: update
  `test/seon/ai/openai_compat_test.cljs` /
  `test/seon/ai/anthropic_test.cljs` (the "HTTP errors must NEVER look
  retryable" assertions) and `test/seon/agent_retry_test.cljs` in the
  same patch — do NOT add a parallel retry path.

## File Refs

- `src/seon/agent.cljs` — `call-llm!` (the retry authority),
  `transport-error?`, `llm-transport-retry-backoff-ms`
- `src/seon/ai/openai_compat.cljs` — `error->envelope` (status →
  envelope mapping), `make-client` (`maxRetries 0`)
- `src/seon/ai/anthropic.cljs` — sibling adapter, same contract
- `test/seon/agent_retry_test.cljs` — the retry contract under test
- `test/seon/ai/openai_compat_test.cljs`,
  `test/seon/ai/anthropic_test.cljs` — "HTTP errors NEVER retry"

## Severity

friction

## Origin

Raised by Sean (via Amit's observation) during the
deaf-after-one-message investigation, 2026-06-17.
