---
type: evidence
date: 2026-09-28
lane: waste-profile-opus
---

# Waste measurements, boot to ordinary operations (default, armed)

All numbers are from the live default JVM through MCP `eval_clj` (JVM mode) unless stated. Probe helpers were
disposable Vars in `tmp.waste` (`snap` sums `seon.profile/observation` calls and total-ms per symbol; `delta`
subtracts two snaps). Windows include each MCP call's settlement tail, so per-call figures divide a window of
several calls.

## Boot layers (`(:seon.boot/layers (get @seon.operator.runtime/running-instances "default"))`)

| start | commit | ready ms | source current ms | note |
|---|---|---|---|---|
| 21:29Z | 555783648 | 20,027 | 14,923 | |
| 21:33Z | 588897b17 | 32,058 | 27,750 | one-time fill of the new dependency-set cache dir (15:33:59–15:34:05 local, 6 s) + 493 re-derived keys (analyzer code changed) |
| 21:35Z | 588897b17 (same) | 5,967 | 1,242 | unchanged program: ensure-dependency-analysis! 401 ms (lints resources/bench/script every boot), changed-source-paths 69 ms |
| 21:40Z | 0f92ebdbc | 20,972 | 17,315 | 34 changed files (25 schema resources from e941b7845) → 106 re-derived keys |
| 21:47Z | 0f92ebdbc | 12,608 | 7,656 | |

JVM launch plus namespace load before `start!` begins: 3.76 s (JVM start 21:35:30.999Z, `began` ≈ 21:35:34.761Z from
the served log line minus ready-ms). The operator's 11.1 s wall = 3.8 s JVM + 6.0 s boot + ≤1.3 s down/adopt/prune.

## Re-derivation that changes nothing (current-src tx 536870951, the 0f92ebdbc publication)

- 106 `:seon.fn.file/derivation-digest` changes; 34 `:seon.fn.file/digest` (byte) changes.
- 97 of the 106 files had unchanged bytes; every datom asserted for them in that transaction was the derivation
  digest (194 datoms of 2,145). Zero other attributes changed.
- `seon.fn/analyze-entities` over those 106 paths, armed: 8,338 ms (`seon.fn.analyzer/analyze` 4,673,
  `invoke-kondo` 2,299, `parsed-facts` 769, `assert-capability-contracts!` 552, `var-entity` 505,
  `forget-namespaces!` 362, `definition-digest` 361, `seon.id` printing digests ~315).
- All 862 inputs: 13,655 ms (under a concurrent test round).
- No analyzer-reached definition digest changed in that publication (analyzer-reach = 640 definitions); the
  re-keying came from schema forms (render pairs removed from 82 error schemas).

## Trivial MCP JVM eval `(+ 1 2)` (0f92ebdbc, window of 6 settles)

- `seon.mcp/call` ≈ 94 ms per call: `turn.batch/settle` → `db/transact!` 58 ms, of which
  `write-owned-values-error` 25.5 ms (write-entity-value 307 entities per settle: the root agent's runtime owns
  ~308 turns as components, task 4bb788513862, 10.8 ms at 179 turns on 2026-09-26); jvm-show/print 11 ms;
  run-observation 11 ms; acquire-context 6 ms.
- `seon.mcp/jvm-run` mean 1.06 s over 41 calls in health includes this lane's analysis probes (13.8 s, 8.4 s).

## Health report (`seon.health/report`)

- Parent 588897b17: first report after an eval commit 335 ms, repeat 17 ms; `seon.health/untested` →
  `seon.fn/functions-without-tests` 251 ms because its read set was `:all` (rules with a variable attribute), so
  `seon.health/derived` keyed it by commit id.
- 0f92ebdbc (commit 0f92ebdbc): first report after an eval commit 114 ms, repeat 12.7 ms; `::untested` reads 19
  named attributes, unchanged across an eval commit (attribute revisions equal, commit id different).
- Remaining per-commit cost: `seon.problems/failed-tests` 50 ms (`test.runner/latest-results` 49 ms).

## Query planner fallback (eavt/aevt root `_subtreeCount` = -1, avet known)

Same 579,039 datoms, default db vs a copy whose eavt/aevt were rebuilt with `from-sorted-array` (counts known),
query cache off (`:cache-context nil`), median of 5 (ms):

| query | heuristic | counted |
|---|---|---|
| keywords by path (3 clauses, 22,517 rows) | 37.1 | 13.8 |
| txInstant last 10 min (125) | 0.67 | 0.41 |
| file digests (862) | 0.73 | 0.64 |
| calls join (4,956) | 8.5 | 7.8 |
| functions-without-tests rules (1,601) | 112 | 107 |
| path/spec join (2,715) | 3.0 | 2.1 |

## Heap

`jcmd GC.heap_info`: 4.56 GB used; after `GC.class_histogram`'s full collection 0.97 GB live (G1 Old Gen
collection usage 969 MB). Largest: byte[] 237 MB, Object[] 232 MB, 3.68 M Datoms 147 MB, 1.9 M Symbols 61 MB,
PersistentVector 55 MB, String 50 MB.

## Store bytes by window (file mtimes under data/store)

| window (UTC) | files | MB |
|---|---|---|
| boot onto 588897b17 (publication + adoption) 21:33:50–21:35:00 | 5,765 | 39.2 |
| boot on the same program 21:35:25–21:36:30 | 653 | 5.9 |
| boot onto 0f92ebdbc 21:40:50–21:41:45 | 3,458 | 32.0 |
| after, tests and evaluations 21:41:45–21:46:37 | 6,982 | 64.7 |
