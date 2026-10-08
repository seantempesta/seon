---
title: "Card CX-5 A context over the model's window refuses before the call"
type: reference
status: active
created: 2026-10-08
---

# CX-5 A context over the model's window refuses before the call

Ledger row CX-5 (group D; the window half of retired S4-04). Spec: agent-loop.md §3.4 Storage roles (prefix, history and
block together over the provider's window refuses; nothing is clipped). Lane: Sonnet.

## Why now

CX-1 bypasses the prompt walk and with it the only pre-call size check (`:seon.config.ai/prompt-token-budget` inside
selection). Without this card an over-window context reaches the provider and fails as a provider error.

## What becomes true

Before the provider call, `seon.cluster.prompt/window-check` sums `seon.ai.tokens/estimate` over the system message
and the user text; over `:seon.ai.model/context-window-tokens` it answers `:seon.cluster.prompt/over-window-error`
(extends `:seon.error/base`, with the estimate and the window as data), which closes the run through the existing
`fail!` path. Nothing is dropped from history.

## Functions

- ADD `seon.cluster.prompt/window-check`
  `{:malli/schema [:=> [:cat [:int {:min 1}] :string :string] [:or :nil :seon.cluster.prompt/over-window-error]]}`
  `[window system text]`.
- ADD the error schema in `seon.cluster.prompt.edn`.
- CHANGE the model call site in `seon.turn` (after CX-1's `model-context`): a `window-check` error is the `refusal`.

## Deleted

The proc-held history of retired S4-04 is not built: history is read per pass (3.3 ms for 38 evaluations), which is
proportional to what is sent anyway. Net ≈ +35 source, +30 test.

## Proof

`seon.cluster.prompt-test/an-over-window-context-refuses-without-a-provider-call` (new): a model row with
`:seon.ai.model/context-window-tokens` 200 and ten ~2 KB exchanges: the pass answers the over-window error as data,
the HTTP fixture received no request, and `seon.eval/of-agent` still returns every evaluation.

## Cost

A linear scan of the sent text (no printing): follows the text length.

## Dependencies and order

CX-1. Files: `src/seon/cluster/prompt.clj`, `resources/seon/schemas/seon.cluster.prompt.edn`, `src/seon/turn.clj`,
`test/seon/cluster/prompt_test.clj`.
