---
type: prd
status: active
tags: [prd, testing, runtime]
---

# One Test Infrastructure: Production Forks, Bounded Artifacts, Fast Gates

## Executive review: the whole decision in one page

### Owner mandate and organizing ruling

Owner mandate, verbatim:

> "I want the tests to share one working infrastructure and for it to not
> create test artifacts that are not cleaned up and fill up my disk."

The performance widening is part of the same mandate: testing must be fast and
efficient.

**Owner ruling addendum (2026-08-07 night) — tiers, à la carte with smart
defaults.** Parallel-on-forks is THE GOAL FOR THE WHOLE SYSTEM, not a
starting point. The suite is staged and tiered:

1. **Platform first, fail-fast.** Every invocation runs the platform
   moving-part regressions FIRST (seconds); the parallel bulk fans out only
   over a proven platform. A broken platform fails in seconds, never by
   poisoning a thousand forked tests.
2. **Default tier = changed only.** Bare `bin/test` runs the platform
   regressions plus only the tests REACHING code changed since the last
   green basis — computed from `:seon.fn/calls` reachability over the
   program graph (F11's tests-reaching), never file mtimes or filename
   conventions. Generative properties for unreached namespaces do not
   re-run. "Changed" is defined against the last GREEN basis of the
   reachability graph, so a shared-schema change re-runs every dependent.
3. **À la carte on demand:** the complete parallel suite with all
   generative properties (`--full`, and at integration checkpoints); the
   boot/reset-boundary proofs; the booted-system drive with generative
   load. Each is one flag, never a second runner.

**Landing note — the tiering shipped 2026-08-07 night; the fork constructor
did not.** What is live in `bin/test` today, and what the rest of this
specification still describes as unbuilt, is recorded in the addendum at the
end of this document. Read that section before planning against the numbers
above: they describe the pre-tiering serial gate.

The organizing ruling is now recorded in the active plan:

> "The test suite should use the same features our system has to make forking
> into fresh test environments cheap and fast. We don't have that many moving
> parts to the platform. We need to make sure they work and have minimal tests
> catching regressions and then everything else should be using the cheap
> forking to get clean environments where they can do their tests in isolation."

This ruling settles the constructor, isolation, and shared-base questions. The
platform is the test infrastructure. There is no test graph builder, test
cluster model, or second lifecycle registry.

### The shape

One suite invocation builds one immutable source base through production
publication and activation:

1. one source commit with its complete activation closure;
2. one canonical schema projection at that commit; and
3. one acquired base SCI `ctx` at that commit.

Every ordinary test receives a new sovereign production-shaped fork:

- Datahike branches the published commit into a fresh cluster branch;
- SCI forks the immutable base `ctx` and attaches only that branch's connection
  and projection state;
- production graph construction starts the cluster graph and agent graphs; and
- teardown stops the production instance and retires the branch.

The only test-facing bracket is `seon.test-support/with-cluster`. It allocates
nothing itself. It calls the proposed production function
`seon.cluster/start-fork!`, gives the body the returned production instance,
and calls `seon.cluster/stop!` in `finally`. Full process-root proofs continue
to call `seon.cluster/start!`; `start!` itself composes the same
`start-fork!` after process-root store and source-base acquisition.

The suite base is not a mutable shared fixture. Its connection, branch, SCI
environment, and projection atom are never exposed to a test. Tests can mutate
only their fork. A test-specific schema addition advances only that fork's
projection state.

### The few moving parts tested directly

Only six platform boundaries receive direct construction tests:

1. file store plus process-root `flock`;
2. branch fork, concurrent roster publication, retirement, and reset;
3. source publication plus activation closure;
4. production Flow graph construction and lifecycle;
5. atomic `settle!`; and
6. SCI fork plus projection acquisition.

These direct tests are enumerated in this spec. They may create the boundary
they are testing, but they still enter it through its production owner. Every
other test uses `with-cluster` and is forbidden from calling Flow construction
or assembling a cluster handle.

### What is deleted

The current `with-database`, `with-branched-database`, `seed-cluster!`,
`database-base`, branch-lease pool, projection reconnect, and public access to
`populate-database!` are deleted from `test/seon/test_support.clj`. Their useful
behavior moves behind the production source-base and cluster-fork functions.

The hand-built render, armer, and agent graphs in `agent_test`, `turn_test`,
`prompt_test`, `loop_test`, `gen/loop_test`, and `render/web_test` are deleted.
The two low-level Flow control graphs that genuinely test Flow construction are
absorbed into the small direct moving-part set; they do not remain as
namespace-local fixtures.

A program-graph regression finds every test that can reach graph construction.
It permits a test only when it reaches `seon.cluster/start-fork!`, or when its
declared `:seon.test/subject` is the directly tested moving-part owner. The
query is over `:seon.fn/calls` and facts, not paths, names, source text, or a
maintained exception list.

### The disk guarantee

Every suite or changed-test invocation has one exact root below
`tmp/test-runs/`. Before that directory is created, the launcher publishes an
external operator root claim naming its exact path and exact owner identity
`(pid, start-instant)`. Store files, blobs, search files, logs, liveness dumps,
copied source, subprocess scratch, and result files live below that one claimed
root. Project tests may not create an unclaimed system-temporary directory.

Success stops all owned processes, records cleanup, recursively deletes the
claimed root without following symlinks, and retires the external claim.
Failure records the reason on the same claim and may retain the root, but
retention is bounded by database config facts: newest `N` roots **and** a total
retained-byte ceiling. At the next run's start, the operator claim reaper
removes oldest retained roots until both bounds hold. Literals in `bin/test`,
mtime-only eligibility, PID-only liveness, and a second test-artifact registry
are forbidden.

The no-follow rule is law: `seon.fs/delete-recursively!` is the deletion owner,
and `test/seon/fs_test.clj` keeps the symlink-sentinel proof.

### Performance outcome

The last green bare gate spent 965.858 seconds inside 106 namespaces, serially.
`seon.shell.jvm-test` and `seon.web.jvm-test` consumed 409.989 seconds. All
twelve tests in those namespaces paid a nearly identical 33.4–35.9 second
setup, and both fixtures populate a brand-new file store for every test. A
2026-08-07 empty invocation of the shell file fixture measured 40.504 seconds.
Under this design those twelve tests share the one suite base and take twelve
cheap branch forks; the subprocess and HTTP behavior remains real.

Direct 2026-08-07 measurements in the current checkout:

- current `with-database` branch fixture: 483.930 ms mean over 100 forks;
- cold `seon.sci.eval/cluster-ctx`: 856.751 ms;
- `sci/fork`: 0.347 microseconds mean over 10,000 forks; and
- current in-memory suite population through first fixture body: 11.865 s.

The plan's production branch-fork measurement is about 17 ms. Replacing only
the twelve duplicated shell/web populations projects the present serial
namespace work from 966 seconds to about 566 seconds. Applying the measured
fork and SCI deltas to the current census of 187 shared-database call sites and
50 cold-`cluster-ctx` call sites projects about 435 seconds serial; that is a
conservative model, not a gate result, because generated tests invoke some
sites more than once and some sites are outside the bare tier. Four concurrent
fork-safe namespace workers project 109 seconds of ideal scheduled work before
JVM load, the one base build, direct serialized proofs, and imbalance. The
recommended review target is therefore a bare gate under three minutes, with
measurement at each migration wave.

### Owner review calls left open

The morning ruling decides the architecture. Review is requested only for:

1. **Parallelism:** keep the first landing serial while the confirmed
   process-global schema/projection owners are repaired (recommended), then
   authorize four bounded in-JVM namespace workers after the new isolation
   regressions are green, or leave the suite serial permanently.
2. **Performance budgets:** accept the proposed `<30 s` focused p95, `<3 min`
   bare gate, and `<10 min` full gate as reportable config facts, or choose
   different numbers.
3. **Failure evidence bytes:** accept both last-`N` and aggregate-byte retention
   (recommended), or accept last-`N` alone and its honest inability to bound one
   enormous retained root.

## Provenance and dependency ledger

### Read completely for this spec

I read the following end to end before writing this specification:

- the complete diffs and messages for the five projection-binding fixture
  failures and their paired issue closures: `61ccb7332`,
  `d1781801d`/`f3148e1f0`, `3f276226f`/`45f7481ca`, `220972135`, and
  `19601a5de`;
- `test/seon/test_support.clj`;
- `bin/test`;
- `docs/prds/archive/in-server-tests/README.md`, including its four owner questions;
- `docs/prds/sci-execution-runtime/plan/README.md`, including the evening and
  night rulings on the minimal turn, activation closure, per-cluster
  projections, and the new 2026-08-07 morning ruling;
- `docs/prds/sci-execution-runtime/research/elegant-solutions-2026-08-06.md`,
  including R7;
- `docs/seon/issues/deletable-directories-have-no-claim-or-size-facts.md`;
- `docs/seon/issues/render-live-proof-roots-have-no-lifecycle-owner.md`;
- `docs/seon/issues/render-adversarial-roots-outlive-their-experiment.md`; and
- `docs/seon/issues/eval-samples-cost-42mb-of-store-each.md`.

I also read the localized runtime instructions, transfer prompt, current
working edge, runtime architecture, agent-runtime architecture, library source
map, the relevant production cluster/Flow/operator/test-runner sources, and the
vendored dependency source at the boundaries below.

### Selected dependency revisions and exact seams

| Dependency or owner | Selected revision | Source read for this decision | Contract used here |
|---|---:|---|---|
| Datahike | `10540578248e` | `reference-code/datahike/src/datahike/versioning.cljc` | `branch!` copies a branch head with CoW indices; `delete-branch!` requires released child connections and removes roster reachability while the key remains until GC |
| core.async | `dc35f3e0d7bc2eef502e77982f48641f025c8051` | `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj`, `flow/impl.clj`, and `flow/spi.clj` | A Flow is created from one data graph of procs and conns; Flow owns allocation and lifecycle |
| SCI | `2db3358cba91` | `reference-code/sci/src/sci/core.cljc` | `sci/fork` creates a generation-aware fork with a copied environment atom |
| Seon store and branches | current checkout | `src/seon/cluster/store.clj`, `src/seon/cluster/registry.clj`, `src/seon/cluster.clj` | One process-root store under `flock`; one sovereign branch and connection per cluster |
| Seon SCI | current checkout | `src/seon/sci/eval.clj` | `projection-state`, `cluster-ctx`, and `fork-for-turn` own acquisition, projection, and isolated interpreter forks |
| Seon graphs | current checkout | `src/seon/flow.clj`, `src/seon/cluster/agent.clj`, `src/seon/cluster.clj` | `var-process` is the proc-construction function; production blueprints own graph contents |
| Operator claims | current checkout | `resources/seon/operator/state.clj`, `src/seon/operator.clj`, `src/seon/maintenance.clj` | External claim-first authority, exact process identity, footprint observation, and no-follow cleanup already exist |
| Test selection and runner | current checkout | `script/seon/dev/changed_test.clj`, `src/seon/test/runner.clj`, `bin/test` | Changed-test selects affected namespaces; `bin/test` is the one fresh gate |

The Datahike revision in the current gitlink is newer than the revision printed
in `docs/seon/architecture/library-grounding.md`; implementation planning must
use the gitlink above and separately reconcile that stale documentation.

## Decision: one production source base and one production fork constructor

### The immutable source base

The proposed production function `seon.cluster/source-base!` owns the one
ordinary value that may be shared:

```clojure
{:seon.source/commit-id ...
 :seon.source/digest ...
 :seon.source/activation-closure ...
 :seon.store/store ...
 :seon.store/branch :current-src
 :seon.db/db ...
 :seon.schema/projection ...
 :seon.sci.eval/ctx ...}
```

The exact schema is an implementation design detail, but every field above is
already a dependency or first-party term. No `:type`, fixture mode, or test
identity belongs in the value.

`source-base!` performs production population, source publication, activation
validation, canonical projection derivation, and SCI acquisition once. The
test runner forces it before it starts any test worker, so initialization cost
is visible once rather than charged to whichever test happens to dereference a
delay first.

The base is indexed by the exact published source commit, not a remembered
boolean. It is invalid when any input that changes that commit or its activation
closure changes: first-party source/test manifest, admitted schema resources,
config defaults required by activation, or initialization rows. A sparse
test-specific config overlay, test datoms, a test-specific schema addition, an
the agent's defs, or a failed test does not invalidate it; those live only on the
fork.

Within one `bin/test` invocation the copied source tree and git SHA are frozen,
so the base cannot invalidate. An in-server runner encountering a new source
digest finishes the current generation, builds a new base for the new digest,
and sends later requests to it. It never mutates a base under running tests.

### The production fork constructor

The proposed `seon.cluster/start-fork!` is the one function that turns a source
base into a running cluster instance. It is extracted from the existing
production sequence in `stand-boot-layers!` and `stand-cluster-runtime!`; those
functions do not acquire a second test-facing form.

Its required work, in order, is:

1. acquire the existing Datahike roster permit;
2. call `seon.cluster.registry/ensure-cluster!` from the base source commit;
3. call `seon.cluster.store/open-branch!` for the new branch;
4. call `seon.cluster/require-activation!` on that branch's immutable database
   value;
5. create a branch-owned projection state from the canonical projection and
   current branch basis;
6. fork the base SCI `ctx`, attach only the new connection and projection
   state, and verify the program generation;
7. apply the production compiled config and seed ordinary requested test facts;
8. construct the production cluster and agent graphs through the existing
   blueprints; and
9. return the same `:seon.boot/instance` shape that `seon.cluster/start!`
   returns.

`seon.cluster/start!` remains the full process-root constructor. After its
REPL-first, root-claim, store, and source-base layers, it calls
`start-fork!`. Tests needing a running system call no private cluster function.

`seon.test-support/with-cluster` is only a bracket:

```clojure
(with-cluster {:seon.test/seed-facts [...]
               :seon.config/manifest {...}}
  (fn [instance]
    ...))
```

The illustrative keys require schema design before implementation. The
important contract is that the helper passes ordinary production inputs to
`start-fork!`; it cannot accept procs, conns, channels, atoms, a projection
executor, a prebuilt cluster handle, or a replacement production function.

### A. Constructor options considered — settled by the morning ruling

1. **Parameterize full `seon.cluster/start!` down for every test.** This is the
   smallest public API change and uses production, but it repeats process-root,
   REPL, file-store, HTTP, and search work for tests that need only a sovereign
   cluster. It also pushes every test toward scratch directories.
2. **Recommended and owner-ruled: extract `seon.cluster/start-fork!`, used by
   both full boot and tests.** One source base is acquired once; the production
   fork-to-running boot sequence is shared. `seon.cluster/start!` remains the complete
   boot proof, and tests call the thinner production boundary.
3. **Test tiers with independent constructors.** Pure, graph, and scratch-root
   tiers can be fast, but they create a policy layer in which a “graph fixture”
   can drift from boot. Rejected. Fidelity differences are subjects of direct
   platform tests, not alternative construction systems.

### B. Isolation options considered — settled except for direct boundary proofs

1. **Recommended and owner-ruled: one source base; one Datahike branch, SCI
   fork, projection state, and production instance per ordinary test.** Branch
   facts and interpreter mutations are isolated; process-root executors and
   immutable source artifacts are shared as production shares them.
2. **One scratch operator root per test.** Required only when the subject is
   process-root storage, `flock`, kill/restart identity, advertisement, or full
   boot. Using it for ordinary tests repeats the expensive layer and creates
   disk artifacts without adding relevant fidelity.
3. **Fresh in-memory database unrelated to the production source branch.** It
   is superficially simple but is the fixture class that failed five times.
   Rejected and deleted.

Multi-JVM tests do not invent a second mechanism. Their parent test has an
ordinary claimed suite root; it starts child JVMs through the production
operator against unique claimed descendants or cluster branches, records exact
child process identities, and reaps them through the same operator lifecycle.
The `flock`/kill-9 proof is a direct store moving-part test and remains outside
the cheap-fork population by design.

### E. Shared-base options considered — settled by the morning ruling

1. **Build a source base once per namespace.** Easy to schedule, but the 106
   namespace gate repeats publication up to 106 times and prevents one suite
   source identity.
2. **Recommended and owner-ruled: build exactly once per suite source commit.**
   Every test gets a Datahike and SCI fork. Test-specific changes stay on the
   fork and cannot invalidate the base.
3. **Build per test.** Current slow behavior and the source of construction
   drift. Rejected.

The caveat is explicit: a fixture-load path is not a boot path. Tests whose
subject is reset, publication, activation failure, file-store reopen, `flock`,
or process replacement must create the real boundary they claim to prove.
They are the small direct set below; their existence does not authorize any
ordinary fixture to bypass `start-fork!`.

## Minimal direct regressions for the platform's moving parts

The target is seven consolidated direct tests. The names below are proposed
durable test identities; implementation may preserve an existing name when it
already expresses the complete class.

| Moving part | Minimal direct regression | Required proof | Current tests to absorb or reduce |
|---|---|---|---|
| Store basics | `store-reopens-byte-faithfully-under-one-holder` | Create/write/release/reopen preserves data; same-process double open refuses; failed release retains the fence | `open-write-release-reopen-preserves-data`, `one-holder-per-store-in-one-process`, `a-failed-release-never-drops-the-fence` |
| `flock` and process death | `flock-refuses-a-second-jvm-and-releases-after-exact-kill` | A foreign JVM cannot open the held physical store; exact kill-9 releases the OS lock; an in-process refusal does not drop it | the two current long `cluster.store-test` subprocess tests |
| Branch fork | `cluster-forks-are-sovereign-concurrent-and-resettable` | Fork from exact source commit; concurrent roster creation loses nothing; sibling writes do not bleed; reset returns only one branch to source | four `cluster.registry-test` examples/properties |
| Publication and activation | `publication-seals-complete-activation-before-any-fork` | Missing schema/default/lookup/function prerequisite refuses before fork; successful scratch publication advances one head and retires scratch; an existing cluster remains sovereign | the corresponding `cluster.source-test` and real-boot sovereignty tests |
| Production Flow construction | `production-constructor-builds-and-stops-every-proc-under-one-projection` | The production graph blueprints are used; every proc has `:io` or `:compute`; the one per-cluster projection reaches proc and submitted IO execution; start/resume/stop publishes completion; Var hot reload still applies | `flow-configuration-test`, the production graph-definition test, the hot-reload control test, and a cross-thread projection regression |
| Atomic settlement | `settlement-is-terminal-atomic-and-model-equivalent` | Receipt outcome is settle-once; program/schema/defs/delivery facts share the terminal transaction; holder mismatch refuses; a generated transition model agrees | the core `cluster.run-test` settlement examples/property and duplicated turn settlement checks |
| SCI fork and projection | `cluster-sci-forks-are-isolated-current-and-cheap` | Forked Vars are copy-on-write; custody and projection are branch-specific; runtime schema changes advance only that fork; cold recovery gives the same program; timing is reported | the focused fork/projection tests in `sci.eval-test` and the five projection-bite regressions |

These tests prove the tools used by all other tests. They should be small and
structural. Large scenario suites do not become “moving-part tests” merely
because they happen to touch a branch or graph.

The recurring deterministic R7 drive runs **on a normal `start-fork!`
environment inside `bin/test`**. It is not an eighth construction path. At
each checkpoint it executes the complete causal chain with the deterministic
provider; release cadence adds the shipped provider and graphical QA against a
real boot root.

## Hand-built fixture deletion inventory

The following search was performed over all `test/**/*.clj*` for
`create-flow`, `var-process`, and direct Flow process construction. Every raw
graph site is accounted for.

| Namespace and current helper/site | Disposition |
|---|---|
| `test/seon/cluster/agent_test.clj` `with-connection` | Delete the render graph, hand-built cluster handle, work launcher, channels, and atoms; use `with-cluster` |
| `test/seon/cluster/agent_test.clj` hot-reload `control` graph | Delete the namespace-local graph; retain its negative-control assertion only inside the direct production-Flow regression |
| `test/seon/cluster/agent_test.clj` `routing-trial` armer graph | Delete; exercise routing through the production cluster constructor |
| `test/seon/cluster/turn_test.clj` `with-render-context-proc` | Delete; the production instance already owns the render proc and projection executor |
| `test/seon/cluster/turn_test.clj` `render-proc-for` | Delete; it currently omits the production projection executor and is another live instance of the failure class |
| `test/seon/cluster/prompt_test.clj` `planted` graph | Delete; seed facts on a fork and request context from its production render graph |
| `test/seon/cluster/loop_test.clj` `with-render-context-proc` | Delete; use the production instance |
| `test/seon/gen/loop_test.clj` `with-render-context-proc` | Delete; use the production instance |
| `test/seon/render/web_test.clj` `with-server` graph | Delete the manually assembled render pipeline; retain socket/browser behavior against the production instance |
| `test/seon/flow_test.clj` `source-testbed` raw graph | Delete the local graph helper; the minimal direct Flow regression calls the one production graph-construction owner |
| `test/seon/cluster/boot_test.clj` executor-observation raw graph | Delete the local allocation; retain the workload assertion through the one production graph-construction owner |
| `test/seon/flow_configuration_test.clj` graph-definition introspection | Consolidate into the direct Flow regression; it does not remain a broad inventory test |

The current canonical database fixture is also deleted as construction:

- `source-manifest` and test-side `populate-database!`;
- `database-base` and its shutdown hook;
- `branch-leases`, `acquire-branch!`, and `release-branch!`;
- `with-fresh-database`, `with-branched-database`, and `with-database`;
- `reconnect-with-projection`; and
- `seed-cluster!`.

Reusable assertion helpers such as `await-event!`, `refusal-data`, and
`assert-check!` remain. Scratch allocation becomes a claim-aware bracket over
the suite root; recursive deletion stays owned by `seon.fs`.

## Class-killing program-graph regression

Text search produced the migration inventory, but text search is not the
regression. The durable proof uses the program graph.

Implementation adds the missing queryable facts rather than a list:

- `seon.flow/create-graph` becomes the one first-party owner around
  `clojure.core.async.flow/create-flow`, so construction is a resolvable
  `:seon.fn/calls` target;
- `seon.cluster/start-fork!` declares a boolean function fact identifying the
  production test-system constructor;
- each of the six moving-part owners declares a boolean function fact marking
  that it is directly constructible in its own subject test; and
- a direct moving-part test declares the existing `:seon.test/subject` ref to
  that owner.

The regression derives all tests transitively reaching
`seon.flow/create-graph`. A row is legal exactly when either:

1. the same test transitively reaches `seon.cluster/start-fork!`; or
2. its direct `:seon.test/subject` has the declared moving-part fact.

Every other row is a failure with the test symbol and shortest call path. The
same pattern applies to any function that constructs a cluster instance or
projection state. No namespace prefix, filename, source regex, literal symbol
set, or exception vector participates. Adding a new offending test makes the
query fail automatically.

## Artifact lifecycle and disk bound

### C. Retention options

1. **Last `N` failed roots only.** Simplest and matches the eval-sample ruling,
   but one retained root can still be arbitrarily large. This is a count bound,
   not a disk bound.
2. **Recommended: last `N` plus aggregate retained bytes.** Keep newest roots
   in deterministic claim order only while both the count and byte facts fit;
   reap older roots at the next run's start. This directly answers the disk
   mandate while retaining recent evidence.
3. **One archive per failure class.** It can retain more diverse evidence in
   fewer bytes, but requires deriving a stable failure class before cleanup and
   risks a second artifact policy. Defer unless measured evidence shows option
   2 loses necessary diagnosis.

The recommended config facts are declared once in the normal config registry:

- `:seon.config.test/retained-failure-roots` — proposed default `3`;
- `:seon.config.test/retained-failure-bytes` — proposed default `1073741824`
  (1 GiB aggregate); and
- existing maintenance usable-byte and usable-ratio facts remain the
  low-space refusal inputs.

These are policy facts, not hard-coded shell values. The byte value is an owner
review proposal. Retention selection orders by recorded terminal instant and
claim id, never filesystem mtime.

### One claim, one root, all descendants

`bin/test` currently creates `tmp/test-runs/run.*`, writes a PID record, deletes
the root on success, and retains it on failure. It also now contains a
provisional literal scanner that keeps three inactive roots younger than 24
hours. That scanner was not present in the provenance baseline and does not
satisfy this design: it uses directory discovery plus PID-only `kill -0`, has
literal bounds, and does not publish an operator claim.

The replacement sequence is:

1. select an exact future run path below `tmp/test-runs`;
2. call the existing operator claim owner with repository root, exact run root,
   and launcher `(pid, start-instant)` **before mkdir**;
3. create the root and mark it created on that claim;
4. put every test-owned file below it;
5. on success, stop exact child identities, record footprint and cleanup
   result, delete the root through `seon.fs`, and retire the claim file after
   the cleanup result is durably represented;
6. on failure or signal, reap exact children first, record terminal reason and
   footprint on the claim, then leave the claimed root; and
7. at the next run's start, invoke the operator reaper over claims and the two
   retention facts before allocating the new root.

The existing operator cleanup currently targets `<managed-root>/data/clusters`.
Test roots require one accretive claim fact naming the exact removable path as
the complete run root; production managed-root cleanup keeps its existing
target. This extends the one claim authority instead of adding a test registry.

### Complete artifact inventory

| Artifact | Declared location | Owner and successful reap | Failure retention |
|---|---|---|---|
| Isolated suite root and copied checkout | `tmp/test-runs/run.*` | `bin/test` launcher identity through external root claim; complete no-follow delete | Root-level count and aggregate-byte bounds |
| Datahike store, branch heads, store lock | `<run>/data/clusters/store*` or suite-base store below `<run>/data` | Production store and cluster stop; root cleanup after all connections release | Inherits root bound |
| Blob values and staging | Below the suite store or `<run>/tmp/.../blob-staging` | `seon.blob` publication/cleanup, then root owner | Inherits root bound; staging must be empty after success |
| Search index | `<run>/data/clusters/<cluster>/search` | Production cluster stop closes index; root owner deletes bytes | Inherits root bound |
| Process and test logs | `<run>/logs` | Operator log owner; root owner on success | Per-log rotation facts plus root byte bound |
| Liveness text and virtual-thread dumps | `<run>/tmp/test-liveness` | Test runner creates one diagnostic set; root owner | Retained only with failed root |
| Test scratch and file-store probes | `<run>/tmp/<test-id>/...` | Claim-aware scratch bracket; root owner is final backstop | Inherits root bound |
| Shell/web/edit child files and captured output | `<run>/tmp/<test-id>/...` and blob store | Capability test plus exact child-process owner; root owner | Inherits root bound and capability output caps |
| `test-run.txt` and retained reason | `<run>/test-run.txt` until migration; then reason also on external claim | Launcher; root owner | Retained only with failed root |
| Changed-test logs | A claimed invocation descendant below `tmp/test-runs`, not the current separate `tmp/test-changed` pool | Changed-test parent claim; same root cleanup | Same config facts, replacing literal “20 logs” |
| Opt-in result cluster files | Default below `<run>/data/clusters`; an explicit external result root must already have an exact operator claim | `seon.test.runner/record!` and named operator root | External owner policy; never silently exempt |
| Root claim control record | Installation control root outside the removable path | Operator state owner; retire successful test claim after recorded cleanup | Failed claim persists exactly while its root is retained |
| Shared dependency-class cache symlink | Source checkout `target/dev-dependency-classes` | Dev-cache owner, not the suite | Never traversed or deleted by suite cleanup |

`test/seon/dev/issues_test.clj` currently creates an OS-managed temporary
directory with no project parent. It migrates below the claimed suite scratch
root. The other `Files/createTempDirectory` calls already select project `tmp/`
but migrate to the explicit claimed-root allocator so their authority does not
depend on working directory.

### No-follow deletion law

Recursive cleanup receives two canonical paths: the exact authority root and
the exact target below it. It walks without `FOLLOW_LINKS`; a symlink is a leaf
to delete, never a directory to traverse. It refuses the authority root unless
the external claim explicitly names that complete root as removable. The
sentinel outside the symlink must survive.

The direct owner regression remains
`seon.fs-test/recursive-deletion-never-crosses-a-symlink`. The overlapping
test-support regression can be reduced to proving the claim-aware bracket
delegates to that owner, rather than maintaining a second deletion algorithm.

### Honest limit of the guarantee

The retained inactive set is strictly bounded after every run-start reaping
and successful cleanup. Count plus bytes prevents historical failures from
filling the disk, which is the 245 GiB scar.

No retention policy can prevent the currently active test from intentionally
writing unbounded bytes before its owner observes them. Production output
ceilings, log rotation, blob staging cleanup, and existing low-space facts
bound normal producers. If active-run growth remains material after migration,
add a separately owner-ruled active-root byte refusal at the artifact-producing
owners; do not pretend last-`N` solves it.

## Performance design

### Evidence from the 2026-08-06 green bare gate

`tmp/bare-gate-2026-08-06d.log` contains 106 complete namespace BEGIN/END
pairs totaling 965.858 seconds. The run is serial in
`seon.test.runner/run-selected-tests`, which `doseq`s namespaces and invokes
`clojure.test/test-vars` for each.

| Namespace | Seconds | Share of namespace time |
|---|---:|---:|
| `seon.shell.jvm-test` | 207.862 | 21.5% |
| `seon.web.jvm-test` | 202.127 | 20.9% |
| `seon.cluster.turn-test` | 72.136 | 7.5% |
| `seon.reconcile-test` | 48.910 | 5.1% |
| `seon.cluster.curate-test` | 48.411 | 5.0% |
| `seon.render.transcript-test` | 44.115 | 4.6% |
| `seon.blob-publication-test` | 41.336 | 4.3% |
| `seon.render.web-test` | 38.440 | 4.0% |
| `seon.schema.admission-test` | 35.851 | 3.7% |
| `seon.repl-parity-test` | 34.163 | 3.5% |

The tree has 1,023 bare test executions in that log. The present source has
187 textual calls to the shared database fixture, 50 cold `cluster-ctx` calls,
and 28 `seed-cluster!` calls. Those counts are migration scope, not runtime
invocation counts.

### G. The top-two options and concrete cut

Both top namespaces own a private `with-file-database` that opens a new file
store and calls the private test `populate-database!` for every `deftest`.
Shell has six tests; web has six. The shell tests do launch real subprocesses,
including one 750 ms time-limit proof and one 2 MiB dual-stream proof. The web
tests do run real loopback HTTP, including one 100 ms timeout. Those meaningful
waits account for seconds, not the observed 410 seconds.

1. **Build one file-store base per namespace, branch per test.** This removes
   eleven of twelve populations with a small edit, but preserves two private
   fixture owners and two suite bases.
2. **Recommended: delete both private fixtures and use the one suite source
   base plus `start-fork!`.** Each test keeps its real subprocess or loopback
   server and gets an isolated branch. Blob bytes are content-addressed and
   branch facts remain sovereign. This removes twelve duplicate populations
   and the private branch construction.
3. **Share one mutable connection across each namespace.** Fastest-looking but
   lets receipt/config/history facts bleed and makes ordering matter. Rejected.

The concrete acceptance cut is that the combined two namespaces fall from
409.989 seconds to under 15 seconds serial on the same machine, while their
existing byte-exact, process-reap, timeout, redirect, and receipt assertions
remain. Fifteen seconds is derived from their current meaningful waits plus a
large scheduling allowance; the first migrated run must replace it with an
observed number.

### F. Namespace parallelism options

The branch and SCI primitives are safe foundations, but the complete current
platform is **not yet safe for concurrent sovereign environments in one JVM**.
Independent source inspection found four production boundaries:

- `seon.schema/!shape-generation` is one process-global compiled validator
  cache, and `ensure-shape-generation-for!` checks one dereference then returns
  a second; another environment can replace the generation between those
  reads;
- `seon.schema` resolves declarations through dynamic projection bindings
  behind Malli's process-global default registry, so a raw or virtual-thread
  hop without binding conveyance silently falls back to packaged forms;
- `seon.schema/!predicate-functions` is keyed only by qualified symbol and is
  process-global; and
- `seon.flow/submit!` conveys the completion binding but not the submitted IO
  `work-fn`, while sibling `submit!!` does convey its compute `work-fn`.

These are platform isolation defects, not reasons to invent a test scheduler
workaround. They belong to the projection-acquisition moving part and must be
fixed at the production owners before parallel tests are enabled. Datahike
branch concurrency and concurrent `sci/fork` remained isolated in the audit.

1. **Recommended landing: keep namespaces serial until explicit projection,
   per-projection caches, predicate resolution, and IO submission isolation
   regressions are green.** This is the only presently safe option;
   conservative projected namespace work is roughly 7–10 minutes.
2. **Target after that gate: four bounded in-JVM workers for
   fork-safe namespaces; serialize process-exclusive subjects.** One JVM
   preserves one immutable base. Each namespace remains internally sequential.
   Branches, connections, explicit projections, and SCI forks then make
   ordinary tests safe by construction.
3. **One JVM worker process per shard.** OS isolation makes global Var mutation
   easy, but every process rebuilds the source base and dependency load. It
   contradicts the one-base ruling and multiplies roots. Rejected as the default;
   direct multi-JVM proofs still spawn exact children when that is their subject.

Option 2 requires the platform first to make projection an explicit
environment input at every cross-thread operation, key compiled schema state
by projection, and resolve predicate Vars without a last-writer-wins cache.
After those direct regressions pass, the runner must:

- force `source-base!` before submitting work;
- derive process exclusivity from program-graph reachability to declared
  process-global leaves, absorbing the in-server PRD's first owner question;
- run each fork-safe namespace on a bounded worker, with clojure.test dynamic
  counters and output captured per namespace;
- feed reporter events to one ordered aggregation channel and print final
  results in the original deterministic namespace/test order;
- serialize tests whose subjects include full boot, `flock`, process signals,
  global Var-root mutation, global system properties, or another observable
  process-global boundary; and
- cancel no sibling on an ordinary assertion failure, so the full result set
  and cleanup events are observed.

The program graph currently retains calls only to known first-party rows.
Process exclusivity therefore needs a declared first-party leaf at every such
boundary or an analyzer fact for the external call; a filename flag or hand
list is not acceptable. This is the one real prerequisite to enabling
parallelism safely.

### Projected gate time

There are three levels of confidence:

1. **Observed present:** 965.858 seconds of namespace execution, serial.
2. **Measured serial projection:** removing the twelve shell/web cold
   populations and substituting the observed production fork/SCI costs gives
   about 435 seconds. Because static call sites undercount generated
   invocations while including some full-only sites, report this as a range of
   7–10 minutes until constructor timing is emitted by the runner.
3. **Conditional four-worker projection:** after the production projection
   defects above are fixed, 435 / 4 is 109 seconds ideal. Adding the measured
   11.865 second base build, JVM/load overhead, serialized direct proofs, and
   imbalance supports a `<3 min` bare target. This is a target to prove, not a
   claim that the current suite is presently safe to attempt in parallel.

The first implementation wave adds timing events for base build, branch fork,
SCI fork, graph start, test body, and teardown. The final report shows p50/p95
and accumulated time by boundary. Optimization decisions after that use those
events, not namespace duration guesses.

### H. Development-loop budget options

Performance facts are budgets and observations, never execution timeouts.
Their proposed names and recommended values are:

| Fact | Recommended value | Surface |
|---|---:|---|
| `:seon.config.test.performance/base-build-max-ms` | `15000` | one suite source base |
| `:seon.config.test.performance/fork-p95-max-ms` | `50` | branch + projection + SCI + graph fork |
| `:seon.config.test.performance/focused-p95-max-ms` | `30000` | normal changed-test selection |
| `:seon.config.test.performance/bare-max-ms` | `180000` | checkpoint bare gate |
| `:seon.config.test.performance/full-max-ms` | `600000` | release full gate |

Options for owner review:

1. **Loose first landing:** focused `<60 s`, bare `<5 min`, full `<15 min`.
   Easier migration but tolerates a slow feedback loop.
2. **Recommended:** focused p95 `<30 s`, bare `<3 min`, full `<10 min`, with the
   component budgets above. This is consistent with the measured fork costs
   and leaves honest room for real process proofs.
3. **Fresh-week aspiration:** bare `<60 s`. Worth pursuing after timing shows
   the remaining semantic work, but not credible as the first migration gate
   while several retained generative proofs individually take 25–48 seconds.

The ordinary developer loop starts with
`seon.dev.changed-test/run-changed!`, which selects affected tests and invokes
the same `bin/test` infrastructure. A bare gate runs at coherent checkpoints.
`bin/test --full` runs at release cadence and before changes to store, process,
publication, or recovery laws. Explicit namespace selection continues to run
all tests in those namespaces.

## Migration and proof

### D. Migration-order options

1. **Recommended: constructor and class query first, then the five bitten proof
   namespaces, then measured cost order.** This makes the failure class
   unrepresentable before broad mechanical conversion and immediately proves
   the historical bites.
2. **Mechanically replace all 187 fixture call sites first.** It may produce a
   large quick diff, but without the constructor/query choke point new hand
   fixtures can arrive during migration and failures are hard to localize.
3. **Convert slowest namespaces first.** Delivers performance sooner but leaves
   graph drift possible. Use cost order only after the proof set lands.

### Ordered migration

1. **Instrument and freeze the baseline.** Preserve the 2026-08-06 namespace
   report, add constructor-stage timing to the runner, and record current root
   footprint through operator claims.
2. **Land the platform base and constructor.** Implement `source-base!`,
   `start-fork!`, `seon.flow/create-graph`, and the thin
   `test-support/with-cluster`; make full `cluster/start!` call the same fork
   constructor.
3. **Land the seven moving-part regressions and the program-graph query.** The
   query is red until every raw test graph is removed or absorbed into a
   declared direct subject.
4. **Convert the five projection-bite proof set first.** In order:
   `seon.cluster.agent-test` (`61ccb7332`), `seon.cluster.curate-test`
   (`d1781801d`/`f3148e1f0`), the shared database/projection fixture
   (`3f276226f`/`45f7481ca`), `seon.cluster.turn-test` (`220972135`), and
   `seon.cluster.prompt-test` (`19601a5de`). Delete their imitation fixtures in
   the same wave.
5. **Delete every remaining hand graph.** Convert `cluster.loop-test`,
   `gen.loop-test`, and `render.web-test`; consolidate direct Flow tests.
6. **Remove the top-two duplicate populations.** Convert shell/web JVM tests
   to the suite base and record the expected roughly 400-second cut.
7. **Convert the remaining fixture-heavy namespaces in measured order.** Start
   with turn, reconcile, curate, transcript, blob-publication, admission,
   repl-parity, agent, SCI eval, and MCP. Then mechanically convert the rest of
   the 187 call-site census.
8. **Replace artifact scanning with claims and config-backed retention.** Move
   changed-test logs and the system-temporary issues fixture below the claim;
   delete the literal three/24-hour and 20-log policies.
9. **Enable parallel workers only after both isolation gates are green.** The
   process-exclusive query must be empty for the parallel population, and the
   direct schema-cache, thread-hop projection, predicate-resolution, and IO
   submission regressions must pass. Then compare serial and four-worker
   results over the same namespace set and assert identical sorted outcomes.
10. **Put R7 on the infrastructure and graduate.** Run the deterministic drive
    on an ordinary fork in every bare checkpoint; run reset-boundary, shipped
    provider, and graphical proofs at release cadence.

### Cost estimate

The estimate is based on the current census: one production boot extraction,
seven direct regressions, 12 raw graph sites, 187 shared-database call sites,
50 cold-context sites, two artifact launchers, and one runner scheduler.

| Work | Estimated engineering days |
|---|---:|
| Production source-base/fork extraction and full-boot composition | 3–5 |
| Direct moving-part regressions plus program-graph facts/query | 2–3 |
| Five bitten namespaces and remaining raw graph deletion | 3–5 |
| Shell/web cut and remaining fixture conversion | 4–7 |
| Claim-backed artifacts and bounded retention | 2–4 |
| Production projection/cache/thread-hop prerequisites plus parallel runner, deterministic reporting, and performance facts | 4–7 |
| Integrated bare/full/reset/R7 evidence | 2–3 |
| **Total** | **20–34 engineer-days** |

The range is intentionally broad because direct tests may collapse many
current examples, while process-global parallel classification may reveal a
missing program-graph fact. The work should land in coherent waves; it is not
one review-sized implementation commit.

## In-server test questions absorbed by this design

The four questions in `docs/prds/archive/in-server-tests/README.md` no longer need four
independent mechanisms:

1. **Process-unclean declaration:** recommend derivation through the program
   graph from declared process-global leaves. This is also the prerequisite for
   namespace parallelism. The per-test flag remains an owner fallback only if
   the required external call facts prove unavailable.
2. **Reload contract:** one suite base is immutable for one source digest. A
   changed digest gets a dependency-ordered reload/publication closure and a
   new base generation; an old base is never patched under tests.
3. **Live-cluster selection:** ordinary tests never run against a user's live
   cluster. A direct live/reset proof must name `--live-cluster`; an absent
   selection may resolve only when exactly one eligible scratch cluster exists.
4. **Agent-run report facts:** a terminal test receipt is the default durable
   report. Duplicate per-test result history is opt-in through
   `--result-cluster`, and that result root must be claimed and retained under
   its operator policy.

Only question 1's choice between derived classification and an explicit flag
remains entangled with the owner's parallelism ruling. The other three are
absorbed by the immutable-generation and artifact rules above.

## Graduation gates

Implementation is complete only when all of the following are observed:

- the seven direct moving-part tests pass through production owners;
- the program-graph query returns zero unauthorized graph constructors and
  zero unauthorized projection constructors;
- the five historical proof namespaces pass after their local graphs and
  projection fixtures are deleted;
- every ordinary test receives a distinct branch, connection, SCI fork, and
  projection state from the one source base;
- mutating one fork cannot change the base or a sibling fork;
- two concurrent forks cannot exchange a compiled schema, predicate function,
  ambient declaration population, or IO-submission projection;
- reset-boundary tests still use real boot and catch a deliberately broken
  activation or `flock` boundary;
- shell plus web JVM tests meet the ruled combined target without replacing
  real subprocess/HTTP behavior;
- the changed-test selector, bare gate, full gate, and R7 drive all invoke the
  same constructor and artifact lifecycle;
- a successful run leaves no claimed run root, child process, blob staging
  file, liveness dump, scratch directory, or active branch;
- repeated failed runs retain no more than the configured root count or byte
  total after the next run starts;
- symlink sentinel referents survive every cleanup path;
- killing the launcher and runner in each ordering leaves an exact retained
  reason, reaps children, and is cleaned by the next claim-based run;
- serial and parallel executions return the same sorted outcomes; and
- the final measured report meets the owner-ruled focused, bare, and full
  budgets or records the exact boundary that does not.

## Current-tree discrepancies and ugly output met

The review should know about four current discrepancies:

1. `bin/test` lines describing “in-memory Datahike, no artifact, no operator”
   conflict with its actual isolated operator root, file directories, copied
   checkout, retained failure root, and optional file-backed result cluster.
2. The new literal “three roots younger than 24 hours” scanner is useful
   emergency behavior but is neither the provenance's unbounded state nor this
   spec's claim/config-backed target.
3. `docs/seon/architecture/library-grounding.md` names an older Datahike
   revision than the current gitlink.
4. Every direct Clojure timing probe printed both an incubator-module warning
   and a conflicting `:java-home` overwrite warning before the one useful
   result. One initially malformed probe also caused Clojure to write its error
   report under the OS temporary directory. The corrected measurements above
   succeeded, but this startup/error face is noisy and the external error-report
   location violates the spirit of claimed test artifacts when it occurs under
   the test runner.
5. A concurrent isolation audit left an untracked research report and issue
   notes in the shared checkout despite this lane's spec-only boundary. They
   were not edited, staged, or included in this commit. Its high-risk schema
   and Flow claims were checked independently against the production source and
   are reflected in the conditional parallelism design above.

These are reported here because this lane is restricted to the spec document;
they are not silently fixed as part of design review.

## Addendum: what the tiering landed, 2026-08-07 night

The owner's directive that opened this lane: "fix the test suite so it runs
faster before you start gating things on it passing. It just bogs everything
down and we can run individual namespaces much easier for targeted testing."

This addendum records what is LIVE against what this specification still
describes as unbuilt. The production `source-base!`/`start-fork!` constructor,
the seven consolidated moving-part regressions, the claim-backed artifact
lifecycle, and namespace parallelism are all still ahead; the tiering that
makes the ordinary loop cheap is in.

### Landed

**Tiers, in `bin/test` + `seon.test.runner` + the new `seon.test.selection`.
No second runner.**

| Invocation | Meaning |
|---|---|
| `bin/test` | platform tier, then only the tests reaching code changed since the last recorded GREEN basis |
| `bin/test --all` | platform tier, then every non-long test (the previous bare behaviour) |
| `bin/test --full` | platform tier, then every test |
| `bin/test --platform` | the declared moving-part regressions alone |
| `bin/test --changed PATH` | platform tier, then the tests reaching PATH (repeatable) |
| `bin/test NAMESPACE...` | unchanged: every test in those namespaces, no tiers, no basis |

**Platform first, fail-fast.** A test namespace or var declares
`:seon.test/platform "<reason>"` exactly as `:seon.test/long` already worked
(one `marker-reason` reader, both markers). Every tiered invocation runs that
set first and, when it is red, prints `PLATFORM TIER RED` and does not run the
bulk. Measured value on its first night: an `--all` invocation returned the
platform verdict in 37 s instead of spending ~30 minutes to report the same
three fixture errors.

**Changed-only default, from the program graph.** `seon.test.selection` derives
the bulk tier from the `:seon.fn/calls` and `:seon.test/subject` edges of the
manifest `seon.fn/build-manifest` already produces — the same edges
`seon.fn/tests-reaching` queries once a program graph is published. The gate
runs before any cluster exists, so it reads them from the manifest value rather
than a database. Seeds are every identity DEFINED in a changed file, so a
require-only edit still selects that namespace's dependents. No modification
time, filename convention, or maintained list participates.

**The green basis is a recorded artifact**, `tmp/test-basis/green-basis.edn`,
holding SHA-256 by repository-relative path for every declared gate input plus
the recording run's mode and git SHA. It is a file rather than a database fact
because the gate runs before any cluster exists and its own run-root store is
deleted on success; there is no database home for it yet. When the in-server
runner of this specification lands, the basis becomes a fact and this file
goes away. Digests are content, so rewriting identical bytes is not a change.

**Widening is loud and named**, never a silent narrow guess: no recorded basis,
an input removed since the basis, or a change to a declared gate input that no
call edge can reach (`resources/`, `config/`, `script/`, `bin/test`, `bb.edn`,
`deps.edn`, `.clj-kondo/config.edn`) all run every eligible test and say which
input widened them.

**One selector.** `seon.dev.changed-test` carried a second one — a
namespace-require reverse closure over hand-partitioned "operator" and "writer"
test roots. It now names the changed paths and runs `bin/test --changed`;
`host-impact`, `reverse-closure`, the two path partitions, and the two boundary
runners are deleted. Its clj-kondo `analyze-host` pass stays for lint findings.

**Class regression:** `test/seon/test/selection_test.clj`, itself in the
platform tier. It asserts exactness in BOTH directions — every reaching test
present, every non-reaching test absent — so a selector that returns everything
fails it. It also pins content-not-mtime detection and the symlink rule (a
symlinked FILE is digested through the link because `bin/test` symlinks
top-level files into its run root; a symlinked DIRECTORY is never descended).

### Measured

Machine: the owner's M-series Mac, same as the 2026-08-06 baseline.

| Measurement | Before | After |
|---|---:|---:|
| Bare gate, no input changed | 965.9 s of namespace execution (2026-08-06 green bare gate) | **43.6 s wall** — platform tier only; the selector chose 0 of 969 bulk tests |
| Bare gate, one changed file | same 965.9 s | **68.7 s wall to the platform verdict**; 5 changed paths (four of them a sibling lane's), 207 reaching tests, **162 of 969 bulk tests selected — 83% skipped** |
| Program-graph build, when a change exists | n/a | 7.4 s (`seon.fn/build-manifest` over `src` + `test`, 206 files); skipped entirely when nothing changed |
| Runner load phase | 5.9 s | 8.0 s (116 namespaces; unchanged mechanism) |
| Platform tier | n/a | 24.7–31 s over 12 namespaces |
| **Green end-to-end bare run**, one changed file (`src/my/background.clj`) | same 965.9 s | **52.5 s wall, 0 failures**, and it recorded the new green basis: 69 platform tests + the 1 bulk test that reaches the change, 977 not reached |

The green end-to-end run is the decisive one: 52.5 s from `bin/test` to
verdict, where the same question previously cost roughly sixteen minutes.
Within it, the manifest build was 5.6 s and the platform tier 28.4 s, so the
platform tier is now the whole cost of an ordinary cycle — which is precisely
where the next optimisation belongs (the seven consolidated moving-part
regressions, small and structural, replacing today's cheap-but-real store,
branch, publication, and fixture namespaces).

The bulk tier's own runtime under the new default is NOT yet measured: the
platform tier was red from foreign in-flight work throughout the measurement
window (first `seon.test-support/populate-database!` refusing initialization
lookup refs, then `seon.env-test/a-submission-carries-the-submitting-threads-interrupt-arm`).
The full-tier time is likewise unmeasured for the same reason. The honest
statement is that the default tier now returns its verdict in 44–69 s where it
previously took roughly sixteen minutes, and that the bulk work it does run is
83% smaller on a one-file change.

### Platform tier composition, and why it is what it is

Declared today, with measured cost: `seon.cluster.store-test` 2.7 s,
`seon.cluster.registry-test` 6–9.7 s, `seon.cluster.source-test` 7.6–8.5 s,
`seon.test-support-test` 8.5 s, `seon.sci.kernel-arm-carriage-test` 0.5 s,
`seon.db.declaration-population-test` 0.4 s,
`seon.sci.admit.declaration-population-test` (added by a sibling lane),
`seon.schema.declaration-population-test` 0.14 s, `seon.env-test` 0.07 s,
`seon.test.selection-test` 0.03 s, `seon.flow-configuration-test` 0.00 s,
`seon.fs-test` 0.00 s, `seon.cluster.cohost-boot-test` (all `:seon.test/long`).

Three of this specification's moving parts have NO platform coverage yet:
production Flow construction, atomic settlement, and sci fork plus projection.
Their namespaces are large scenario suites — `seon.sci.eval-test` 118.9 s
(71.9 s of it one generative test), `seon.cluster.run-test` 39.7 s,
`seon.flow-test` 12.8 s — and this specification is explicit that a large
scenario suite does not become a moving-part test by touching a branch or a
graph. Declaring their small structural vars instead was tried and reverted:
`seon.flow-test/submission-time-limit-covers-the-pre-start-wait` hangs when run
without its namespace siblings, which wedged the gate at the 300 s liveness
backstop
([issue](../../../seon/issues/a-flow-test-hangs-when-run-without-its-namespace-siblings.md)).
The durable fix is this specification's seven consolidated direct regressions,
which are small and structural by construction.

### The selector's one honest gap

The selection is exactly as good as the `:seon.fn/calls` facts, and one shape
records no edge: a test that exercises a MACRO only by `macroexpand-1` of a
quoted form. `my.background-test` is the worked example — of its two tests,
`poll-and-await-…` carries five call edges and is selected when
`src/my/background.clj` changes, while `background-macro-expands-one-direct-call`
carries none and is not.

This is a missing FACT, not a flaw in the walk, and the fix belongs at the one
index pass in `seon.fn` rather than in the selector: a quoted symbol resolving
to a first-party macro in a test body is a real usage the analysis discards.
Widening the selector by namespace name instead would be the banned naming
convention. Until the fact exists, `--all` at checkpoints covers it, and the
gap is bounded — editing the test file itself always selects that file's tests.

### Deliberately not done

**`situation-totality-property` (~55 s) is untouched.** The read-side lane was
right to refuse masking it in the fixture, and the tiering makes the question
moot the way the ruling intended: it runs only when a change reaches
`seon.cluster.work`. Marking it `:seon.test/long` would ALSO remove it when
someone changes that namespace — exactly when the property earns its cost — so
it stays in the default tier. Its 14 ms-per-transaction floor is the write-seam
declaration population, and Phase 1 of the environment work erases it.

**The 1.9 s render pass is untouched** (its issue,
`render-package-proc-reruns-unchanged-renderers`, is open and owned elsewhere).
The tier system stops the bare gate paying it on unrelated changes, which is
all this lane owed it.

### What remains, in this specification's own order

Unchanged: the production `source-base!`/`start-fork!` extraction, the thin
`with-cluster`, the seven consolidated moving-part regressions plus the
program-graph query that makes hand-built graphs unrepresentable, the fixture
deletion inventory, the claim-backed artifact lifecycle with config-backed
retention, the projection/cache/thread-hop isolation prerequisites, and
namespace parallelism. The tiering neither substitutes for nor blocks any of
them; it changes only WHICH tests one invocation runs, never how one runs.

Two smaller items this lane surfaced and did not take:

1. `bin/test`'s literal three-roots/24-hour retention scanner is still there,
   still not claim-backed. Its replacement is this specification's artifact
   section.
2. The green basis becomes a database fact when the in-server runner lands;
   until then a lane that wants the previous behaviour unconditionally uses
   `bin/test --all`.
