---
type: issue
status: open
severity: friction
tags: [issue, runtime, contracts]
---

# Read terminal failure from the settlement key its producer writes

## Problem

The run-form consumer reads `:seon.cluster.loop/failure` from `settle!`'s
result, but no settlement producer writes that key. Refusal settlement writes
the declared flat value under `:seon.error/value`.

This leaves a successfully committed phase failure indistinguishable from an
ordinary release at the local report branch unless another condition happens
to classify it.

## Evidence

Contract grounding for
[[settle-is-public-without-a-complete-contract]] queried every producer and
consumer in `src/seon/cluster/loop.clj`. `evaluation-terminal-data` returns
`:seon.cluster.loop/settled`, `:seon.cluster.loop/evaluation`,
`:seon.cluster.loop/receipt`, staged writes, and transaction data.
`refusal-terminal-data` returns `:seon.error/value` plus transaction data.
`settle!` adds `:seon.cluster.loop/outcome` and, on a refused first commit,
`:seon.cluster.loop/refused-outcome`. The only occurrence of
`:seon.cluster.loop/failure` is the read at `src/seon/cluster/loop.clj:1461`.

This is independent of adding the missing public contract and was not folded
into that phase-one prepared fix.

## Owner

`seon.cluster.loop` terminal-result consumption after the `settle!` contract
lands.

## Acceptance

- A committed refusal settlement makes the turn report `:error`, not
  `:released`.
- The consumer reads the declared producer key; no alias or duplicate failure
  field is introduced.
- One regression injects a post-receipt phase failure and asserts both durable
  settlement facts and the returned turn outcome.
