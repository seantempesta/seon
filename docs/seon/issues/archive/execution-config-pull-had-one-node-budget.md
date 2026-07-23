---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, database]
---

# Give the execution configuration pull retained-node headroom

## Problem

Execution-child startup pulls the config singleton with `[*]` but sets
Datahike `max-results` to one. Datahike charges every retained pull node, so
the full bounded config entity cannot fit even though only one root entity is
requested.

## Evidence

After program rows reached `canonical-program`, a real agent turn failed in
`prepare-eval-program!` with `Configuration acquisition failed` and
`datahike query-results budget exceeded`. The config member combined a 64 KiB
weight cap with a one-node count cap.

## Owner

`seon.execution/config-member` owns the immutable config pull used to prepare
the execution child's eval program.

## Acceptance

- The one-root pull retains its weight and work limits with bounded node
  headroom suitable for the declared config singleton.
- A focused test asserts all three resource constraints.
- A real agent reaches authored-form evaluation.

## Resolution 2026-07-23

Commit `46fcd779` fixed the retained-node budget. Current
`src/seon/execution.cljs:430-436` uses the configuration read profile's
`max-results`, not a one-node cap.
