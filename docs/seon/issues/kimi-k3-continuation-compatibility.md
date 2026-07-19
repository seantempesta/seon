---
type: issue
status: open
severity: friction
tags: [issue, agent, capability]
---

# Prove Kimi K3 completion and continuation compatibility

## Problem

Kimi K3's OpenAI-compatible single-response path works, but two stronger wire
contracts are not yet proven.

- K3 documents `max_completion_tokens` and deprecates `max_tokens`.
  `seon.ai.openai-compat/request-params` currently sends `max_tokens`, which K3
  accepts today. Replacing it globally would be wrong because DeepSeek still
  consumes that field.
- K3 requires the complete assistant message, including reasoning content, on
  a later tool-call turn. The adapter deliberately excludes
  `reasoning_content` from agent-visible text and Seon's agent loop does not
  currently execute a provider-native continuation loop. Existing tool tests
  prove only request emission and response parsing.

## Evidence

- [Kimi K3 quickstart](https://platform.kimi.ai/docs/guide/kimi-k3-quickstart)
  documents the complete-message continuation rule.
- The same quickstart's “Important limits” section documents
  `max_completion_tokens`; the accepted deprecated compatibility field is
  visible in the API reference reached from its documentation index.
- `docs/prds/generate-code/research/design-seam-audit-2026-07-19.md` records two
  paid single-response calls; neither exercises tool continuation.

## Owner

The existing provider-neutral request/response contract in `seon.ai` and the
one OpenAI-compatible adapter. Do not add a Kimi-specific provider or a second
agent loop.

## Acceptance

- Model capability data selects the correct completion-limit wire field without
  changing DeepSeek's request shape.
- A live K3 test proves the configured output cap is honored.
- If provider-native tool continuation becomes an agent-loop feature, a live K3
  test returns the complete assistant message unchanged and pairs every tool
  result with its call ID.
- Until that proof exists, maintained documentation claims only
  single-response K3 compatibility.
