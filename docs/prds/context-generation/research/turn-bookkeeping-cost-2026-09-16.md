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

## Gate-set cost — 2026-09-16, lane gate-set-cost

### Verdict and scope

The graph-wide derivation is removed from `gate-set`. The unchanged 300 ms
six-form assertion is **still red** in this JVM: 6,530.462375 ms before,
390.081584 / 355.566790 / 441.423375 ms after; final reload 365.775334 ms.
In the attributed window
`gate-set` costs **0.864375 ms**, while two `db/transact!` calls cost
**250.262166 ms**. This slice resolves the recursive gate lookup, not the
remaining [turn bookkeeping issue](../../../seon/issues/turn-bookkeeping-exceeds-recorded-regression-bound.md).
Do not claim a green turn or an adopted proof from these numbers.

Read this research page, AGENTS.md, the three assigned wave-2 rule files,
the gate/rule definitions, settlement call site, and `seon.test/check`
end to end before implementation; loaded datahike, REPL, data-oriented
Clojure, and testing skills. The assigned “22:40Z attribution” was absent
from this page's 418-line starting version. The working-edge record at
`docs/prds/steward-platform/plan/unsettled.md` instead labels its
5,976 / 6,416 ms attribution 20:20Z. This lane independently reproduced
the cost rather than relying on that timestamp.

Only the production function `seon.fn/gate-set` changes. The private
`test-reach-rules` value loses its two now-unused `test-gates-symbol`
clauses; its other queries remain intact. `tests-reaching` remains because
`seon.test/identity-tests`, fixture preparation, render callers, and tests
still call it. No settlement or check call-shape change is needed.

### Rotation, dependency ledger, and guarantee

- Default has **1,755 tests**, only **160** with `:seon.test/reach`.
  That attribute is historical tested-closure evidence, not a maintained
  current closure. `seon.test.runner/record-tx` derives it from the tested
  database value (`src/seon/test/runner.clj:1742–1758`).
  Rewriting it at install time would corrupt its meaning.
- `reach-digests` uses a separate incremental index of source, contract,
  schema, and call dependencies (`src/seon/test/runner.clj:1425–1545`).
  This slice neither reads its private cache nor introduces another cache.
- Datahike already supplies indexed incoming edges: `db/datoms` with
  `:avet :seon.fn/calls target` (`src/seon/db.clj:1881–1950`).
  The existing `seon.test.selection/reaching-tests` uses the same reverse
  direction over manifest edges (`src/seon/test/selection.clj:134–177`).
- The “re-plan every call” attribution is too strong: the dependency caches
  plans by query shape (`reference-code/datahike/src/datahike/query.cljc:3448–3472`).
  The expensive repeated work here is recursive reach derivation; changing
  its execution direction removes it without tuning a limit.

For each requested identity, visit each incoming-call-reachable entity at
most once, then select tests by calls, resolved subjects, and exact pending
subject symbol. The finite immutable graph bounds traversal, including
cycles. A subject edge terminates at a test; pending subjects do not
silently become transitive call edges. A missing function still selects
its pending-subject tests. The return remains a sorted, duplicate-free
vector. The database value is the only authority.

This rotates the suggested once-per-turn graph build further: there is
**no graph-wide build at all**, even for one newly installed definition.
N requests walk only their incoming subgraphs; no cross-request cache,
stored derived membership, writer hook, or additional turn state exists.

### Measurements on default

PID **45917**, captured database basis **536871586**; source commit
`6aaa5523-5055-5df1-bcd7-d944ce8a43fc`.
The canonical fixture base realized successfully on a future outside the
MCP/test bound and test loader. It was neither reset nor replaced.

| Observation | Before ms | Candidate ms | Evidence |
|---|---:|---:|---|
| `gate-set "seon.id/id"` | 6753.094083 | 19.578709; armed 11.444833 | Exactly the same 1,001 tests |
| New `my.agents.agent-a/repaired` identity | — | 1.080750 | Empty exact gate set |
| `gate-set "seon.fn/gate-set"` | — | 4.168083 | 174 tests |
| `check`, changed `seon.schema/commit-registration-delta!` | 5922.737292 | 5.417250 | Both select zero tests; selection-only proof |
| Check's reported elapsed | 5917.882625 | 3.382333 | No tests or provenance invented |
| All installed identities compared | — | 26008.902292 total | 4,879 identities, 1,002,483 reference pairs, zero differences |

The final attributed six-form window also measured `analyze-forms`
30.576625 ms (two calls), candidate evaluation 94.286334 ms, six ordinary
SCI evaluations 63.144251 ms (excluded by the assertion), and
`settle-batch!` 243.853833 ms inclusive. These are nested measurements,
not additive independent phases. Two transactions dominate the remaining
window; their internal cost has not been attributed by this bounded lane.

### In-process regression evidence and adoption boundary

Every run uses the canonical fixture, actual SCI, and armed contracts
(1,062 registered / 1,062 instrumented). Test namespaces were explicitly
reloaded through `seon.test/with-test-loader`; the base namespace was not
reloaded. The candidate was evaluated, called with real data, and tested
before production edits. Tests run serially on a future via:

```clojure
(seon.test/run
 (#'seon.test/resolve-test
  'seon.cluster.turn-test/delimiter-repair-is-span-local-and-precedes-intent)
 (seon.operator/connection "default"))
```

| Run entity | Test | Pass / fail / error |
|---|---|---|
| 81837 | Original delimiter regression | 15 / 1 / 0; 6530.462375 ms |
| 81840 | Existing calls/subjects reach regression | 6 / 0 / 0 |
| 81841 | Candidate delimiter regression | 15 / 1 / 0; 390.081584 ms |
| 81850 | Candidate indexed-walk class regression | 18 / 0 / 0 |
| 81858 | Attributed delimiter regression | 15 / 1 / 0; 441.423375 ms |
| 81861 | Final reloaded indexed-walk class regression | 19 / 0 / 0 |
| 81863 | Final reloaded calls/subjects reach regression | 6 / 0 / 0 |
| 81864 | Final reloaded delimiter regression | 15 / 1 / 0; 365.775334 ms |

The class regression now also requires exact scan counts for five requested
identities. It proves cycle termination, direct/transitive calls, subjects,
pending absent identities, unrelated/absent functions, edge retraction, and
old-database isolation. Delegates count only the invoking test thread.
An initial fixture candidate was correctly refused for missing required
function fields; those rows were completed before retaining the regression.

Development publication is blocked by the concurrent `:seon.issue/agent`
index declaration: default says it predates the incompatible schema change.
The existing [adoption issue](../../../seon/issues/adoption-refuses-a-monotonic-index-addition-datahike-supports.md)
records that boundary. The observed published head
`6aaa599a-051f-5b32-994b-99df63a3c1dc` differed from default's adopted
source above, and its indexed gate source was still old. No restart,
refork, foreign-file edit, scratch cluster, test JVM, `bin/test`, or
`bin/test-fast` was used. Live candidate proof is explicitly hot-loaded
and re-armed; integration/adoption remains the orchestrator's boundary.

### Reproduce the complete graph comparison

Evaluate on a daemon future and poll later; do not force fixture construction
inside a bounded MCP call. This compares the retained recursive reference
relation once against every installed function's gate set:

```clojure
(future
 (let [database (seon.db/db (seon.operator/connection "default"))
       pairs (seon.db/q
              '[:find ?symbol ?function :in $ %
                :where (test-reaches ?test ?function)
                       [?test :seon.test/sym ?symbol]]
              database @#'seon.fn/test-reach-rules)
       expected (reduce (fn [m [s f]] (update m f (fnil conj #{}) s))
                        {} pairs)
       pending (group-by first
                (seon.db/q
                 '[:find ?name ?symbol
                   :where [?test :seon.test/pending-subject ?name]
                          [?test :seon.test/sym ?symbol]]
                 database))
       identities (seon.db/q
                   '[:find ?function ?symbol
                     :where [?function :seon.fn/sym ?symbol]]
                   database)]
   {:gate-set-cost/identities (count identities)
    :gate-set-cost/reference-pairs (count pairs)
    :gate-set-cost/differences
    (into []
          (keep (fn [[f s]]
                  (let [wanted (into (get expected f #{})
                                     (map second (get pending s)))
                        actual (set (seon.fn/gate-set database s))]
                    (when (not= wanted actual)
                      {:seon.fn/sym s
                       :gate-set-cost/expected wanted
                       :gate-set-cost/actual actual}))))
          identities)}))
```

Gate request: `tmp/orchestrator/gate-requests/gate-set-cost.txt` contains
`seon.fn-test`, `seon.cluster.turn-test`, `seon.test-support-test`, and
`platform`. Focused clj-kondo reports **0 errors / 16 existing warnings**;
`git diff --check` passes.

The explicit final publication command,
`bin/seon init --dev default --changed src/seon/fn.clj --changed test/seon/fn_test.clj`,
exited 1 at the same `:seon.issue/agent` schema refusal. Its shell exited;
all lane probe futures completed. The source-edit hooks also reported that
refusal. Markdown's global hook reports 29 unrelated findings, including
stale dependency gitlinks in `agents-md-audit-2026-09-15.md`; this lane does
not edit those paths. No adopted or platform-green claim is made.

After all final runs and the publication command completed, the final MCP
source check returned `repl-unavailable`: default's advertisement was
missing. The lane did not operate default. All numbers above belong to
PID 45917; a later process requires its own adopted proof.

## `request-profile` landing — 2026-09-16

Lane `request-profile`, live `default` PID **95853**, branch `steward-platform`.
Read end to end first: this page, `docs/seon/issues/request-profile-is-derived-64-times-per-turn.md`,
AGENTS.md §0, §2.1, §2.4, §2.5, and `tmp/orchestrator/wave2/repl-rule.txt`. No
test JVM was launched, and `default` was never stopped, restarted or reforked.

### The 64 calls were not 64 derivations — and the derivations had one owner

`seon.render/request-profile` (`src/seon/render.clj:70`) already returned a
carried `:seon.render/profile` before deriving, so the open question was which
callers fail to carry one. Two paths were measured by wrapping the var with a
counter through `alter-var-root` inside one evaluation (restored in the same
`finally`), classifying each call as carried or derived:

| Path (live `default`) | calls | derived | derive ms | carried ms |
|---|---:|---:|---:|---:|
| `seon.cluster.prompt/prompt`, agent `root`, cold render cache | 146 | **1** | 3.2 | 0.26 |
| `seon.turn/evaluate-sources`, six forms, agent `root` | 12 | **6** | **14.7** | — |

The prompt path is already correct: `seon.render.web/derive-context!`
(`src/seon/render/web.clj:2407`) derives once and every one of the 145 further
calls reads the carried value for 0.26 ms in total.

The whole derivation cost belonged to ONE seam, `seon.turn/evaluate-sources`
(`src/seon/turn.clj:4472` before the change), which derived the profile inside
the source loop — **once per form**. Six forms, 14.7 ms: that is exactly the
15 ms this page recorded against 64 calls. The count was a red herring; the
derivation count is the observable.

### The change

`evaluate-sources` now derives the profile ONCE, before the loop, from the same
basis it already captures for `ai/agent-overlay`, and carries it on every
evaluation request. `request-profile` is unchanged: a carried profile short
-circuits, an absent one still derives, and a request with neither a profile
nor a projection still returns the typed `::render/missing-projection` refusal
(§2.4). No cache, no atom, no second mechanism.

| Six-form `evaluate-sources`, agent `root`, live `default` | before | after |
|---|---:|---:|
| `request-profile` calls | 12 | 7 |
| derivations | 6 | **1** |
| ms inside derivations | 14.7 / 14.5 | 2.40 / 2.39 |
| whole `evaluate-sources` | 48.8 / 48.6 ms | 30.2 / 28.6 ms |

### In-process regressions (PID 95853, `seon.test/run` on daemon threads)

- `seon.cluster.evaluate-sources-test/one-turn-derives-the-render-profile-exactly-once`
  (new, the class regression): **3 pass, 0 fail, 0 error**. It asserts the
  derivation count itself — six forms, exactly one derivation — plus the shown
  text of all six evaluations, so the profile cannot be dropped to make the
  count pass.
- `seon.cluster.turn-test/delimiter-repair-is-span-local-and-precedes-intent`:
  15 pass, **1 fail** — the 300 ms bookkeeping assertion. Successive runs in
  this JVM measured **954 → 913 → 488 → 477 → 393 ms**, still falling. The
  bound was NOT loosened. This JVM is the shared `default` development cluster
  under several concurrent lanes, so it is not the idle JVM (PID 53378) where
  this page measured ~100 ms; the remainder is not attributed to this change,
  which only ever removes 12.3 ms of derivation from that window.
- `seon.cluster.evaluate-sources-test/ordered-evaluation-retains-one-explicit-basis-without-publication`:
  24 pass, **9 fail**, all downstream of one
  `:seon.turn/agent-already-running` refusal. **Exonerated by probe**: re-run
  with `request-profile` forced to ignore any carried profile — the exact
  pre-change behaviour — it fails identically (24 pass, 9 fail, same refusal).
  The failure is independent of this change and belongs to the run-transition
  path this lane did not touch.

The orchestrator's batched gate is the proof; `tmp/orchestrator/gate-requests/request-profile.txt`
names the namespaces.

### Where the remaining bookkeeping ms are, on PID 95853

One run of the same deftest with the phase wrappers of this page re-armed
(pure delegates via `alter-var-root`, restored in the same future). The
bookkeeping assertion reported **408.5 ms**. The counters below span the WHOLE
deftest — three `with-cluster` blocks, six `turn` calls — and the
instrumentation is process-global, so the live cluster's own work on other
threads is included. They are attribution, not a window total:

| Phase | calls | ms |
|---|---:|---:|
| `seon.db/transact!` | 28 | **6,344** |
| `seon.turn/turn` | 6 | 3,934 |
| `seon.turn/evaluate-sources` | 27 | 1,973 |
| `seon.sci.eval/evaluate` | 35 | 1,698 |
| `seon.cluster.prompt/prompt` | 3 | 740 |
| `seon.turn/settle-batch!` | 3 | 268 |
| `seon.render/request-profile` | 373 | 125 |
| `seon.turn/gate-function-install` | 35 | 123 |
| `seon.fn/analyze-forms` | 4 | 54 |
| `seon.ai/agent-overlay` | 53 | 52 |
| `seon.turn/evaluation-terminal-data` | 11 | 10 |
| `seon.plan/run-issue-tests!` | 3 | 1 |

`seon.db/transact!` averages **227 ms a call** here, against the **3–4 ms** this
page measured on the same in-memory fixture base in the idle PID 53378. That
gap — two orders of magnitude on the one phase with the largest share — is the
whole remainder, and it tracks the load on this shared JVM, not the turn
algorithm. Profile derivation is no longer a term in it: 2.4 ms per six-form
turn, one derivation. The 300 ms bound stands unchanged.

## Remaining bookkeeping, phase by phase — 2026-09-16, lane `turn-bookkeeping-phases`

Read-only research lane on branch `steward-platform`, live `default` **PID
95853**, snapshot `e765058fe` plus this tree's uncommitted test edits
(`test/seon/cluster/turn_test.clj` is at HEAD). Read end to end first: every
earlier section of this page, `AGENTS.md`, and
`tmp/orchestrator/wave2/repl-rule.txt`. No file under `src/ test/ bin/
script/ resources/` was edited, no test JVM was launched, and `default` was
never stopped, restarted or reforked. Every probe ran on a daemon thread
outside the MCP bound; every wrapped Var was restored with `alter-var-root`
in the same `finally`, and each report confirmed `:probe/restored true`.

### (a) Warm, on the shared `default`

Six in-process runs of
`seon.cluster.turn-test/delimiter-repair-is-span-local-and-precedes-intent`
(`test/seon/cluster/turn_test.clj:2704`), namespace reloaded through
`seon.test`'s own loader, run via `seon.test/run` with
`:seon.test/remaining-ms 270000`:

| run | 1 | 2 | 3 | 4 | 5 | 6 |
|---|---:|---:|---:|---:|---:|---:|
| `bookkeeping-ms` | 531.0 | *voided* | 399.2 | 365.0 | 402.2 | 436.6 · 372.4 |

Run 2 is voided, not slow: it failed on `(= 1700 (:seon.sci.eval/time-limit-ms
request))` — another lane's `with-redefs` in this shared JVM, the
process-global hazard this page has hit before. **No run came under 300 ms;
the warm spread is 365–531 ms with no downward trend across six runs, so the
remainder is not first-use cost in this JVM.** Concurrent live turns were
observed on a second thread throughout (11 `turn` calls, 21 transactions
averaging 11 ms) — this is the loaded shared JVM, not the idle PID 53378
where this page measured ~100 ms.

### (b) The window, sliced by timestamp

Each phase owner was wrapped to log `[name thread start end]`, and the
assertion's window was reconstructed from the event log: the deftest's own
thread (6 `turn` calls; the other live thread had 11), its **second** `turn`
call, from the end of `prompt` (where `reply/sources` arrives) to the end of
the turn. That run reported 402.2 ms.

| Phase | calls | ms | nesting |
|---|---:|---:|---|
| whole turn 2 | 1 | 701 | includes `prompt` before the clock |
| `seon.cluster.prompt/prompt` | 1 | 231 | **before** the window |
| **window (prompt end → turn end)** | | **464** | = 402 reported + 52 SCI + clock slack |
| `seon.db/transact!` inside `settle-batch!` | **1** | **219** | the settlement commit |
| `seon.turn/settle-batch!` inclusive | 1 | 224 | 219 of it is that one commit |
| `seon.turn/gate-function-install` | 6 | **120** | **120 / 0 / 0 / 0 / 0 / 0** |
| `seon.turn/evaluate-sources` inclusive | 1 | 195 | = 52 + 120 + 27 |
| `seon.fn/analyze-forms` | 2 | 27 | inside `gate-function-install` |
| `seon.db/transact!`, the other three | 3 | 19 | 5 / 4 / 10 |
| `seon.sci.eval/evaluate` | 6 | 52 | **subtracted by the assertion** |
| `seon.render/request-profile` | 57 | 5 | one derivation — dissolved |
| `seon.ai/agent-overlay` | 6 | 5 | |
| `seon.turn/evaluation-terminal-data` | 6 | 4 | |
| `seon.fn/gate-set` | 1 | 1 | dissolved |
| `seon.plan/settle-call` | 1 | 0–3 | exonerated, see below |

Sum of the named phases is 365 of the 402 reported ms; the residue is the
turn's own map building and the wrappers themselves.

### What the remainder actually is

1. **One Datahike commit is 54% of the window.** `settle-batch!`
   (`src/seon/turn.clj:3510`) builds ONE transaction — namespace rows, the
   batch receipt rows, every evaluation's tx-data, and one
   `[:db.fn/call #'plan/settle-call agent-id]` per agent
   (`src/seon/turn.clj:3541-3550`) — and committing it costs **219 / 208 /
   226 ms** across runs. The writer-side `plan/settle-call` is **0–3 ms**
   (16 measured calls, max 3), so the cost is the commit of the batch itself,
   not the plan arm. In the same JVM, the turn's three other transactions are
   **4, 5, 10 ms** and the live cluster's own concurrent transactions average
   **11 ms**, so this is batch size, not contention.
2. **Installing one definition costs 120 ms, and only the defining form pays
   it.** `gate-function-install` measured 120 / 0 / 0 / 0 / 0 / 0 ms over the
   six forms; `analyze-forms` (2 calls, 27 ms) is inside it, leaving ~90 ms
   of candidate evaluation, contract arming and gate selection for the one
   `defn`. `gate-set` is now 1 ms, confirming the gate-set lane's slice.
3. **The already-dissolved phases stay dissolved.** 57 `request-profile`
   calls cost 5 ms with one derivation; `gate-set` 1 ms; `settle-call` ~0.
   Neither is a term any more.

### (c) First-use versus per-turn, and what the cold 625 / 652 ms is made of

Repeating the deftest in one session does **not** converge downward: 531,
399, 365, 402, 437, 372. The two dominant phases recur at full cost in every
run (settlement 208–226; `gate-function-install` 120). **So ~400 ms of the
cold worker's 625–652 ms is ordinary per-turn work, not first-use.** The
remaining ~225–250 ms is what a fresh worker pays once and this JVM has
already paid: class loading on the turn path, the first clj-kondo analysis,
the first candidate SCI fork and the first contract arming. The regression's
window is unlucky by construction — it brackets the FIRST definition-installing
turn a fresh worker ever runs, so every one-time initialization on that path
lands inside the measured window. This lane could not run a cold worker JVM
and does not attribute that residue further; it states only that the warm
structure accounts for roughly two thirds of it.

### Verdict

**The 300 ms bound is not achievable warm today.** The best of six warm runs
was 365 ms, and the window's own content is ~400 ms, of which ~220 ms is a
single Datahike commit and ~120 ms is installing one contracted definition.
The bound was correct against the ~100 ms measured on an idle JVM earlier on
this page; it is not correct against this JVM, and the gap between those two
measurements is itself the open question — small transactions here are 4–10 ms,
so JVM load does not explain a 219 ms settlement commit.

One line per remaining phase:

- **Settlement commit, 219 ms — measure the batch before accepting it.**
  Size `:seon.db/tx-data` at `src/seon/turn.clj:3541` for a six-form turn and
  check whether unchanged namespace and program rows are re-asserted every
  turn; a commit 20× the cost of the turn's other three is a datom-count
  question, not a tuning one. Dissolve if it is re-assertion; accept and
  state the number if it is genuinely new facts.
- **`gate-function-install`, 120 ms for one `defn` — accept, but it does not
  belong in a bookkeeping bound.** It is real per-definition work (analysis
  27 ms, candidate evaluation and arming ~90 ms) and it scales with
  definitions, not with forms.
- **`analyze-forms`, 27 ms in two calls — probe why a single defining form
  analyzes twice** (`src/seon/turn.clj` gate path); one call is expected.
- **`request-profile` / `gate-set` / `settle-call` — dissolved or exonerated.
  No further work.**
- **The assertion itself.** It measures the most expensive turn shape there
  is (a contracted definition installed into the program graph) and calls the
  result "bookkeeping". The honest repair is to bound the phases the name
  claims — settlement plus transcript writes — and to assert the definition
  install separately with its own number, rather than to raise 300 to a
  number nobody can defend. Not implemented here.

### Incidental, worth its own owner

Every in-process `seon.test/run` commits its results through
`seon.test.runner/record-tx` against `default`'s `DefaultStore`: measured
**5,943 / 5,789 / 8,054 ms** per run in this session. That is outside the
assertion's window and outside this lane's question, but it is 6–8 s of
writing per in-process regression and it dominates the wall time of the
REPL-first loop every lane is told to use.

## Turn settlement cost landing — 2026-09-16

Lane `turn-settlement-cost`, default PID 95853, after ordered-evaluation
commit `91cd63e5a`. Read the complete research page, assigned issue, AGENTS.md
sections, PRD §14, and all three wave2 rule files before editing. Loaded the
REPL, data-oriented Clojure, Datahike, and testing skills. Initial adopted
source was `6aaa62a7-41e6-5707-b0a8-dfef8347fb56`. The fixture base was acquired
on a future outside the test loader/bound and returned `:ready`.

### Slice 1: batch-size attribution refuted

The canonical six-form delimiter regression submitted **9 top-level operations**:
six evaluation settlement calls, disposition, close, and plan settlement.
There were **zero namespace maps**, **one new function declaration**, and
**zero unchanged namespace/program rows reasserted**. The transaction report
contained **249 datoms**. The function's returned transaction data was one
new declaration (including its contract components) and its call edge to
`clojure.core/+`. These are new facts, not removable repeated rows.

The commit measured **282.869125 ms**, with **243.270083 ms** in `row-tx`.
A second attributed run measured `row-tx` **191.371708 ms**, of which
`schema/projection-from-database` was **186.671708 ms**; adding the function
contract was **3.159666 ms**. The other two settlement commits in the first
test measured **22.120541 / 14.887750 ms**, with **81 / 94 datoms**.
No transaction-size change is justified by these measurements; the requested
**<50 ms defining-turn commit target is not achieved**.

Dependency ledger: `reference-code/datahike/src/datahike/db/transaction.cljc:1152`
hands the current writer database to `:db.fn/call`; `receipt-settle-call`
and `row-tx` retain that authority. `src/seon/schema.clj:2423` queries all
schema, function-contract, and function-source rows even with a reusable
projection. Simply substituting the entering carried projection would omit
earlier declarations in the same transaction, violating ordered evaluation.
The remaining optimization belongs at that exact projection derivation owner:
reuse unchanged declarations while including all writer-visible changes.
`src/seon/schema.clj` is outside this lane's exclusive paths; it was not edited.

In-process runs of
`seon.cluster.turn-test/delimiter-repair-is-span-local-and-precedes-intent`:
**71255: 15/1/0**, bookkeeping **550.257708 ms**;
**71287: 15/1/0**, bookkeeping **402.543209 ms** (pass/fail/error).
The namespace was reloaded through `seon.test/with-test-loader`. Temporary
timing delegates were restored after each run. These are live JVM measurements
on canonical fixtures, not adopted-change or cold-gate claims.

### Slice 2: carried-analysis candidate, withdrawn after stronger proof

`gate-function-install` already carries `:seon.turn/form-facts` and the
analyzed `:seon.program/row` on the evaluation. The candidate made `resume-turn` submit
only evaluations without those facts to its remaining batch analysis. No
new attribute or cache. The defining source
occurs **once**, versus twice before; ordinary sources still receive their
evaluation-owned call edges from the batch analyzer for existing functions.
The stronger same-turn edge proof below refutes completeness for new functions.

The unchanged old window remained **420.366332 ms** (run **71311**, 15/1/0).
Therefore the delimiter regression now measures the requested boundaries
directly: `db/transact!` after reply arrival (intent and settlement writes),
and `gate-function-install` separately. Both retain a **300 ms** bound.
Positive observation assertions require the writes and installation to have
actually occurred; a further assertion requires exactly one analysis of the
stored defining source. Counters exclude other threads and delegates restore
their entering roots. SCI execution, parsing and final context installation
are not mislabeled as settlement/writes.

The direct measurement is **221.266417 ms** settlement/writes and
**114.318456 ms** definition installation. An intermediate broader
total-minus-evaluation-minus-gate measurement passed once (run **71323**,
18/0/0), then measured **364.192584 ms** under publication load (run **71578**,
17/1/0); it was replaced with the direct boundaries, not a larger limit.

Explicit development publication reached reload and instrumentation, then
refused because the source changed during publication and its one retry.
The tree includes concurrent edits in `src/seon/fn.clj`, `test/seon/fn_test.clj`
and `test/seon/render_coverage_test.clj`; none was edited by this lane.
An owned formatting edit also occurred during that publication. No particular
foreign edit is asserted as the cause. Final adoption proof is recorded below.

**Final slice-2 verdict: stopped at the protected `src/seon/fn.clj` boundary.**
Candidate commit `3594331c8` is corrected by the following path-limited
commit; its `resume-turn` shortcut and one-analysis assertion are removed.
Run **72330**, **20/1/0**, proved that `(repaired 2)` lost its call edge to
the function declared earlier in the same six-form turn. Evaluation still
returned `3`, so the original result-only proof was insufficient.

The current analyzer derives available symbols from database rows plus the
submitted declaration rows (`resolvable-runtime-function-rows` and
`runtime-analysis-batch` in `src/seon/fn.clj`). Excluding the defining source
also excludes its new identity from that context; `analyzed-form` drops the
ordinary evaluation's unresolved call. The retained regression explicitly
requires that evaluation's `:seon.fn/calls` edge to `my.agents.agent-a/repaired`.

Exact required protected change: accrete an `analyze-forms` input carrying
already analyzed declaration rows; use those rows in `program-prelude` and
the resolvable symbol set, without analyzing their bodies again. Then
`resume-turn` can pass the carried rows alongside only the remaining source
forms. Preserve the current arity and contracts. No protected file was edited;
the original batch analysis remains until that owner change is authorized.
The direct settlement/write and definition-install bounds remain useful and
are retained independently of the withdrawn optimization.

Restoring the complete batch restored the same-turn edge: run **72332**,
**20/0/0**. The direct-bound candidate was run **71587**, **20/0/0**.

### Slice 3: provider budget

`episode-runs` now positively requires a provider attempt, or an accepted
reply whose datom transaction is later than the turn identity. Absence of a
reply no longer counts as a provider turn. Failed provider attempts still
count, and the outside-wake anchor remains unchanged. No schema changes.

`seon.cluster.turn-test/generated-opening-preserves-one-provider-turn-budget`
uses the canonical cluster fixture and production `generated-run-tx`, the
same opening constructor used by `seon.issue/start-call`. It drives the
ordinary turn transitions with real SCI and a virtual provider reply that
does not request completion, so stopping after one reply proves the budget.
It checks opening closure, absence of opening attempts, actual provider-call
and durable provider-turn counts, remaining budget, and no further work.

Before, run **71356**, **6/4/0**: the opening consumed the unit and **zero**
provider calls occurred. The hot-loaded and re-armed candidate, run **71561**,
**10/0/0**: opening cost **zero**, exactly **one** provider turn, zero budget
remaining. Both tests loaded through the production test loader and ran with
`seon.test/run` on futures. Final adopted verification follows.

### Final verification boundary and deliverables

Budget code commit: `97d1f69e0`. Slice-2 correction: `60e0ba923` (supersedes
the unsafe source shortcut in `3594331c8`). Slice-1 measurement: `9ea0f5cd1`.

Final namespace reload through `seon.test/with-test-loader`, hot-loaded budget
form, and `seon.instrument/apply!`, followed by serial `seon.test/run` calls:

| Test | Run | Pass / fail / error |
|---|---:|---:|
| `generated-opening-preserves-one-provider-turn-budget` | 73386 | 10 / 0 / 0 |
| `delimiter-repair-is-span-local-and-precedes-intent` | 73389 | 20 / 0 / 0 |
| `a-batched-turn-commits-only-queryable-definition-facts` | 73390 | 7 / 0 / 0 |

The latter two tests are in `seon.cluster.turn-test`, as is the budget test.
The final source retains the original complete batch analysis: no lost
same-turn call edges are accepted for a performance gain. The <50 ms commit
target and single-analysis optimization remain unachieved with the exact
owner changes stated above.

Two explicit `bin/seon init --dev default --changed src/seon/turn.clj
--changed test/seon/cluster/turn_test.clj` attempts exited 1. The second waited
behind PID 12595's publication, then refused source changes during analysis.
At final read, default still recorded `6aaa62a7-41e6-5707-b0a8-dfef8347fb56`,
and its indexed `episode-runs` source did not contain the new query.
**There is no adopted proof.** These are hot-loaded, re-armed live-JVM proofs.
Neither publication failure justifies touching another lane's process/files.

Focused clj-kondo: **0 errors / 49 warnings**; `git diff --check` passes.
Markdown's global hook reported 29 existing unrelated citation findings.
No `bin/test`, `bin/test-fast`, test JVM, default restart/refork, scratch
cluster, new cache, protected-file edit, or delegated agent was used.
Both owned publication shells exited; all probe futures completed. Temporary
probe files were removed after recording the evidence here.

The four-line gate request is
`tmp/orchestrator/gate-requests/turn-settlement-cost.txt`:
`seon.cluster.turn-test`, `seon.turn-test`, `seon.cluster.evaluate-sources-test`,
`platform`. The orchestrator owns that integration proof. The budget issue
is archived with commit and live evidence; its protected arming-test comment
still describes the historical budget-3 workaround and is explicitly left
for that test's owner.

### Reproduce the settlement size and writer attribution

After acquiring the canonical base on a daemon future, reload the test through
`seon.test/with-test-loader` and evaluate this form on a future. Retain its
return and inspect after completion; do not force it inside the MCP deadline.
The committed test contains the separate installation/write clocks and the
same-turn edge assertion. This delegate records transaction counts and writer
projection cost without changing their implementations.

```clojure
(future
  (let [events (atom [])
        settle @#'seon.turn/settle-batch!
        transact @#'seon.db/transact!
        projection @#'seon.schema/projection-from-database]
    (with-redefs-fn
      {#'seon.turn/settle-batch!
       (fn [cluster requests]
         (let [thread (Thread/currentThread)]
           (with-redefs-fn
             {#'seon.db/transact!
              (fn [& arguments]
                (let [started (System/nanoTime)
                      result (apply transact arguments)]
                  (when (= thread (Thread/currentThread))
                    (swap! events conj
                           {:probe/commit-ms (/ (- (System/nanoTime) started) 1e6)
                            :probe/input (second arguments)
                            :probe/datoms (count (:tx-data result))}))
                  result))
              #'seon.schema/projection-from-database
              (fn [& arguments]
                (let [started (System/nanoTime)
                      result (apply projection arguments)]
                  (swap! events conj
                         {:probe/projection-ms (/ (- (System/nanoTime) started) 1e6)})
                  result))}
             #(settle cluster requests))))}
      (fn []
        {:probe/result
         (seon.test/run
           (#'seon.test/resolve-test
             'seon.cluster.turn-test/delimiter-repair-is-span-local-and-precedes-intent)
           (seon.operator/connection "default"))
         :probe/events @events}))))
```

Projection observations may include other threads during the settlement
window; the original row-level attribution above separately isolated the
defining row. Transaction observations are restricted to the calling thread.

## Settlement projection rebuild landing — 2026-09-16, lane `settlement-projection`

Live `default` **PID 17352**, branch `steward-platform`. Read end to end
first: this whole page, `AGENTS.md` §0 and §2.1, turn PRD §14 and the
vocabulary rows for turn and system turn, and
`tmp/orchestrator/wave2/repl-rule.txt`. No test JVM was launched; `default`
was never stopped, restarted or reforked. Every probe ran on a daemon
future; every wrapped Var was restored by `with-redefs-fn`.

### Slice 1 — the rebuild, and one refuted premise

The assignment's premise was that the mid-transaction database value carries
an up-to-date projection. **Probed and refuted**: inside a `:db.fn/call`, the
writer's database carries the projection *identical* to the outer value's —
the entering one. So a bare substitution would have lost declarations made
earlier in the same transaction, exactly as the slice-2 withdrawal above
warned.

What is real is the waste. `seon.turn/row-tx` called
`schema/projection-from-database` on every declaration settlement — three
`d/q` scans over every schema, contract and source row — and the result was
the **identical** object it was handed. Measured on the canonical fixture in
one baseline run of
`seon.cluster.turn-test/delimiter-repair-is-span-local-and-precedes-intent`:
one call, **209.412084 ms**, `identical?` true, on the Datahike writer thread
inside the settlement commit. That is 209 of the **252.989625 ms** the
regression bounds as settlement and writes, spent to learn nothing — the
absence-of-signal class this page keeps naming.

The repair keeps the one case the carried value cannot answer.
`seon.turn/declaration-projection` (new, `src/seon/turn.clj`) returns the
value's carried projection, and derives at the writer only for a request that
a declaration PRECEDES in this same transaction. The single owner that orders
those calls, `receipt-settle-batch-tx`, is what marks them
(`:seon.turn/declarations-preceding?`); an absent carried projection is still
refused loudly. No cache, no new mechanism.

| `delimiter-repair-is-span-local-and-precedes-intent`, PID 17352 | before | after |
|---|---:|---:|
| six-form settlement and writes | **252.989625 ms** | **53.142 ms** |
| `projection-from-database` calls in the run | 1 (209.412084 ms, identical result) | **0** |
| definition installation | 130.538291 ms | 106.235665 ms |
| pass / fail / error | 20 / 0 / 1 | **20 / 0 / 0** |

The 300 ms assertion is unchanged. The run's same-turn edge assertion — the
later evaluation keeping `:seon.fn/calls` to the function declared earlier in
the same turn, the exact proof that withdrew the earlier candidate — passes.

The class regression is
`seon.cluster.turn-test/a-settling-declaration-uses-the-projection-its-database-carries`
(**6 / 0 / 0**): the marking is exactly "a declaration precedes this
request"; the unmarked path performs **zero** derivations and returns the
carried projection `identical?`; the marked path still derives exactly once.
It fails the moment the rebuild returns.

`seon.cluster.turn-test/a-batched-turn-commits-only-queryable-definition-facts`:
**7 / 0 / 1**, the one error being this shared JVM's worker-global
instrumentation drift on `seon.render-simplification-test/authored-source`,
a namespace this lane did not touch.

### Slice 2 — the prompt's turns-left expectation was stale

`seon.cluster.prompt-test/prompt-prices-the-exact-retained-history` expected
`turns left: 99 of 100` and rendered `turns left: 100 of 100`. **The
derivation is correct and the expectation was stale**, so the expectation
moved. Probed inside the fixture:

| fixture turn | identity `:t` | reply-size `:t` | attempts |
|---|---:|---:|---:|
| `opening-history` | 536870925 | 536870925 | none |
| `walk-run` | 536870926 | none | none |

`episode-runs` is **0** and `turns-left` is **100**. Both planted turns are
precisely the shapes 97d1f69e0 ruled out of the budget (turn PRD §14): a
reply frozen in the same transaction as its turn identity is the opening, and
an open turn alone is not a provider attempt. The test is **9 / 0 / 0** after
the change, and the expectation carries that reasoning rather than a number.

### Boundary

Commits: `2da44c50d` (slice 1, `src/seon/turn.clj` +
`test/seon/cluster/turn_test.clj`), `c27727551` (slice 2,
`test/seon/cluster/prompt_test.clj`). Focused clj-kondo: **0 errors**
(49 pre-existing warnings in `turn_test.clj`, 2 in `prompt_test.clj`);
`git diff --check` passes. `src/seon/cluster/prompt.clj` was not edited —
the prompt's derivation needed no change. Every number above is a
hot-loaded, re-armed live-JVM measurement (`seon.instrument/apply!` reported
1061 registered / 1061 instrumented); the adoption attempt is recorded
below. The orchestrator's batched gate owns the integration proof;
the namespaces are `seon.cluster.turn-test`, `seon.turn-test`,
`seon.cluster.prompt-test`, and `platform`.

**Adoption: refused on a foreign reference.** `bin/seon init --dev default
--changed src/seon/turn.clj --changed test/seon/cluster/turn_test.clj
--changed test/seon/cluster/prompt_test.clj` reached program rows and then
refused: `The rebuilt source could not preserve test evidence`, naming
`[:seon.test/sym "seon.render-simplification-test/nested-ai-values-retain-data-and-html-uses-declared-faces"]`.
That is the same foreign namespace whose worker-global instrumentation drift
appeared in the batched-declaration run above; this lane edited no file under
`test/seon/render_simplification_test.clj` and did not operate another lane's
process. **There is no adopted proof**; all numbers here are hot-loaded and
re-armed live-JVM measurements. Worth its own owner: publication currently
refuses on a stale test identity left by another lane's in-flight edit.
