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

### Final boundary after the owner's restart

Implementation committed path-limited as `6313d2006`. During cleanup default
changed to PID 37572; this lane did not restart it. The queued publication
`f915faf2-ca7d-4b51-8442-574a829c5426` subsequently reported convergence on
source commit `6aaa36cc-8be5-5a3c-8fbf-2f27933c7b14`, but its reaching check
was unavailable during the process transition.

The fresh process initially had an unrealized canonical fixture base. One
retry of the class regression used `seon.test/run` on a future, with its
three-argument options carrying `seon.db/db`, `seon.test.runner/provenance`
of that database, and `:seon.test/remaining-ms 270000` to avoid interrupting
cold base construction. It finished in **32756.598 ms**, run **49224**,
recorded **2 pass / 0 fail / 1 error**. Construction failed loading
`seon.dev.dependency-cache-test`: `clojure.tools.build.api` is absent from
the process classpath. A subsequent dereference of the realized base
confirmed the cached `:seon.sci.eval/namespace-unloadable` exception.

This is now the explicit **base-poison stop boundary** in `repl-rule.txt`,
not the earlier held-permit boundary. No further fixture run, classpath
repair, base replacement, or restart was attempted. The existing issue
[in-process-test-runs-poison-the-shared-fixture-base](../../../seon/issues/in-process-test-runs-poison-the-shared-fixture-base.md)
was updated with this recurrence; that issue note is one additional edited
path, solely to retain the observed verification blocker. Owned scratch
files were removed; the completed retry has no running future.

## Batch 36 cold-red follow-up — 2026-09-16

The owner's batch 36 at `7216a688b` supplies the missing cold evidence:
the generated turn test completed in **105245 ms**, versus the earlier
270000 ms worker bound. Schema, database, and fixture-support namespaces
were green. Config/reconcile contained eight failing assertions. The retained
root `tmp/test-runs/run.gIQ9CS` was left read-only.

### Verified causes and repairs

1. **Carriage:** on default, `(db/carried-projection @connection)` is nil
   while `(db/carried-projection (db/db connection))` is present. Both
   `config/apply-compiled!` and `reconcile/reconcile!` now obtain the database
   through `db/db`. The class regression observes that same public boundary.
2. **Hand edits, provenance, identity scope, and managed pulls:** the
   apparent semantic failures were refused setup writes, not a changed
   reconcile plan. Read-only calls to `seon.db/write-error` with default's
   real database and projection returned `:seon.db/invalid-write` for the
   old identity-bearing hand edit, at
   `[0 :seon.config/applied-manifest-digest]`, and for the five-field seed
   row at `[0 :seon.config.agent/turn-completion-backstop-ms]`. The tests
   discarded those returned errors. Therefore no edit, provenance datom,
   outside-scope row, or managed eid existed to observe.

   The prior write-validation ruling deliberately requires complete entities
   when a map asserts their required identity; attribute edits use `:db/id`
   lookup refs or explicit datoms. The owning `config-row` fixture helper
   now calls the production manifest compiler for a complete desired row.
   `transact-as!` checks its report and throws the complete refusal before
   any downstream assertion. Hand edits use `{:db/id
   [:seon.config/cluster name], ...}`. The class regression additionally
   asserts a committed report and the changed value before applying the
   manifest. Both revised write shapes passed live admission. The existing
   repair, provenance, scope, and pull expectations are unchanged; neither
   database admission nor reconciliation semantics were weakened.
3. **Shipped defaults:** the exact set difference was
   `#{:seon.test/check-time-limit-ms}`, not the removed blob dial. The
   registered dial already defaulted to 120000 ms, but the shipped EDN
   omitted its explicit decision. `config/default.edn` now contains that
   value. Registry/default differences are empty on the live read-only
   probe. `:seon.config.blob/max-bytes` is absent from both sides.

The extra paths for this follow-up are `config/default.edn` (the missing
shipped decision) and
[identity-upserts-still-require-complete-entity-maps](../../../seon/issues/identity-upserts-still-require-complete-entity-maps.md)
(the existing issue's newly verified fixture members). No protected source
or schema resource was edited. The schema/fixture and REPL skills loaded in
the initial slice remain the method; the existing write-validation landing
and identity-upsert issue supplied the precise admission contract.

### One authorized cold iteration

Candidate source functions and fixture helpers were evaluated in default
before source edits, and the functions were re-armed (1058 registered /
1058 instrumented). No database-backed in-process test was attempted against
the poisoned fixture base. The owner's exception authorized this one serial
single-JVM iteration, using HEAD plus only the five edited code/config paths:

```sh
SEON_TEST_ORCHESTRATOR=1 bin/test-fast --paths \
  src/seon/config.clj src/seon/reconcile.cljc \
  test/seon/config_test.clj test/seon/reconcile_test.clj \
  config/default.edn -- seon.config-test seon.reconcile-test
```

Snapshot basis `f9734422a519595a7b1d2fc3d8713b8434fb584b`, temporary root
`tmp/test-runs/run.AkWdZZ`, test JVM PID 45575. The runner armed 1040/1040
contracts. Namespace execution ran from 06:49:59.751446Z through
06:51:56.152005Z. **27 tests / 128 assertions / 0 failures / 0 errors;
exit 0.** This includes all six tests responsible for the eight cold
failures. The complete class regression took 889.881 ms; that includes
initial application, convergence, hand editing and repair, and changed
initialization—not one apply latency measurement.

Exactly one cold iteration was launched. No `bin/test` gate, `--all`,
parallel test invocation, process kill, default restart, or shared-base
replacement was performed. The runner retired its successful snapshot.
Test-file clj-kondo and `git diff --check` passed. The temporary test log
was removed after recording the result here. The existing seven-line gate
request was refreshed; platform and broader integration remain the
orchestrator's gate, not a claim made by this two-namespace iteration.

Hook `4078e361-ac23-4efe-a0a2-2f569978a21e` reported convergence to
`6aaa3c2a-99ed-57e5-9d4c-ab5b225c18d3`; its automatic reaching check was
unavailable because the cluster rejected the prepl operation. The cold
snapshot result above is the execution proof for this follow-up.
