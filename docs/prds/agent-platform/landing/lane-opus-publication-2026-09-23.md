---
type: landing
status: three of four fixes landed; fix 3 stopped on a held path (fn.clj)
created: 2026-09-23
tags: [agent-platform, lane-b1, publication, adoption, save-gate]
---

# Lane opus-publication — the one-JVM hot path, safe for concurrent lanes

Spec: orchestrator launch (health priority 2), B1 §2a steps 1, 2a, 11-17, Sol audit
`docs/research/agent-platform/sol-audit-landings-2026-09-23.md` bug rows 16-17, bloat row 24.
Proof JVM: default pid 31476 (booted from the checkout after the 06:3xZ restart).

## Commits

| commit | fix | src net |
|---|---|---|
| `1f114bdec` | 1: reloaded namespaces must hold their published bytes BEFORE `require :reload` | +5 |
| `986cdc521` | 2: one `refresh-source!` of the store at a time (`locking` one monitor) | +9 |
| `09bd6e64d` | 4: save gate passes every changed fn/test, deleted included, to `seon.test/run`; gate intersection deleted | -6 |
| — | 3: rows + adoption record in one transaction | STOPPED, see below |

Paths: `src/seon/cluster.clj`, `test/seon/cluster/publication_serialize_test.clj` (new),
`test/seon/cluster/save_gate_test.clj`. `src/seon/cluster/source.clj` unchanged (timing
comment added and removed). `test/seon/cluster/publication_lock_test.clj` NOT converted: its
six tests drive `source/publish!` with an explicit expected commit, below the new monitor;
the Datahike expected-head refusal they assert is still the cross-writer fence, so they
remain correct (they are platform-excluded from `bin/test-check`).

## Fix 1 — what the evidence says about "--changed P republishes the tree"

Explicit P already publishes exactly P at `full-source-refresh!` (`partial?` captures only
the requested paths). Default's adoption records (`:seon.test/adoption-inputs` by tx):

| tx | instant | inputs | fn identities |
|---|---|---|---|
| 536871145 | 05:53:24Z | `src/seon/db.clj` (cut-a2-c8) | 183, all `seon.db` |
| 536871161 | 05:54:00Z | `registry_retention_test.clj`, `instrument.clj`, `schema.clj` | 166, `seon.schema`/`seon.instrument` |
| 536871240 | 06:15:44Z | the same three paths | 185 |

So `schema.clj` entered default through requests that named it, not through the db.clj
adoption. The real path by which an unnamed dirty file enters the JVM is `require :reload`
of a dependent, which reads the disk; the digest check ran only after the reload. Fix 1
runs `verify-development-sources!` over the whole reload set first.

Live proof (this lane's adoption of `cluster.clj` + two tests while `boot.clj`, `store.clj`,
`wake.clj`, `runner.clj` were dirty from other lanes, plus `tmp/opus-publication-dirty.clj`):
the published head's stored digests for those four stayed `8beb9f0e`, `ef4282ed`,
`8ad4d2e0`, `325f3261` (disk `8a599f4b`, `74b03048`, `922d92dd`, `cd69a251`); only
`cluster.clj` moved `89329ccf` → `116705f8`. Probe: `tmp/opus-publication-digests.clj`.

## Fix 3 — stopped: needs `src/seon/fn.clj` (held by digest-parity)

Adoption writes up to three transactions (schema declarations in cluster.clj, program
rows inside `seon.fn/index!`, issue rows inside `seon.issue/adopt!`) then the record. The
record can only share a transaction with the rows if `index!` accepts extra tx-data.
Options:
- **A (recommended)**: `index!` appends `(:seon.source/record-tx request)` to its one
  adoption transaction (fn.clj, 2 lines); `adopt-rows!` hands the record to whichever row
  write is last (issues use `adopt!`'s existing guard-vector arity, 1 line in issue.clj).
  Guarantee: the record lands iff the last row write lands. Cost ~15 lines. Gives up:
  the schema-declaration transaction stays separate (attributes precede rows).
- B: write rows + record on a scratch branch of the cluster branch, then one
  `force-branch!` of the cluster pointer. Fully atomic incl. schema; ~40 lines; moves the
  pointer under live agents' custody.
- C: leave two transactions; a refused record makes the next adoption re-reconcile the same
  identities (diff-based, converges). No code; no atomicity.

Reload-before-write stays: with fix 1 and fix 2 the only post-reload failure is the write
itself refusing.

## Fix 4 — the deletion case, measured

`seon.test/run`'s `selection-seeds` resolves a deleted symbol through history
(`test.clj:510-535`); `gate-sets` of a test seed selects that test (REPL: 1 of 1, 793 ms
first call); a never-existing symbol selects 0. A write deleting a function that a test
still calls is refused by the writer: "Program deletion leaves surviving referrers"
naming the test — so the audit's "deletion goes green" needs a surviving caller the writer
already refuses. The gate now passes all changed symbols; nsa slice 4 can call
`seon.test/run` directly with explicit identities and `:seon.test/check-time-limit-ms`.

## Tests (one request each, `bin/test-check default`)

- run `e4bba27d2860`: 6 executed, 33 pass, 1 error (first draft of the deletion test hit the
  writer refusal above — the finding, not a defect of the fix). Publication-lock tests excluded
  (platform).
- run `831a83cc17aa`: `--ns seon.cluster.publication-serialize-test --ns seon.cluster.save-gate-test
  --include-long`: 5 executed, 35 pass, 0 fail, 0 error, 27,211 ms, passed.
- Contracts of `cluster.clj` compiled against the packaged projection
  (`seon.contracts-compile-test/check`): 0 refusals, 298 ms.

## Timings (hot path, same probe on parent state and on this lane's loaded state)

| operation | parent | mine | over 1 s: why |
|---|---|---|---|
| no-change `init --dev default --changed src/seon/cluster/source.clj` | 0.26, 0.28 s | 0.26, 0.25 s | — |
| one-file (comment line in source.clj) | 2.32, 2.25 s | 2.13, 2.03 s | one clj-kondo lint of a 700-line file, two writer transactions, reload of `seon.cluster.source` and dependents; `schema/call-with-projection` 3.2–4.9 s inclusive (concurrent work included) |
| concurrent pair, one edited file | refused loser (prior sightings, 25–180 s) | 2.11 s and 2.33 s, same commit `6ab37412` | the waiter waited ~2 s, then converged in 0.2 s |
| adoption of `cluster.clj` + 2 tests | — | 9.95 s | `full-source-refresh!` 8.6 s: analysis of the 3,700-line file and caller lint (`seon.fn/analyzed-artifacts` 4.2 s). Over the one-second rule; the per-file analysis cost is the class in `docs/seon/issues/a-db-adoption-refuses-while-another-lane-edits-a-dependent.md` (orchestrator folds this row) |
| test adoption (`save_gate_test.clj`) | — | 3.59, 3.33 s | one test file lint + rows; same class |
| test request (5 tests, 2 nested gate runs) | — | 27.2 s | each nested `seon.test/run` re-derives `runner/program-digest` per new candidate commit (cache audit item 6); over ten seconds: existing defect, owned by test-overhead |

Heap (`jcmd 31476 GC.heap_info`): old gen 1,904 MB before, 4,376 MB after ~12 adoptions and
two test requests with other lanes working; consistent with the open registry-retention leak
(opus-leak-fix lane).

## Risks and limits

- The save gate runs its tests inside the monitor. A test that itself calls
  `refresh-source!` under `bin/test-check --gate` waits on the monitor until its bound, and
  `a-second-publication-waits-for-the-first-before-reading-the-head` fails under a gate run.
- Fix 1's regression tests the check, not its placement; the placement is proven only by
  reading and by the live adoption above (no dependent reload was triggered).
- Fix 4's deletion regression passes on the parent too (the writer refuses the severing
  deletion first); it proves the deleted symbol is accepted by `seon.test/run`.
- Earlier default (pid 28202) had every MCP value and every operator diagnostic refused
  ("seon.print/fit refused node ... expected must be a print node"); it cleared with the restart.
- No RESET NEEDED.
