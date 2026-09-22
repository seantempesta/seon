---
type: landing
status: landed (see commit below); proof boundary stated
created: 2026-09-23
lane: sci-program-revisions (Opus 5.5)
tags: [agent-platform, sci, acquisition, performance, datahike, host-bound]
---

# Lane sci-program-revisions: a context re-acquires only when program rows change

Owner file: `src/seon/sci/eval.clj`; regressions in `test/seon/sci/branch_execution_test.clj`.
Defects addressed: slow-assumptions runtime audit row 2
(`docs/research/agent-platform/slow-assumptions-audit-runtime-2026-09-23.md`), issue
`docs/seon/issues/a-data-only-commit-rebuilds-the-whole-sci-program.md`, the unmemoized
`base-ctx` (tests audit P7c), the README host-bound switch, plus three coordinator items
(resume-in-seconds `eval.patch`, one-transaction refusal recording, no `acquire!` lock).

## What changed (one mechanism, in place)

- **Program identity is Datahike's own revision basis.** `program-identity` (public) is
  the value's `:cache-context` members `connection-id`, `generation`,
  `conservative-revision`, `attribute-revisions`, memoized by commit id in a core.cache
  LRU (256). Seam: `reference-code/datahike/src/datahike/query.cljc:2568-2589`
  (`advance-query-cache-context`: one revision per touched attribute, conservative on a
  schema/unknown change) and `:2963-2975` (`source-context-unchanged?`, the same
  comparison Datahike's query cache uses); commit id names one root
  (`datahike/db.cljc:385`). Same derivation as seon.db's projection memo
  (`projection-cache-key`, `src/seon/db.clj:1223`), restricted to a different
  attribute set; db.clj's key is private, so the shape is repeated here (see
  "Changes needed elsewhere" #2).
- **The attribute set is declared, not listed.** `acquisition-attributes` =
  `seon.program/program-attributes` (the `:seon.program/partition :seon.program` fact),
  `seon.schema/projection-attributes`, `seon.config/dial-attributes` (the contract dials
  interpreted wrappers arm with), `:seon.config/cluster`, `:seon.source/commit-id`;
  memoized by projection fingerprint. Measured 188 program attributes on default;
  computing the set costs 14 ms, so the memo matters.
- **`acquired-database?`** (now public): identical value; or equal program basis over the
  acquisition attributes; or, on the acquired value's own lineage, equal revisions —
  which then records the new commit under the acquired identity so a branch opened at
  it matches by commit id. `acquire!` on a match advances the snapshot's `:seon.db/db`
  and the environment basis; zero program work.
- **`watch-program-identity!`**: a `d/listen` observer on each cluster/fork connection
  records each commit's basis as it lands (one select, no read), so a test branch opened
  at the head compares with the parent without anyone having evaluated there.
- **`base-ctx` memoized** by `[program basis, loaded commit, fault recorder]` (LRU 4,
  ~8.5 MB retained per entry measured); every caller gets its own `sci/fork` with fresh
  kernel atoms. A failed derivation evicts its cell and rethrows.
- **No `acquire!` lock.** Concurrent acquirers of one program share the memo's delay;
  refusals record once per `[program, target]` (`record-refusals-once!`).
- **Refusals record as one transaction** on the custody connection
  (`record-acquisition-refusals!`), the fault recorder only when no connection exists.
- **Host-bound switch**: `host-bound-row?` reads the indexer's `:seon.fn/host-bound?`;
  a function row without the fact is treated as host-bound (unknown never grants).
- **`load-core-namespaces!`**: resume-in-seconds patch applied verbatim (test-root
  namespaces deferred; a refused query throws instead of a `ClassCastException`).

## Evidence (scratch root `tmp/sci-rev-root`, source `git archive f8af5d92e` + my two files at `tmp/sci-rev-src`)

Audit probe (`tmp/sci-rev/probe.clj`, JVM `seon.sci.eval/evaluate` on the cluster ctx,
thread-allocated bytes):

| step | ms | MB | cache |
|---|---:|---:|---|
| warm `(+ 1 1)` | 24.4 | 15.2 | — |
| data-only commit (one `:seon.dev.mcp.artifact` row), then `(+ 1 1)` | **13.2** | **15.0** | identity miss 1 (5→6 entries), base-context 0 derivations; acquisition `identical?` |
| next `(+ 1 2)` | 11.0 | 15.0 | — |
| HEAD's per-commit path on the same value (`load-core-namespaces!` + `derive-base-ctx`) | 346.9 | 679 | (no overridden row on this root, so no reverse closure; audit on default: 1,242 ms / 3.58 GB) |

MCP SCI mode after a windowed JVM result (the audit's exact T5): `(+ 1 1)` 16 ms round
trip, record 8 ms / 9.4 MB; next `(+ 1 2)` 12 ms, 6 ms / 9.3 MB.

Test-request acquisition (`acquire-context!` isolated at the captured head, after a
data-only commit, no live evaluation there):

| version | ms | MB | interpreted | derivations |
|---|---:|---:|---:|---:|
| commit-id key (HEAD behaviour on this path) | 952 / 911 | 1,867 / 1,855 | 0 | 1 each |
| revision key, before the listener | 242 only when a live eval ran at the head first | 461 | 0 | 0 |
| revision key + listener (landed) | **300 / 294 / 289** | 461 | 0 | 0 |

The remaining ~250 ms is sampled (70 samples, 62 inside it) at
`seon.cluster.agent/acquire-context!:843` → `seon.db/carried-projection` →
`schema/load-projection`: db.clj's projection memo keys on the connection id, so every
new branch misses. Not this file (see #2).

Regressions (`seon.test/run`, `:named`, `:include-long? true`), run `5c2bf3b26feb`:
executed 4, pass 24, fail 0, error 0, 20,441 ms:

- `a-data-only-commit-reuses-the-acquired-program-and-a-program-row-reacquires` —
  reuse 0.40 ms, `identical?` acquisition and environment; program row re-acquires
  (1,382 ms) and the new function evaluates.
- `a-base-context-is-memoized-by-its-program-and-each-caller-gets-its-own-fork` —
  derive 687 ms, memoized 0.065 ms; forks isolated.
- `an-override-of-a-declaration-the-indexer-marks-host-bound-refuses-by-name` —
  `seon.render.hiccup/escape` (fact true, head `defn`) refuses by name.
- `two-concurrent-acquisitions-of-one-program-derive-it-once` — two futures return the
  `identical?` acquisition; both bounded at 6 s and finished.

Focused `test/seon/sci/**` compared with HEAD's `eval.clj` loaded into the same JVM:

| namespace | mine | HEAD eval.clj |
|---|---|---|
| lazy-acquisition | 7 pass / 13 fail | 7 / 13 |
| supplied-database | 0 / 5 (over bound) | 0 / 5 |
| shown-text | 2 / 8 | 2 / 8 |
| kernel-arm-carriage | 1 red | 1 red |
| reader | 269 / 17, same 5 reds | 269 / 17, same 5 reds |
| admit | 65 / 3, same red | 65 / 3 |
| declaration-population | 16 / 0 green | — |
| branch-execution (old test) | error at `evaluate!` | same class |
| eval-test | 9 executed, 7 reds | 10 executed, same 7 plus 2 more |
| documentation | 49 / 50 / 8 | 49 / 49 / 8, 2 reused |

Every red seen in both columns is the pre-existing class "A different SCI context is
already armed on this thread": host test bodies calling `eval/evaluate` inside a
`seon.test/run` member. No red was new under this change; the eval-test and
documentation rows stopped at the request bound, so they are not complete coverage.

## TIMINGS (every operation over 1 s)

| operation | wall | note |
|---|---:|---|
| scratch from-zero boot #1 | 44 s, exit 1 | my schema `[:or :nil :vector]` (no child): malli child-error |
| boot #2 (partial store) and #3 (from zero) at `268094a2c` | 56 s / 48 s, exit 1 | gitlink pin indexed as `:seon.fn.file/digest` (fixed by `f8af5d92e`) |
| boot #4 at `f8af5d92e` | 48 s, exit 1 | **HEAD from-zero defect**: `test/seon/cluster/release_context_test.clj:21` `[:fn #(instance? Throwable %)]` "has no admitted callable"; patched in the snapshot only |
| boot #5 | 133 s wall, ready 111,022 ms, exit 0 | from zero; over 10 s (from-zero-boot-takes-minutes class) |
| adoption, test file only | 13.6 s | over 10 s |
| adoption, eval.clj + test | 120.2 s / 107.7 s | over 10 s: defect, reload span of `eval.clj` dependents |
| adoption with stale kondo entry | 55.1 s / 36.8 s, exit 1 | linked `.clj-kondo/.cache` carried the main tree's 4-arity `classify-paths` into the snapshot |
| adoption, eval + test + source.clj | 60.1 s | over 10 s |
| warm resume | 46 s wall, ready 25,778 ms | over 10 s |
| acquisition-refusal recording after a load-file mismatch | > 3 min | each refusal's `commit-tx` pull re-derives the projection on the writer (db.clj) |
| `seon.test/run` my 4 regressions | 20.4 s | members 1.0–3.3 s run, ~0.22 s acquire each |
| documentation / eval-test / reader requests | 92 / 123 / 102 s | pre-existing reds and bounds |

Cache hit/miss counts are in the probe rows above.

## Changes needed elsewhere (not edited; exact change)

1. `src/seon/test.clj:1190` (realities-commit-5): replace the `same-commit?` +
   two `runner/program-digest` passes with `(sci.eval/acquired-database? ctx database)`;
   `runner/program-digest` can memoize on `sci.eval/program-identity`.
2. `src/seon/db.clj:1223` (projection-writer-producer): key the projection memo by commit
   id as well (a branch opened at a commit reads the same declarations), or expose
   `projection-cache-key` over an attribute set so `seon.sci.eval` calls it. Removes the
   remaining ~250 ms per isolated acquisition.
3. Adoption (`docs/seon/issues/development-adoption-leaves-the-loaded-program-at-the-boot-commit.md`):
   the host-bound switch makes this worse — every adopted host-bound declaration now
   refuses in a fork until the context's `::loaded-database` names the adopted commit.
4. `test/seon/cluster/release_context_test.clj:21`: `[:or :nil :seon.error/throwable]`;
   HEAD cannot boot from zero while the anonymous `:fn` stays.
5. `src/seon/cluster.clj:629` `mcp-runtime-observation` counts a Date in
   `:seon.problems/problems` after these test requests; `bin/seon status` fails.
6. Host bodies calling `eval/evaluate` inside `seon.test/run` members refuse on the arm
   guard; most `test/seon/sci/**` reds are this one class.

## Verification limits

- The live `default` was not touched beyond read-only probes; it runs older source.
- No reverse-closure case (an overridden row) was measured on the scratch root.
- The regressions ran on a JVM whose `eval.clj` was loaded with `load-file` after the
  last adoption (rows equal the loaded program); the listener was registered by hand
  on the cluster connection because the cluster ctx predates it.
- RESET NEEDED: no.
