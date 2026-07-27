---
type: issue
status: superseded
severity: blocker
tags: [issue, agent, runtime, database]
---

# Refuse model dispatch when turn allocation fails

## Problem

`seon.agent.driver/open-turn!` converts an allocation error to `nil`, but
`process-message!` destructures that value and continues into context
derivation and provider dispatch with a nil turn id.

## Evidence

At commit `c03ff91eb`, `open-turn!` returns a map only when the allocation has
no `:seon.error/message`. Its caller does not branch on absence before calling
`model-request` and `llm-transport!`.

This is the same silent-return-value class as the resolved plan transaction
defect, but it is outside the successful-turn measurement: a completed
measured turn necessarily has a committed turn ref.

## Owner

`seon.agent.driver/process-message!` owns interpreting the turn-allocation
result before any external provider work is admitted.

## Acceptance

- A forced turn-allocation error makes zero provider and eval calls.
- The run closes with the same flat error durably represented.
- No provider attempt or eval receipt can exist without a committed turn.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
