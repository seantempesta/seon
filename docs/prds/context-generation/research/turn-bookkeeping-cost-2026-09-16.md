---
type: research
status: active
tags: [research, config, test-fixture, performance]
---

# Turn bookkeeping cost: the 300 ms bound and the 270 s worker bound

Dated 2026-09-16. Research lane, read-only, branch `steward-platform` at
`2843d6ec7`. Every number below was measured in the live `default` cluster's
JVM (pid 53378) through `mcp__seon__eval_clj` in `jvm` mode, on daemon threads,
against the canonical in-memory fixture base (`seon.test-support/with-database`).
No file under `src/`, `test/`, `bin/`, `script/`, `resources/` was changed and no
test JVM was launched.

## Verdict

Two separate questions had one assumed cause. They do not share one.

1. **The six-form turn's bookkeeping is NOT 5,646–6,695 ms on a warm JVM. It is
   about 100 ms.** Measured on the same fixture the regression uses, the complete
   second `seon.turn/turn` call — reply through settlement and close — is
   **452 ms**, of which `seon.cluster.prompt/prompt` is 217 ms and the six SCI
   evaluations are 134 ms. The regression's clock starts at `reply/sources` and
   subtracts SCI nanoseconds, so what it asserts against 300 ms is ~100 ms here.
   The recorded 5.6–6.7 s therefore measures the FIRST turn a fresh worker JVM
   ever runs (class loading, first clj-kondo analysis, first candidate
   evaluation, first instrumentation arming), not the per-form algorithm. This
   lane could not run a cold gate JVM and does not attribute it further; it
   states only that the algorithm at HEAD does not cost that warm.
2. **The generative test hits the 270 s worker exchange bound because of its
   FIXTURE, not its turns.** One empty `with-cluster` costs **5,017 / 4,851 /
   5,050 ms** warm. `generated-model-attempt-traces-preserve-presence-and-episode-laws`
   runs 48 trials, each one `with-cluster`: **~240 s of fixture construction
   before any turn work**, against a 270 s bound. That is the whole gap.

The cost inside the fixture is one mechanism, and it is unnecessary work.

## Phase attribution — the timed turn window

Replicated the regression's own path (`#'seon.cluster.turn-test/with-cluster`,
its `request`/`now`, one opening turn, then the six-form call turn with
`ai/complete` stubbed), timing counters reset immediately before the timed turn.
Wrappers are pure delegates installed with `with-redefs-fn`.

| Phase | calls | ms |
|---|---:|---:|
| `seon.cluster.prompt/prompt` | 1 | 217 |
| `seon.turn/evaluate-sources` (inclusive) | 1 | 158 |
| `seon.sci.eval/evaluate` (of that) | 6 | 134 |
| `seon.db/transact!` | 4 | 38 |
| `seon.turn/settle-batch!` (inclusive) | 1 | 22 |
| `seon.fn/analyze-forms` | 1 | 16 |
| `seon.render/request-profile` | 64 | 15 |
| `seon.ai/agent-overlay` | 6 | 6 |
| `seon.turn/evaluation-terminal-data` | 6 | 4 |
| `seon.plan/run-issue-tests!` | 1 | 0 |
| `seon.turn/gate-function-install` | 6 | 0 |
| **window total** | | **452** |

Findings that follow directly:

- The quarry's disease has NOT recurred. `seon.fn/analyze-forms` runs **once**
  for the one defining form (16 ms), not per form; there are four transactions
  for the whole turn, not two per form; `seon.plan/run-issue-tests!`
  (`src/seon/turn.clj:3529`, the closing arm of `settle-batch!`) is called once
  and costs 0 ms — `0c8f90630`'s staleness gate holds, and the timed path pays
  nothing for it.
- `seon.render/request-profile` is called **64 times** in one six-form turn for
  15 ms total. Cheap, but it is a derive-per-call on a hot path and worth
  folding into the value the turn already carries (§2.1). Filed as an
  observation, not a cause.
- A bare `seon.db/transact!` of one datom on the canonical base is **3–4 ms**;
  the issue-retention seam in `seon.db/transact-call` (`retention-rules` /
  `retention-snapshot`) measures 0 ms with one installed rule
  (`:seon.issue/tests` activated by `:seon.issue/agent`) and no issue rows. Not
  a contributor here; it is an O(all rows carrying the attribute) double
  snapshot per transaction and should be re-measured once issues exist.

## The store-size hypothesis is refuted

The dev store's 72 GB / 380k keys does not explain any of this. The fixture
runs on `konserve.memory.MemoryStore`, and a bare `seon.db/transact!` there is
3 ms. The scheduled reset will not change these numbers.

(One 12.0–13.2 s, 88,974-datom `DefaultStore` transaction was observed
concurrently during whole-test runs. The instrumentation is process-global, so
this is almost certainly the running cluster's own publication on another
thread, not the test. Not attributed; it is worth its own probe, because a 13 s
publication transaction on `default` is itself a large number.)

## The dominant cost: `seon.config/apply!`, paid twice per fixture cluster

| Measurement (warm, canonical in-memory base) | ms |
|---|---:|
| `with-database` alone (branch of the canonical base) | 1 |
| `with-database` + `seed-cluster!` | 2,354 / 2,341 |
| empty `with-cluster` (two applies + launcher) | 5,017 / 4,851 / 5,050 |
| `config/apply!` first call | 3,009 |
| `config/apply!` again, identical config | 2,101 |
| `config/apply!` with the manifest | 2,030 |
| `cluster/ensure-cluster-entity!` | 21 |
| `config/effective` | 2 |

Inside `apply!`: `compile-manifest` is 55–58 ms. `apply-compiled!`
(`src/seon/config.clj:454`) is 2,802 ms cold and **1,539 ms when the config is
already converged and there is nothing to do**. Its two phases:

| Inside `apply-compiled!` (already converged) | ms |
|---|---:|
| `seon.schema/projection-from-database` (`src/seon/config.clj:459`) | 682 / 711 / 720 |
| `seon.db/carried-projection` on the same database value | 0 / 0 / 0 |
| `seon.reconcile/plan` (`src/seon/config.clj:479`), returning **0 operations** | 773 / 973 / 984 |

Both halves are unnecessary work, and both are ruled defects, not tuning:

- **The projection rebuild is the §2.1 violation.** The database value already
  carries its projection state (`src/seon/db.clj:141`); `apply-compiled!` throws
  it away and rebuilds the complete projection from datoms on every call, at
  700 ms a call, where the carried value costs 0 ms. This is the same disease
  the quarry removed from settlement in `4e68150f5`, relocated to config.
- **The converged plan is an absence-of-signal cost.** `reconcile/plan` walks
  the whole desired population to discover that zero operations are needed —
  ~900 ms to learn nothing. The compiled value already carries
  `:seon.config/applied-manifest-digest`; a second identical apply against an
  unchanged basis is derivable without a plan walk.

## Plan (not implemented)

Minimal, in this order; each deletes work rather than caching it.

1. **Hand `apply-compiled!` the carried projection.** Use
   `db/carried-projection` on the connection's database value and fall back to
   `schema/handed-projection`, exactly as `seon.db/transact-call` already does
   (`src/seon/db.clj:2955`). Removes ~700 ms per apply. Owner:
   `src/seon/config.clj:459`.
2. **Make a converged apply a read.** Compare the compiled
   `:seon.config/applied-manifest-digest` and the basis `:t` against the stored
   config row before planning; plan only when one differs. Removes ~900 ms per
   redundant apply. Owner: `src/seon/config.clj:454-480`.
3. **Stop applying config twice per fixture cluster.** `with-cluster` calls
   `seed-cluster!` (which applies) and then applies the manifest
   (`test/seon/cluster/turn_test.clj:195-216`). After (2) the second is nearly
   free; the seeded cluster itself belongs in the canonical base that
   `with-database` branches in 1 ms, so a fixture cluster costs a branch, not a
   reconcile. This is what takes the 48-trial property from ~240 s of fixture
   to seconds.
4. **Only then revisit the 300 ms assertion.** Warm, the path measures ~100 ms
   and the bound is correct. If the gate still reds on a cold worker, the honest
   fix is to name the first-call cost (one warm-up turn in the fixture, or an
   assertion over the second turn) rather than to raise the number.

### Class regression

One test, asserting the wanted behavior of the class *"a check that recomputes
what its input already carries"*: a **converged** `config/apply!` performs
**zero** calls to `seon.schema/projection-from-database` and **zero** reconcile
operations, counted by a delegate around the var. It fails the moment either
half of the waste returns, and it kills the class rather than the instance.

### Namespaces to gate

`seon.config-test`, `seon.reconcile-test`, `seon.schema-test`, `seon.db-test`,
`seon.cluster.turn-test`, plus `bin/test --platform`.

## Open, for the owner

- The 13 s / 89k-datom publication transaction on `default` seen during these
  runs has no owner in this page. It deserves its own probe.
- `seon.render/request-profile` at 64 calls per six-form turn is a derive-per-call
  on the turn's hot path.

## Config apply landing — 2026-09-16

Lane `config-apply-cost`, default PID 27828. Read this research page, the
assigned AGENTS.md sections, `apply!`/`apply-compiled!`, `reconcile/plan`, the
canonical fixture construction, and all three wave-2 lane-rule files end to
end before editing. No test JVM, default restart, refork, or replacement of
the shared fixture base was performed.

### Rotation and implemented slice

The proposed attribution was incomplete: `reconcile/plan` itself rebuilt
the projection. Both `apply-compiled!` and `plan` now prefer the database
value's carried projection, then the explicitly handed projection. Only
absence of both reaches the existing warning seam and rebuild. Config apply
captures one database value for its reads; writer-side reconciliation still
recomputes against the writer's current database.

The proposed digest shortcut is **refuted as a convergence proof**.
`compile-manifest` hashes effective dials only, excluding initialization
rows. Hand edits do not update that digest. On default, compilation of the
current effective config produced an exact plan containing three retractions
on `[:seon.ai.model/id "deepseek-flash"]`:
`:seon.ai.model/last-latency-ms`, `:seon.ai.model/last-tokens-per-second`, and
`:seon.ai.model/last-used-at`. These are initialization-row differences even
with the same config digest. A changed initialization document can also
arrive at an unchanged database basis. Neither digest equality nor adding
the basis comparison proves the complete desired population is converged.
No cache, new marker, or weaker convergence semantics was introduced.

The two fixture calls were **not identical** and were **not a boot call**:
`seon.cluster.turn-test/with-cluster` called `test-support/seed-cluster!`
(which applied defaults), then applied its test manifest. There is no
`with-cluster` in `test_support.clj`. The fixture now applies its final
manifest once, then calls the same `cluster/ensure-cluster-entity!` owner
that `seed-cluster!` called, with the same boot-process identity, before
seeding its agent and message. Production boot still applies its compiled
config once in `stand-cluster-runtime!` and was not edited.

Additional edited paths beyond the assignment's ownership list:

- `test/seon/cluster/turn_test.clj`: the actual owner of the duplicate apply;
  no pre-existing edit was present in this path.
- [config_apply_cost_probe_2026_09_16.clj](config_apply_cost_probe_2026_09_16.clj):
  reproducible, non-writing comparison on one immutable database snapshot.

`test/seon/config_test.clj` adds one class regression,
`converged-apply-uses-carried-projection-and-remains-exact`: a present carried
projection, zero rebuilds, zero fallback events, a real plan with zero
operations, and an unchanged transaction basis. It also checks that the
unchanged digest cannot hide a hand edit or changed initialization rows.
The observation delegates count only their calling test thread.

### Dependency ledger and measured evidence

- Datahike writer authority: `reference-code/datahike/src/datahike/db/transaction.cljc`
  `:db.fn/call` supplies the current transaction database;
  `seon.reconcile/reconcile-call` retains that integration.
- Projection authority: `seon.db/carried-projection` reads the immutable
  schema origin's metadata. Existing `seon.config/effective` demonstrates
  carried-first, explicitly-handed-second acquisition.
- Fixture branch authority: `test-support/with-branched-database` calls
  Datahike `branch!`; its roster permit is acquired in
  `reference-code/datahike/src/datahike/versioning.cljc:231`, through
  `reference-code/datahike/src/datahike/gc_guard.cljc:190`.

| Measurement | Before (ms) | After (ms) | Boundary |
|---|---:|---:|---|
| Exact plan on default | 1,343.693 | 70.145 / 66.584 / 63.824 | Hot-loaded and re-armed candidate; three operations in both |
| Fixed-snapshot comparison, basis 536871203 | 1,335.950 / 661.827 / 612.218 | 85.890 / 81.674 / 79.592 | Six exactly equal plans; committed probe script |
| Manifest compilation | 53.307 / 50.849 / 50.173 | unchanged | Still separate work |
| Empty fixture | Historical 5,017 / 4,851 / 5,050 above | unavailable | Current fixture branch acquisition blocked |
| Generated 48-trial test | Historical 270,000 worker bound | 24,497.810 total; 20,000 execution bound | Zero assertions; fixture acquisition blocked, not a performance pass |

The fixed-snapshot script reconstructs the previous projection-build-plus-plan
path and compares it with the actual carried plan, without changing Vars or
writing facts. This establishes the removed work and equal plans, **not**
whole-apply latency below 100 ms or a fixture below 500 ms. Those acceptance
measurements remain unverified. Optional fixture-base seeding was not added
without its prerequisite measurement.

### In-process runs and exact verification boundary

All tests were resolved with `#'seon.test/resolve-test` and run using
`seon.test/run` on futures, with `(seon.operator/connection "default")`.
Namespaces were reloaded using `#'seon.test/with-test-loader`; the shared
fixture namespace/base was not reloaded or replaced.

```clojure
(seon.test/run
 (#'seon.test/resolve-test
  'seon.config-test/apply-compiles-once-and-round-trips-through-database-facts)
 (seon.operator/connection "default"))
;; Before: run 46716; candidate: run 47638. Both 0 pass / 0 fail / 1 error,
;; named 20000 ms execution timeout.

(seon.test/run
 (#'seon.test/resolve-test
  'seon.config-test/converged-apply-uses-carried-projection-and-remains-exact)
 (seon.operator/connection "default"))
;; Run 48471: 0/0/1, the same 20000 ms fixture boundary.

(seon.test/run
 (#'seon.test/resolve-test
  'seon.cluster.turn-test/generated-model-attempt-traces-preserve-presence-and-episode-laws)
 (seon.operator/connection "default"))
;; Run 48906, after reloading the edited test namespace: 0/0/1,
;; 24497.810 ms including result recording; execution bound 20000 ms.
```

The fixture delay realizes successfully and has a connection; it is **not**
a cached construction exception. The empty-fixture probe instead waited at
`acquire-reachability-permit!` → `branch!`. Its memory-store id was
`348b77ab-4a40-4b20-9457-9b966b4b4df9`; roster token 174 remained held, no
sweep or blobs were active, and 12 requests were queued. The originating
holder was not identified. The lane cancelled its own waiting probe and
did not operate another session or release an unowned permit. This is the
existing issue
[canonical-fixture-roster-permit-remains-held](../../../seon/issues/canonical-fixture-roster-permit-remains-held.md).

Adoption is separately unproven. Publication
`50a6badf-3c19-4556-9580-a646c85e2044` refused on the foreign
`test/seon/test_failure_facts_test.clj:120` reference `support/test-context`.
That file was not edited. The live function evidence above is explicitly
hot-loaded, with `seon.instrument/apply!` reporting 1040/1040 registered and
instrumented functions, not a completed development-adoption claim.

Focused clj-kondo: zero errors; existing shadowing, unused-require, and
duplicate-require warnings remain in the touched namespaces. `git diff
--check` passed. Gate request written to
`tmp/orchestrator/gate-requests/config-apply-cost.txt`: `seon.config-test`,
`seon.reconcile-test`, `seon.schema-test`, `seon.db-test`,
`seon.cluster.turn-test`, `seon.test-support-test`, and `platform`.
No test-suite green claim is made; the orchestrator's batched gate owns it.
