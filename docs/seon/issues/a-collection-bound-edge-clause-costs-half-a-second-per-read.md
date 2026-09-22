---
type: issue
status: open
severity: friction
created: 2026-09-22
tags: [issue, publication, database, query]
---

# A collection-bound edge clause costs half a second per read

Seen 2026-09-22 by lane deletion-caller-edge on default (pid 51528). The Datalog
clause `[?caller ?attribute ?symbol]` with `?attribute` and `?symbol` both bound
from input collections (`[:seon.fn/calls :seon.fn/references]`, one symbol) took
**550.9 ms**; the same answer from two `db/datoms :avet` reads plus a file join took
**1.3 ms** (0.35 ms datoms, 0.92 ms join). Probe forms are in the landing note
`docs/prds/agent-platform/landing/lane-deletion-caller-edge-2026-09-23.md`.

The same shape runs in a loop in `seon.fn/assert-capability-contracts!`
(`[?caller ?edge ?target]`, `src/seon/fn.clj` reverse walk), which measured
**3212.7 ms** for a three-file selection on default and dominates a two-file sample
population (3.5–4.0 s). Wanted: the reverse walk reads AVET per edge attribute,
as `gate-set-in` already does, and a sample population returns in well under a second.

## Sighting 2026-09-23 (deletion-caller-edge, c843d621a)

`file-identities` has the same collection-bound clause shape. In an instrumented 5-case run: `file-rows` 9,455 ms, `reconcile-tx-in` 8,857 ms, `report-identities` 4,919 ms, `assert-capability-contracts!` 3,402 ms.
