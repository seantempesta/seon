---
type: issue
status: superseded
severity: friction
tags: [issue, database, runtime]
---

# Datahike queue-pressure warnings obscure the load failure

## Problem

Datahike logs queue pressure on each qualifying queue operation rather than on
a pressure-state transition or a bounded reporting cadence. Under intentional
saturation, the warnings become a second load and bury the actual failure.

## Evidence

The 2026-07-25 saturation run drove 131,072 concurrent transaction callers
against the pinned Datahike writer with `-Xmx4g`. Once the fixed 120,000-entry
transaction queue crossed 90%, `:datahike/tx-queue-pressure` repeated for
nearly every observed count. The retained command output reached 262,144
tokens before truncation. `:datahike/commit-queue-pressure` behaved the same
way above 60,000 entries.

The owning sites are
`reference-code/datahike/src/datahike/writer.cljc:100-105` and `:170-175`.
The queue size is defined at `:78`.

## Owner

The pinned Datahike writer owns queue-pressure detection. Seon should consume a
bounded signal or metric from that owner, not scrape or suppress dependency
logs at the application boundary.

## Acceptance

- Entering pressure emits one event with queue name, capacity, threshold, and
  observed depth.
- Sustained pressure emits at a bounded cadence or on meaningful depth bands,
  not per queue operation.
- Leaving pressure emits one recovery event.
- A saturation test preserves the first pressure evidence and terminal error
  without producing output proportional to transaction count.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
