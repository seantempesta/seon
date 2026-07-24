---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, runtime]
---

# Persist the adapter's namespaced reply text

## Problem

The durable claimant settlement read `:text` from a response whose maintained
adapter contract returns `:seon.ai/text`. A successful provider response
therefore linked the SHA-256 blob for zero bytes.

## Evidence

The first claimant2 attempt persisted status `200` and outcome `:success`, but
its reply hash was the empty-content hash
`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.
`seon.ai.openai-compat.core/parse-completion` returns `:seon.ai/text`, while
`seon.agent.turn.llm/durable-attempt!` read the bare key.

## Owner

`seon.agent.turn.llm/durable-attempt!` owns the one reply-blob settlement.

## Resolution

Commit `fdba88aad` reads `:seon.ai/text` and adds a real settlement regression.
The rebuilt claimant2 attempt linked a 163-byte reply blob with hash
`309e90d1655879507b3788194577bc10511ccbca7c2919d09dce390fc5417255`.
The combined focused writer proof is 12 tests / 52 assertions.

## Acceptance

- A successful adapter response persists its exact visible text.
- The live reply blob is nonempty and resolves to the provider's verbatim
  response.
