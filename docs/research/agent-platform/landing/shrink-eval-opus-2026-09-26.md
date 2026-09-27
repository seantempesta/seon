---
type: landing
lane: shrink-eval-opus
date: 2026-09-27
plan: docs/research/agent-platform/shrink-plan-sci-eval-reader-admit-2026-09-26.md (cc3514a1e, rulings d148e8638)
review: docs/research/agent-platform/review-eval-shrink-plan-2026-09-26.md
status: three commits; static checks green; tests NOT run (root runs them after one restart)
---

# shrink-eval-opus landing

**RESET NEEDED.** Then run these focused tests:

- `bin/test-check default --ns seon.sci.branch-execution-test`: two new tests, plus the existing acquisition tests
  whose forks now acquire.
- `bin/test-check default --test seon.render.entity-pairs-test/a-declared-database-input-is-supplied-and-a-callers-is-kept`
- `bin/test-check default --ns seon.cluster.branch-release-retention-test`
- `bin/test-check default --ns seon.test-test`: `stale` fork at an older value.
- `bin/test-check default --ns seon.test.accretion-test` and `--ns seon.sci.entry-gate-test`: the candidate gate's
  `disposable-copy` forks an acquired ctx, so it now acquires the copy's value.
- `bin/test-check default --ns seon.sci.eval-test` and `--ns seon.sci.restoration-test`: slice 4 touched `bindings`
  and `declared-row`.

## 1. Defect: a fork taken after a program-row commit lacked that row (47d9d4419)

- **Symptom** (shrink-web landing): the fixture commits a row with `support/program-fn-row`, then calls
  `support/fork-cluster-ctx`. The fork's `:seon.sci.kernel/program-snapshot` lacks the row. An explicit `acquire!`
  brings it in.
- **Cause, at the producer:** `seon.sci.eval/fork-cluster-ctx` copied the base's program snapshot and moved custody
  and projection to the new connection, but it never acquired. The fork's program was therefore the base's
  (acquired at the handle's earlier value), not the value it was forked onto. `:seon.sci.eval/loaded-basis`
  covering only `:seon.source/commit-id` is by design (it tracks the adoption record) and is not the cause.
  - Production callers hid this by acquiring by hand right after forking: agent.clj:1122 and test.clj:1893.
  - The test fixture did not do that.
- **Fix (+9 lines):** a fork of an acquired base calls `acquire!` on the supplied value. A value with an equal
  program answers at once through `acquired-database?`; an unacquired, lazy base stays lazy.
  - The explicit acquires at agent.clj:1122 and test.clj:1893 are now fast no-ops, since the value is identical.
    Their owners may delete them.
- **Regression:** `a-fork-taken-after-a-program-row-commit-holds-that-row` (branch_execution_test).
- **Evidence:** by source reading, plus the render lane's live probe (`acquire!` brings the row in). I could not run
  `with-database` from JVM mode (it needs a `seon.test/run` handle), and default runs the committed archive.
- **Behaviour change to watch:**
  - `disposable-copy` (candidate gate) now acquires the copy's value. When the program is equal this is the fast
    path; otherwise it is a real acquisition that keeps the context's own interns.
  - The boot fork (boot.clj:88) acquires when its source base is acquired.

## 2. Slice 1: loaded digests read once per store and commit (07ed49de3)

- **Change:** `acquire-program!` ran two whole-program `function-digests` queries per derivation. The one over the
  loaded program is now `seon.cache` kind `:seon.sci.eval/loaded-digests`, keyed by `[store identity, commit id]`.
  - The weight is 256 bytes × entries (~1.4 MB for 5,425 rows).
  - A loaded value `identical?` to the acquired one reuses the branch's digests and is never retained, since it may
    be an uncommitted `d/with` value.
- **Probe** (default, same `seon.cache/lookup` under a scratch kind): miss 9.6 ms, hit 0.012 ms. The scratch entry
  `:shrink-eval-probe/loaded-digests` (~0.9 MB) stays in the cache until eviction.
- **Not done, per Astra's review:** caching the `loaded-program` materialization. Every Datahike connection in this
  process holds its own konserve store and storage (30 connections, 30 distinct store objects, measured). A
  materialization from any branch's store binds that branch's node cache, so retaining it would pin released
  branches.
  - That needs a store-lifetime owner lookup (the held main store by store id), which lives in
    cluster/store.clj or registry.clj, not in my files. It saves ~5–8 ms per call.
- **Regression:** `a-loaded-programs-digests-are-read-once-per-commit`: a second read is a hit, and a new commit
  gets its own map. Net +27 src, +27 test.

## 3. Slice 4: mechanical deletions (a21a40517)

- Deleted:
  - unused requires: `clojure.main` (eval.clj) and `seon.error.refusal` (reader.cljc);
  - unused `caps` bindings (×2) and `interrupt-fn`;
  - `bindings`' four ignored parameters, and both callers' `built-in-calls` observer, which is now deleted;
  - `declared-row`'s unreachable `:seon.fn/spec` arm (`row` makes only schema, ns and deletion rows).
- Made private: `program-cache-policy` and `admit/record-name`.
- Kept: `evaluate-native!` (requiring-resolve caller at my/program.clj:680).
- Net −25.

## Checks and sizes

- Every commit passed `clj-kondo --lint src script test --cache false` with 0 errors, and
  `seon.contracts-compile-test/check` over the changed files returned `[]`.
- Sizes: eval.clj 5,022 → 5,060 (+9 fix, +27 cache, −25 deletions, plus a docstring). reader.cljc 857 → 856.
  admit.clj 870.
- The lane's net so far is positive, from the correctness fix and the cache. The real deletions are slices 2, 7 and
  8 in the plan.

## Next

Slice 2 (content-keyed base context) is marked REVISE by the review. It needs a complete-input key (config, supplied
defaults, projection content, graph inputs), an `overridden-row?` branch-law fix, and conversion of every consumer
(`loaded-basis`, `record-refusals-once!`, listener retirement, retention tests) in one slice. I will write that design
before editing. Slice 3 follows it.
