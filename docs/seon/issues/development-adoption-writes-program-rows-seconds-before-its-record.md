---
type: issue
status: open
severity: high
created: 2026-09-23
tags: [issue, adoption, acquisition, reload, test-check]
---

# Development adoption writes program rows seconds before its record

## Problem

`seon.cluster/development-source-refresh!` (`src/seon/cluster.clj:2525`) runs
`adopt-rows!` (`:2470`: schema, `seon.fn/index!`, `seon.issue/adopt!`, each its own
transaction) FIRST, then `require :reload`, source verification and
`instrument/apply!`, and only then transacts the adoption record
`:seon.source/commit-id`. Acquisition names the JVM's loaded program by that
record (`sci.eval/loaded-source`, `src/seon/sci/eval.clj:862`; B2 §2a rule 1). In
the gap, default's rows are the new publication P while the record still names P0,
so any isolated acquisition taken then — every `bin/test-check` branch — classifies
P's changed rows as overridden, interprets them and their callers, and refuses the
host-bound ones by name (`install-row!` `:1009`). A refusal between the row
writes and the record leaves the rows ahead of the record until the next adoption.

## Evidence (default pid 90963, 2026-09-23, read-only history query)

Gap between the last `:seon.fn/source` transaction and the following record
transaction, last twelve adoptions: 4331, 11122, 548, 2516, 9006, 518, 23225,
11427, 1572, 1445, 1591 ms (one record without row changes omitted). Publication
6ab35729 was never recorded (record went 6ab3571f → 6ab35793), which is the
"no reuse: this JVM loaded source 6ab35729… but … record 6ab3571f…" line and the
transcript-test check-unavailable (`seon.cluster.reload-measure/-main` refused)
seen by two lanes. Outside those windows the same checks pass with no drift line
(landing: `docs/prds/agent-platform/landing/lane-loaded-follows-adoption-2026-09-23.md`).

## Owner and acceptance

`src/seon/cluster.clj` (development adoption). Smallest change, no new mechanism:
derive the reload set, requires, arming identities and definition digests from
`published-database` (it holds the same rows), reload/verify/instrument FIRST, then
`adopt-rows!`, then the record — the gap shrinks to the row-write transactions.
Acceptance: after the reorder, the history query above shows record-minus-rows
gaps bounded by the row transactions (sub-second for a one-file change), and a
test check started during a reload refuses nothing.

Secondary (`src/seon/test.clj:1669`): the drift line calls the published head
"loaded"; it is the published, not-yet-adopted commit. Wording only; the refusal
to reuse while publication leads adoption is right.
