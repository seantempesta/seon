---
type: research
status: proposed, stage 0 review required
created: 2026-09-17
tags: [testing, design, datahike, sci]
---

# Test system Stage 0 design review

**Recommendation:** retain the five existing owners, put selection and immutable
run evidence in the named cluster, claim selected namespace groups at its writer,
and render the recorded reports. Do not begin production changes until the
orchestrator reviews this note and the owner answers §6. In particular, isolated
worker custody needs an explicit constraint; a connection cannot cross JVMs.

Authority: read [the test-system PRD](../plan/test-system-is-the-database-prd-2026-09-17.md)
**end to end**, the parent
[program-facts PRD](../plan/program-facts-are-the-runtime-prd-2026-09-17.md)
**§1b–§1d and §4b in full**, [AGENTS.md](../../../../AGENTS.md) **§0–§5 and §7
in full**, and `tmp/orchestrator/wave2/repl-rule.txt` **end to end**. Applied the
data-oriented-clojure, data-modeling, datahike, repl, and clojure-testing skills.
The assignment's no-tests/no-mutations rule governs this stage; the general
iteration and gate instructions do not authorize a test here.

Inspected working tree based on `7ac59ae197327c70577656cbc82c17e57437656c`.
Read **every line**, including all **3,863 lines** of the runner:

| End-to-end read | Lines |
|---|---:|
| `bin/test` | 807 |
| `bin/_test-slot` | 77 |
| `src/seon/test.clj` | 921 |
| `src/seon/test/runner.clj` | 3863 |
| `src/seon/test/fast.clj` | 61 |
| `resources/seon/schemas/seon.test.edn` | 233 |
| `resources/seon/schemas/seon.test.run.edn` | 45 |
| `resources/seon/schemas/seon.test.failure.edn` | 71 |
| `resources/seon/schemas/seon.test.runner.edn` | 82 |
| `resources/seon/schemas/seon.config.test.edn` | 2 |
| `test/seon/test_runner_test.clj` | 2290 |
| `test/seon/test/runner_test.clj` | 608 |
| `test/seon/test_support_test.clj` | 709 |
| `test/my/test_test.clj` | 88 |

Also read `src/seon/test/selection.clj` and `deps.edn` end to end; read the
complete `clojure.test/report` seam and assertion constructors at
`reference-code/clojure/src/clj/clojure/test.clj:325–550`, and its fixture/execution
seam at `:710–765`. Its names are **report**, **test Var**, **assertion**,
`:pass`, `:fail`, `:error`, `:begin-test-var`, and `:end-test-var`. A report is
data; `report` dispatches on `:type`. `do-report` adds file/line information.
`test-vars` groups by namespace and applies `:once` and `:each` fixtures.
The report type is a bounded outcome, not an entity-kind discriminator.

Dependency ledger: Clojure 1.12.5 (`deps.edn:8`) owns this report/fixture seam;
Datahike's serial writer (`reference-code/datahike/src/datahike/writer.cljc`)
owns transaction decisions, already exercised through `record-tx` and
`commit-results!` (`runner.clj:2097,2313`); SCI's `copy-var*`, `fork`, and `intern`
(`reference-code/sci/src/sci/core.cljc`) own context acquisition through
`seon.sci.eval`, rather than another test evaluator. Tools.build 0.10.5 in
`deps.edn:138` supplies the existing `create-basis` call in `dev_cache.clj:475`.
These paths are the pinned local seams, not an invitation to implement their
mechanisms again. No dependency implementation is changed by this review.

Archaeology: inspected `453f08b5d`'s custody diff and `f272b9e6e`'s recording
change/evidence. Preserve the former's separation of body custody from result
destination. Preserve the latter's delta writer: its measured unchanged
93-result replay went from 157,981 datoms to zero beyond transaction metadata.
Neither should be replaced by a second execution or recording mechanism.

## 1. Function inventory and deletion stages

Paths abbreviated below: `test.clj` is `src/seon/test.clj`; `runner.clj` is
`src/seon/test/runner.clj`. “Retain” means make the existing function the sole
implementation, with complete contracts, not leave both old and new behavior.

| Concern and owner | Existing functions and duplicates | Replacement/deletion |
|---|---|---|
| Selection: `seon.test/select` (new public entry over existing query helpers) | `identity-tests :407`, `changed-reach :432`, `reaching :439`, `stale-in :459`, `namespace-tests :502`; selection embedded in `check-in-process :535`. `changed-since-green :54` is a diagnostic over a test's last green, not the cluster run selector. | Stage 1 routes `check`, `run-owned` admission, and worker requests through `select`. Retain the diagnostic's meaning; delete its use as run-basis policy. Retain query helpers only where they actually supply this owner. |
| Selection duplicates in coordinator | `test-vars-in :642`, marker/long declarations `:653–760`, `test-selection :763`, `indexed-test-symbols :838`, `tests-reaching-rows :854`, `requested-changed-paths :2595`, `reaching-selection :2606`, `bulk-selection :2615`, `record-green-basis! :2667`, coordinator `:3690–3741`. `src/seon/test/selection.clj` walks manifest/file digests and stores `tmp/test-basis/green-basis.edn`. | Stage 1 deletes the file-based selection, green-basis file writer, synthetic file-artifact reach adapter, and coordinator's policy. Move declaration verification, not declaration discovery by loaded Vars, to admitted facts. No compatibility flag. Classpath cache fingerprinting must cease depending on `selection/input-digests` before that namespace is deleted. |
| Selection duplicates in shell/fast | `bin/test:200–249` constructs namespace/mode selections; `:660–673` discovers tests by filenames; `:736–741` writes changed paths. `fast.clj:18,28–34` independently resolves namespace membership. | Stage 1 removes discovery and decision-making. Parse CLI arguments into a request only; `--paths` remains snapshot isolation, never a selector. Fast calls the same admission. |
| Resolution: `seon.test/resolve-test :513` | `test-loader :106`, `with-test-loader :113`, `prepare-tests! :516` reload namespaces; coordinator loads all namespaces at `runner.clj:3660–3675`; `resolve-task-vars :1119` calls `find-var`; fast resolves its own namespace Vars. | Stage 2 makes `resolve-test` accept the admitted identity, database/projection, acquisition context, and shared loader value. Delete `resolve-task-vars`' independent resolution and namespace-reload selection. Keep explicit reload as a development operation, not as how a run discovers tests. |
| Execution: `seon.test/run :306`, `run-owned :380`; report capture in `runner/run-var! :579` | `bounded-result :126` invokes one Var; `run-selected-tests :1019`, `run-tiers! :1055`, `run-request! :1077`, `run-task! :1392`, and `run! :1737` separately arrange fixtures/capture/counters. `fast.clj:36–53` uses the request path, not `seon.test/run`. | Stage 2 shares resolved-Var execution/capture; Stage 3 replaces orchestration by claims. Accrete a selected-Var collection arity at the existing execution owner so a namespace's `:once` fixture is not repeated by a loop of singleton `run` calls. Delete the competing capture/fixture bodies in the same slice; thin call adapters are fine. |
| Execution scheduling | `test-tasks :801`, `split-resolved-tasks :845`, `run-task-pool! :3159`, serial/unindexed work and confirmation orchestration `:3248–3392`, `run-parallel-stage! :3595`. Local `LinkedBlockingQueue` holds work independently per invocation. | Stage 3 deletes the local work queue/serial escape for unresolved identities and replaces admission by writer-decided claims. Retain process readiness, exact exits, explicit diagnostic confirmation, drift checking, and shutdown. Confirmation is an explicitly new observation, never automatic hidden re-execution. |
| Recording: `runner/commit-results! :2313` → `record-tx :2097` | `provenance :1979`, `prepare-failures! :1994`, `failure-replacement-tx :2059`; `record! :2378` starts/stops a destination and rejects default; persistent route `:2413–2575` records on current source through the held store. | Stage 1 supplies explicit cluster and immutable run membership to this writer. Stage 3 adds compare-and-claim and completion checks here. Remove default refusal/start-stop destination behavior, implicit current-source destination, and `some` live-store selection at `:2485`. Keep one owner-routing transport to the explicit holding JVM; never open its store from a worker. |
| Tally: `runner/render-run` (new query/render entry) | `task-summary :1112`, `red? :1043`, `sum-summaries :1047`, `summarize-task-results :3429`, `print-task-failures! :3437`, `print-final-tally! :3445`, `finish-run! :3544`; `test/check-in-process :634–711` manually counts; `feedback :830` counts again; `fast.clj:52–55` decides exit from counters. | Stage 4 deletes all independent final tallies/verdicts. `feedback` renders the same query, optionally filtered by requested identities. Capture-time counters may support Clojure's reporting protocol but never decide a durable verdict separately. |

**Contradictions with principles, not inferred causes:**

- P1/P3: `check-in-process` uses stale test rows, the coordinator uses a file
  basis, and fast uses named loaded namespaces. Identical requests can select
  different tests (`test.clj:535`, `runner.clj:2615`, `fast.clj:18`).
- P2: results replace facts on the test row (`runner.clj:2246`); mutable failure
  components are replaced/retracted at `:2059`. A historical run cannot obtain
  its old result by following today's `:seon.test/run` backlink.
- P3/E2: `seon.fn/gate-set` still adds `:seon.test/subject` and
  `pending-subject` (`src/seon/fn.clj:1195–1207`); `runner/reach-entry :1848`
  and `selection/row-edges :132` also admit subjects. Parent S1 must remove this
  reliance and supply actual call edges; Stage 1 must not copy it.
- P4: `resolve-test` uses only `requiring-resolve`; the coordinator's unresolved
  tests become serial tasks (`runner.clj:845`). Neither is identity refusal.
  The Stage 2 wording “has a file” also contradicts parent R4/C3: an agent
  override can retain a file coordinate. **Admitted provenance decides loading.**
- E1/P1: `check-in-process :544` and `check :761` choose `default` implicitly;
  `record! :2385` rejects that same cluster. Both rules must go.
- P2: `print-final-tally! :3445` does not count assertions exactly as
  `clojure.test/report :summary` does (`test.clj:399`, pass + fail + error).
- P5: existing bounded worker exchanges at `runner.clj:2776` are valuable;
  they must emit durable failure evidence, not only a coordinator summary.
  Fixture initialization and per-test execution need separate declared bounds;
  `test.clj:126` currently charges cold fixture acquisition to a test's wait.
- The PRD's “shell tally” and “static partition of namespaces” are inaccurate
  descriptions of today's source. The coordinator tallies, and `test-tasks`
  already groups namespaces with `:once`/`test-ns-hook` and queues other Vars.
  Correct these PRD descriptions when the implementation slice updates its
  authority; this note does not edit a second path in Stage 0.

Selection is over one immutable database value. `basis-t` retains its existing
meaning, the **tested database's** basis, not the previous run's basis. Find the
previous completed run on the same branch, use its tested basis as the default
change basis, then derive changed program identities and test reach. Open runs
are unfinished obligations, not a new baseline that erases pending work. A
previous red is not silently made green by an empty subsequent selection.
Explicit names, first-run eligibility, and platform membership use the same
function. Missing call facts are unknown, never evidence that no test is needed.
Use E2 call edges and recorded tested reach to handle removed definitions whose
identity remains a tombstone. Dependency/schema changes need declared graph
facts or a named incomplete-coverage refusal; removing file widening must not
silently erase its safety guarantee.

No tests were executed to measure selection. Read-only MCP, explicit default
custody, pid **53320**, gave:

| Observation | Result |
|---|---|
| First census, basis 536871253 | 1,823 tests; 151 runs |
| Later census, basis 536871262 | 1,823 tests; 158 runs |
| One run pulled by entity id 59707 (not an assertion of global latest) | id `ae677e89ce0c`, at `2026-09-16T18:30:01Z`, branch `:cluster-default`, tested basis 536871260, digest `5ea8954a991c0994ecf289eb19cd0da7ade66ee95151bcc08b441d454806e38e` |
| `tests-reaching` for `seon.test.runner/record-tx` | 0 in 7.997459 / 4.345417 / 2.107583 ms; a later pull confirmed the function entity exists |
| Positive control, basis 536871265, `seon.db/q` | 1,056 tests in 35.851542 ms |
| Admitted test provenance, same basis | 1,822 `:core`; 1 `:agent` |

These are query observations, **not** timings of the proposed selector, and a
zero reach result is not a coverage proof. Concurrent lanes explain advancing
bases only in the limited sense that this lane made no writes; no attribution
of their operations is needed. Exact repeatable query form (run only read-only):

```clojure
(let [database (seon.db/db (seon.operator/connection "default"))]
  {:basis-t (seon.db/basis-t database)
   :tests (seon.db/q '[:find (count ?e) .
                       :where [?e :seon.test/sym]] database)
   :runs (seon.db/q '[:find (count ?e) .
                      :where [?e :seon.test.run/id]] database)
   :provenance (seon.db/q '[:find ?source (count ?e)
                            :where [?e :seon.test/sym]
                            [?e :seon.schema.admission/source ?source]] database)
   :reach
   (mapv (fn [s]
           (let [identity (seon.db/pull database [:db/id :seon.fn/sym]
                                       [:seon.fn/sym s])
                 started (System/nanoTime)
                 result (seon.fn/tests-reaching database s)]
             {:identity identity
              :elapsed-ms (/ (double (- (System/nanoTime) started)) 1000000.0)
              :count (count result)}))
         ["seon.test.runner/record-tx" "seon.db/q"])
   :run (seon.db/pull database
                     [:db/id :seon.test.run/id :seon.test.run/at
                      :seon.test.run/branch :seon.test.run/tested-branch
                      :seon.test.run/basis-t :seon.test.run/program-digest]
                     [:seon.test.run/id "ae677e89ce0c"])})
```

## 2. Accretive fact model

**Keep `branch` and `tested-branch`, unchanged.** Their schema docstrings
(`seon.test.run.edn:6–7`) distinguish result destination from tested database.
Add a ref to the logical cluster; do not infer its name by parsing
`cluster-default`. The named cluster's connection determines destination.
An isolated source snapshot is not permission to write results to default for
an unrelated cluster. A mismatched program digest is refused before execution.

The following tables specify exact additions and their docstrings. `ref` below
means the existing `:seon.db/ref` declaration; no parallel reference type.
Use the existing schema compiler and `:seon.db/attributes true` entity maps.
All existing entity-schema additions are **optional**, so existing facts still
validate. New request/admission contracts require the new fields for new runs.
No existing attribute changes meaning, cardinality, or output promise.

| Attribute | Malli declaration | Docstring (`:description`) |
|---|---|---|
| `:seon.test.run/cluster` | `:seon.db/ref` | “Cluster explicitly named by this run's request; the reference targets its :seon.cluster/name identity.” |
| `:seon.test.run/change-basis-t` | `:seon.db/basis-t` | “Historical database basis against which this request selected changes, on the tested branch. Absent for the first recorded selection.” |
| `:seon.test.run/members` | `[:vector {:seon.db/component true :seon.db/cardinality :many} :seon.db/ref]` | “Immutable selected test memberships admitted for this run before execution; an empty set is a valid selection, not execution evidence.” |
| `:seon.test.run/covered-by` | `[:vector {:seon.db/cardinality :many} :seon.db/ref]` | “Members of another run accepted at admission instead of duplicate execution for this request's same cluster, program digest, and change basis. References retain the admission decision, not a count.” |
| `:seon.test.run/selection-tx` | `:seon.db/ref` | “Transaction that admitted this run's complete selection, including an empty selection; absence identifies historical runs without recorded membership.” |
| `:seon.test.run/retained-root` | `[:string {:min 1}]` | “Actual retained launcher directory supplied after cleanup decided to retain it; absent when no retained directory was reported.” |
| `:seon.test.member/id` | `[:string {:min 1 :seon.db/identity true}]` | “Identity derived by seon.id/id from run identity and selected test identity.” |
| `:seon.test.member/test` | `:seon.db/ref` | “Selected program test identity; resolves through :seon.test/sym in the run's tested database.” |
| `:seon.test.member/reasons` | `[:vector {:seon.db/cardinality :many} :seon.test.run/reason]` | “Reasons asserted by selection at admission; multiple reasons may apply to one member.” |
| `:seon.test.run/reason` | `[:enum :platform :reaches-changed :named :first-run]` | “Closed selection outcomes: declared platform membership, changed call reach, explicit request, or no prior usable recorded selection. This describes selection, not an entity kind.” |
| `:seon.test.member/worker` | `:seon.db/ref` | “Process record that owns the current claim, including an in-process executor; never a PID without its start instant.” |
| `:seon.test.member/claimed-at` | `:inst` | “Instant supplied by the claiming process and accepted by the writer for this claim.” |
| `:seon.test.member/claim-tx` | `:seon.db/ref` | “Transaction that accepted the current claim; completion must name this exact claim, so an earlier worker cannot complete a reclaimed member.” |
| `:seon.test.member/completed-tx` | `:seon.db/ref` | “Transaction that accepted this claim's terminal execution evidence; absence means execution has not completed.” |
| `:seon.test.member/reports` | `[:vector {:seon.db/component true :seon.db/cardinality :many} :seon.db/ref]` | “Immutable clojure.test reports accepted for the completed claim, ordered by their report ordinal.” |
| `:seon.test.report/id` | `[:string {:min 1 :seon.db/identity true}]` | “Identity derived by seon.id/id from member identity, claim transaction, and report ordinal.” |
| `:seon.test.report/ordinal` | `[:int {:min 0}]` | “Position in this member's report sequence, including begin/end reports.” |
| `:seon.test.report/type` | `[:enum :begin-test-var :pass :fail :error :end-test-var]` | “clojure.test report event admitted by the test-result writer; begin/end establish whether the selected test actually ran.” |
| `:seon.test.report/error` | `:seon.db/ref` | “Existing seon.error fact explaining an execution-boundary :error report, such as a missing worker response or unresolved identity.” |
| `:seon.db.process/pid` | `:seon.boot/pid` | “Operating-system PID observed when this process record was created; identify it together with start-instant.” |
| `:seon.db.process/start-instant` | `:seon.boot/start-instant` | “Operating-system start instant observed for this process record; prevents reuse of a PID being mistaken for the claimed worker.” |

The process additions extend the existing `:seon.db.process/id` record, whose
schema currently declares **only an id** (`seon.db.process.edn:1`). Reuse
`seon.cluster/process-identity` (`src/seon/cluster.clj:2414`), which already
derives that id from PID/start instant. The census consumes the existing process
identity map; it must not parse a test-specific ID string or introduce a second
process registry. A process with unavailable start instant cannot own a claim.

New entity maps: `:seon.test.member/member` requires id, test, reasons; claim
and completion/report fields are optional because unclaimed members are valid.
`:seon.test.report/report` requires id, ordinal, type. On that report entity,
reuse optional `:seon.test.failure/expected`, `actual`, `message`, `contexts`,
`reported-file`, `file`, `line`, `signature`, `throwable`, `expected-blob`,
`actual-blob`, `expected-size`, and `actual-size` with their existing definitions
and rendering semantics. Do **not** place `:seon.test.failure/id` there: that
identity means the mutable current failure site. Do **not** place
`:seon.test/sym` on a member: it is unique and would upsert the program test.
Component refs have cardinality-many set semantics; ordinal, not vector order,
preserves report order. All optional values are omitted rather than stored nil.

For the recommended platform option, add `:seon.test/platform` as
`[:string {:min 1 :description "Declared reason this test must run before other selected tests; admitted from its declaration, never inferred from its name or filename."}]`,
optional on the existing test schema. Index namespace-level declarations onto
the appropriate test facts at the existing admission seam; preserve the
declaration's provenance rather than reread namespace metadata during selection.
The declaration is a policy reason, **not** an E2 subject/coverage annotation.

At live basis **536871271**, the database-carried projection's forms contained
none of `run/members`, `run/cluster`, `run/change-basis-t`, `member/id`,
`report/id`, `test/platform`, `db.process/pid`, or `db.process/start-instant`
(each with the full namespaces above). This was a registry query before
proposing keys. The exact probe was `select-keys` on
`(:seon.schema.projection/forms (seon.db/carried-projection database))`.
An earlier attempted `seon.schema/registry` call failed to compile; it produced
no measurement and was corrected by reading the schema owner.

Keep declarations staged: Stage 1 adds run/selection/member identity fields;
Stage 3 adds process claims and immutable reports; Stage 4 replaces summary
readers. Required new-run and completed-member contracts enforce presence at
those boundaries while historical entity schemas stay open and accretive.
Report facts have a real storage cost proportional to assertion count; a pass
contributes three scalar attributes plus its component ref. This proposal does
not claim that the additional cost is measured or free. The implementation
review must check the canonical run's datom delta and retained blob behavior,
including zero changed report datoms on an identical completion replay.

**Why reports are needed.** The current run entity has no results of its own.
Referencing mutable test/failure rows loses historical results after a rerun.
Persist each observed pass/fail/error once, plus begin/end; preserve failure
payloads through the existing renderer/blob owner. A pass needs only identity,
ordinal, and type. Do not store its expected/actual value just to recover a
count. One writer may continue supplying the existing latest-test result
attributes for their existing public contract; they are a legacy projection,
never the historical run's authority. Stage 4 moves their readers to the
report query before any separate proposal to retire those stored projections.
Adding a second independent result writer would violate P1/P6.

Do **not** add run/member status, running count, pass/fail/error totals,
assertion count, green boolean, elapsed time, namespace/file group roster, or
“latest run” pointer. Derive them. Claimed process and claim instant are event
evidence; claim/completion transaction refs identify decisions, not mirrors.
`covered-by` records a time-specific admission choice that today's query cannot
reconstruct after a competing run completes. Its rendered reason is always
“already admitted for this cluster, digest, and basis”; store no redundant text.

**Writer decisions.** Selection itself is pure. Admission submits its tested
database identity and complete selection to the named connection. The writer
checks the expected program digest and change basis, finds intersecting open
members for the same request context, and atomically admits the complement plus
`covered-by` refs. This reservation happens **before** workers claim; checking
only claimed members would admit duplicates between selection and first claim.
Claiming atomically writes worker, claimed-at, and claim-tx for every member in
the chosen group. Completion checks worker **and claim-tx**, writes reports and
completed-tx together, and is idempotent for identical evidence. A changed
replay is refused, preserving `record-tx`'s immutable-run/delta behavior.

Reclaim only after an exact process exit is observed by the process owner or a
census establishes that PID/start-instant is dead. “Unknown” is not dead.
Accepted death evidence cannot become stale by resurrection of that process;
do not use a timeout as death evidence. Losing a claim must not execute its
body. Death after effects but before completion permits a later rerun: the
guarantee is **no concurrent accepted claim**, not exactly-once external effects.
The Stage 3 regression must state this boundary and reject a late completion.

Tally: count `:begin-test-var` reports as tests; count `:pass`, `:fail`, and
`:error` as Clojure does. Preserve the existing assertionless-test failure.
Fixture exceptions become named :error evidence; do not fabricate that every
selected body ran. A member lacking a terminal event/result is incomplete,
even with zero failures. A platform failure leaves later selected members
unexecuted and the run red, with that reason derived from tier policy.
An admitted empty selection renders “no tests selected”; it cannot manufacture
a new green verification of the cluster. A run covered by another unfinished
run is pending, not green. Historical runs without selection-tx render their
membership/result evidence as unavailable, not zero tests passed.

## 3. One classpath derivation and identity resolution

`deps.edn:135–146` declares `:test` with extra paths `test`, `script`, and `.`,
plus `org.clojure/tools.build` 0.10.5 and the local
`org.clojure/core.async.flow-monitor` dependency. `test.clj:106–111` currently
adds **only paths** to a DynamicClassLoader. `dev_cache.clj:466–491` already
uses tools.build's `create-basis {:project "deps.edn" :aliases [:test]}` and
its ordered `:classpath-roots`. Reuse this existing derivation; do not maintain
another list of jars or special-case `seon.test-runner-test`.

Make `dev-cache`'s existing derivation return an explicit data value containing
the resolved basis and ordered roots for the selected source snapshot, then
hand it to both hosts. Run the derivation in its existing tools.build tool
environment, where that dependency is already available; requiring dev-cache
inside the broken loader to obtain tools.build would be circular. Boot/adoption
hands the resulting value to the in-process loader. The worker launcher uses
the same roots, rebased to its immutable checkout, and the test alias's JVM
options. The in-process loader installs the same resolved roots and preserves
the existing parent-first Clojure/SCI/class identities; it does not reload
protocol owners or replace already-loaded classes with another version.
If a required class version cannot coexist in that JVM, refuse it explicitly
and use the isolated worker, rather than claim URL addition replaced a class.

The existing fingerprint at `dev_cache.clj:459–464` calls
`selection/input-digests`. Move its dependency/input fingerprint responsibility
into the existing cache owner in Stage 1 before deleting selection.clj; it may
still fingerprint cache inputs. P3 forbids using those paths to select tests,
not correctly identifying a classpath artifact. Stage 2 removes the independent
`test-loader` dependency derivation and `bin/test:633–638`'s legacy classpath
fallback for snapshots predating the protocol. An unsupported snapshot gets a
typed version/classpath refusal; no parallel implementation is maintained.

Resolution contract: find `[:seon.test/sym s]` in the **supplied database**,
inspect admitted per-identity provenance, then resolve its JVM Var or acquire
the cluster's SCI base through `seon.sci.eval` and take its SCI Var. Validate
`:test` metadata and identity before executing. A file coordinate is diagnostic
only. Reuse S1/S3 acquisition for agent-authored/overridden definitions; do not
`eval-string` into a shared context on every test resolution. Acquisition's
unloadable override is a typed result under parent C3, not silent JVM fallback.
Stage 2 therefore needs the parent S3 acquisition guarantee as well as S1's
indexed facts, or an explicit proof that S3 is already available.

This closes the known
[in-process loader issue](../../../seon/issues/the-in-process-test-loader-cannot-load-a-namespace-needing-a-test-alias-dependency.md).
Acceptance remains the PRD's real loader regression plus a fileless agent test
under armed contracts and canonical fixtures. This review did not require,
reload, acquire a fixture, or attempt either regression.

## 4. Exact typed refusals and first-run outcome

Use `seon.error/diagnostic`, existing `:seon.error/value`, and flat error
values. The names below are proposed exact `:seon.error/kind` values, not thrown
exceptions, empty vectors, or prose-only shell exits. Each diagnostic supplies
layer `:test`, operation, member, expected, offending, and evidence; no nil
fields. They use the existing `:seon.error/value` contract and its kind keyword,
not new entity types or unspecified per-refusal storage attributes.

| Condition | Exact outcome and evidence |
|---|---|
| No cluster named and no agent custody supplies one | `:seon.test/cluster-required`; operation `:select`; member `:seon.cluster/name`; expected `:explicit-cluster`; offending `:seon.error/unknown`; message “Name the cluster whose tests and results this request owns.” No fallback to default/current-src. |
| Named cluster unavailable or not the handed database's cluster | `:seon.test/cluster-unavailable` or `:seon.test/cluster-mismatch`; include requested name and observed branch/cluster identity, expected named connection. No lifecycle action follows a refusal. |
| No prior recorded selection on that cluster | **Successful first-run selection**, all eligible members have `:first-run` (and any additional applicable reason). Message “First run on CLUSTER: full eligible set.” Omit change-basis-t. This is not an execution refusal; returning an empty success would violate P3. A historical run without membership evidence is explicitly reported as unusable history and conservatively takes the same first usable selection path. |
| Test identity absent, tombstoned without runnable definition, or admitted loading produces no test Var | `:seon.test/identity-unresolved`; operation `:resolve-test`; member `[:seon.test/sym s]`; expected `:runnable-test-var`; evidence includes run, tested branch/basis, provenance if available, and resolution phase. Existing `:seon.test/not-runnable` remains the specific diagnostic for a Var present without a test function; it is not replaced by an empty result. |
| Another process wins claim | `:seon.test/claim-conflict`; operation `:claim-member`; member id; expected unclaimed or confirmed-dead owner; evidence names actual worker process identity and claim-tx. Execute nothing. The worker asks the same writer for another available group, with the original deadline, not an unbounded retry. Exhaustion is a distinct successful “no available members” query, never a conflict disguised as success. |
| Earlier worker completes after reclaim | `:seon.test/claim-replaced`; operation `:complete-member`; include submitted/current worker and claim-tx. Accept no report from that completion. |
| Selection does not describe the named cluster's admitted program | `:seon.test/program-mismatch`; expected and supplied digests, branch/basis, run id. Snapshot creation alone cannot authorize this mismatch. |
| Worker response/completion misses declared bound | Retain the existing worker-exchange error class and evidence (`runner.clj:2776`), record it as the member's :error report via the same writer, with expected event, worker process, deadline, and diagnostic paths. If the authority itself is unavailable, exit nonzero as unavailable and retain diagnostics; never print a recorded red that was not committed. |

Refusal evidence belongs to the existing error schema, not a new state enum.
First-run reason, empty selection, unavailable execution, claim conflict, and
red assertions are different outcomes and must render differently. An explicit
namespace resolving to no test identities names that namespace in a typed
selection refusal. An unnamed first run with no cluster refuses before it can
be confused with an empty database.

## 5. What each part of bin/test becomes

Line references are to the completely read **807-line** script. This covers all
regions, including blank/comment-only lines. Retained operations remain process
launch responsibilities; all test selection and result formatting move to the
owners above. Human-readable test output is exactly `render-run` output.

| Current lines | Result and stage |
|---|---|
| 1–26 | Rewrite usage/comments in Stage 1: explicit `--cluster`, no implicit current-src result target, no default refusal, no “green basis” file claim. |
| 27–43 | Keep frozen launcher bytes, checkout/cwd, Java selection and strict shell execution. These establish which launcher runs. |
| 44–62 | Usage generated from the request contract or a single minimal synopsis; delete shell test-policy prose. |
| 63–78 | Retain repository-link validation as launch preflight; no test selection. Its failure is a launcher error, not a fabricated test run. |
| 79–105 | Keep argument storage only; delete `set_mode` policy and `SEON_TEST_FULL` decision. Supply flags to shared request validation. |
| 106–198 | Retain syntax parsing and snapshot path validation. Replace result-cluster/result-root semantics with explicit cluster/root request. Delete `--changed PATH` selection in Stage 1; named program identities replace it. `--paths` only determines checkout bytes. |
| 199–221 | Delete confirmation symbol-to-namespace derivation and sorting in Stage 1; pass requested identities to select. Confirmation is an explicit run request. |
| 222–250 | Delete independent fast/mode/default/namespace policy in Stage 1. No namespace or tier count is computed here. |
| 251–265 | Delete default-target rejection and implicit persistent destination. Keep obtaining git SHA as snapshot provenance, not selection authority. |
| 266–349 | Retain run-root creation and bounded retained-root cleanup under exact process ownership. Its actual retained directory is supplied to the run; no summary assembled here. |
| 350–435 | Retain lifecycle log/exit cleanup, process launch and awaited completion. Delete selection-description fields/text; raw process diagnostics remain diagnostics. |
| 436–474 | Retain `bin/_test-slot`, signal traps and child cleanup. Signals cannot mark unfinished members passed. Slot count remains a process bound, not a claim mechanism. |
| 475–482 | Remove formatted test phase reporting in Stage 4. Retain timestamps as launcher diagnostics if needed; the test renderer owns human summaries. |
| 483–518 | Retain isolated checkout materialization and reference-code/cache links. |
| 519–570 | Retain HEAD-plus-explicit-paths snapshot exactly. Remove formatted diff/test-selection narration; retain snapshot identity and diagnostics. No path list enters select. |
| 571–575 | Retain bounded slot acquisition. |
| 576–585 | Delete fast's separate execution route by Stage 3; if the command name remains, it passes an in-process execution request to the same owners. It cannot mean an unrecorded second gate. |
| 586–617 | Retain dependency cache preparation and launcher failure handling. |
| 618–641 | Consume the shared classpath derivation from §3. Remove legacy fallback by Stage 2; no extra hand-built classpath. |
| 642–659 | Retain snapshot/cache linking. |
| 660–674 | Delete `find`/filename-to-namespace discovery in Stage 1. |
| 675–701 | Retain worker-count bound, but obtain runnable-group count from admitted facts; delete namespace-array-based capping and selection formatting. Worker policy is declared once, not recomputed differently in runner. |
| 702–719 | Retain only checkouts/roots needed for admitted workers. Zero runnable members needs zero worker JVMs. |
| 720–742 | Delete destination inference, “SELECT” text, and changed-paths file. Carry explicit authority/root, cluster and run id. |
| 743–767 | Retain canonical base publication/reuse for the exact execution snapshot. Admission must first verify it corresponds to the named cluster's program; a current-source base is not automatically another cluster's program. |
| 768–788 | Launch bounded workers with run id and worker index as work arguments. Supply authority endpoint/root, classpath and snapshot as their environment values; do not pass namespaces, paths, selection mode or counts as a second work list. |
| 789–807 | Await and reap all owned processes, query the run, print exactly its renderer, exit from its derived verdict, release slot, retain/delete own root. Remove elapsed/final-tally formatting and any exit based solely on coordinator counters. Unavailable query means nonzero/unavailable, never success. |

The existing `bin/_test-slot:1–77` stays a bounded launcher admission primitive.
Its two-per-checkout default cannot prevent two launchers from selecting the
same tests; the database writer does that. Preserve per-exchange bound, watchdog,
thread dumps including virtual threads, exact exit observation and shutdown
(`runner.clj:371–577,2715–3037`). They report into the same recorded run when
the authority is available. There is no stored run verdict: “exit from the run
entity's verdict” means exit from its query, per P2, not a second boolean.

## 6. Owner choices, costs, and verification boundary

### Claim granularity

Historical **measured fixture costs**, not new executions in this stage:
[turn-bookkeeping-cost](../../context-generation/research/turn-bookkeeping-cost-2026-09-16.md)
at line 94 records a warm canonical `with-database` branch at **1 ms**, branch plus
`seed-cluster!` at **2,354 / 2,341 ms**, and empty `with-cluster` at
**5,017 / 4,851 / 5,050 ms**. These are dated pre-repair costs, not current
throughput predictions. The same note's `:319` records **32,756.598 ms** for a
cold-base attempt that **failed** due to the classpath gap; that is not a
successful fixture-load measurement. The supplied REPL rule estimates about
**30 s** per publication's first canonical base construction; it is an estimate.
[test-suite-cost-plan](../../context-generation/research/test-suite-cost-plan-2026-09-15.md)
at line 23 measures fresh-worker startup at **11.189–16.179 s**, and cold publication at
**41,508 / 118,399 / 44,011 ms** versus reused **3 ms**. Those are startup and
publication, not fixture-body splits. No current cold namespace-fixture-only
measurement is available under this stage's no-tests constraint.

| Simplest first | Guarantee | Cost and what we give up |
|---|---|---|
| **1. Per namespace — recommended** | Claim all selected members of a namespace atomically; call `clojure.test/test-vars` once for that selected set. One `:once` fixture per group, `:each` remains per test. `test-ns-hook` retains the existing full-namespace requirement. | Smallest model: grouping derives from admitted namespace refs; no group entity. One slow namespace limits parallelism. Body-local `with-database` still runs per body, so do not multiply the historical 1 ms branch cost into an invented namespace saving. For a genuine once fixture of cost F and n selected Vars, this saves `(n-1)*F` over singleton execution. |
| 2. Per test | Finest balance for namespaces without once fixtures/hooks. Claims still atomic at member level. | Existing `run-var!` calls `test-vars [v]`, so naïve use repeats once fixtures n times and can change semantics. Viable only with the existing derived namespace exception for once fixtures/hooks; this adds two claim shapes. Gives up uniform granularity. Per-body setup is unchanged; persistent workers avoid paying 11–16 s startup per test. |
| 3. Per file | Keeps selected core tests from one source file together. | Fileless agent tests and overrides require a namespace/test fallback, and fixture scope remains namespace, not file. Adds coordinate grouping plus exception rules with no measured saving over namespace groups; gives up one uniform identity-based policy. P4 rules it out as the universal unit. |

Stage 3's first integration measurement should time actual fixture entry/exit
and each body separately through the canonical fixture, plus worker startup,
on the accepted snapshot. Do not tune claims using enclosing END durations.

### Non-default results and the global answer

| Simplest first | Guarantee | Cost and what we give up |
|---|---|---|
| **1. Query across explicitly selected cluster branches — recommended** | Results stay where E1 says they belong. Return cluster/store/branch with each answer and compare only compatible tested digests/bases. | No extra write or reconciliation. Query costs scale with selected branches; unavailable branches remain named unknowns. Gives up constant-time “global latest” from one pointer. |
| 2. Also write a pointer on current-src | One branch can list references to non-default run identities. | Additional cross-branch write cannot be atomic with the result transaction; needs missing-pointer detection and repair. Gives up one authoritative write. A bare pointer is a derivable mirror and conflicts with P2 unless the owner explicitly changes the requirement. |
| 3. Copy full run/results to current-src | A single-branch global query can read everything. | Maximum write/storage and provenance complexity, duplicate authority and failure handling. Gives up E1/P1/P6; reject under the current principles. |

“Global last green” must name its scope and tested program. Sorting independent
branch `:t` values does not establish a common program baseline. No pointer can
repair that semantic ambiguity.

### Platform tier

| Simplest first | Guarantee | Cost and what we give up |
|---|---|---|
| **1. Keep the declared reason as a fact — recommended** | Explicit policy survives program changes and agent-defined tests. Platform-first is selection/scheduling policy in one owner. | One indexed declaration, queried before execution. Reviewer must maintain its intent; gives up automatic discovery of every infrastructure-reaching test. No maintained list. |
| 2. Derive tests reaching boot and cluster functions | Membership follows actual admitted calls. | Must first declare which function identities define this policy; otherwise it hides a roster. Transitive reach may select a much larger tier. Gives up the distinction between policy-critical and incidental infrastructure reach. |
| 3. Eliminate a platform tier; use only changed reach/explicit requests | One minimal selection with no special order. | Gives up the PRD's platform-first regression guarantee and AGENTS §5 until explicitly re-ruled. Simplifies policy but is incompatible with current acceptance. |

### Additional decision: what isolated worker custody means

This is a real cross-owner decision, not permission to implement a second
database API. `start-worker!` creates isolated operator roots
(`runner.clj:2885`); ordinary Datahike connections are JVM objects. The root's
store has one lifetime lock. Passing `(run-id, worker-index)` does not make
the original named connection exist in the child. Today body custody and result
destination are expressly separate (`test.clj:306`, commit `453f08b5d`).

| Simplest viable constraint first | Guarantee | Cost and what we give up |
|---|---|---|
| **1. Immutable named-cluster snapshot in isolated workers — recommended, requires owner acceptance** | Claims and result recording occur in the original cluster's holding JVM through its existing bounded operator transport; workers acquire the exact named cluster program/database snapshot in isolated stores and execute under that local connection. Program/branch/store provenance is explicit. | Reuses present snapshot/base machinery and keeps real isolated execution. Gives up observing concurrent live-cluster writes or applying a worker body's mutations to the original live cluster. This is valid only if E1's “ran on” accepts an explicitly recorded isolated snapshot; do not claim literal live custody. Result destination and tested-branch keep their documented meanings. |
| 2. Shared named-cluster execution in the hosting JVM | Every test body sees the actual connection; existing `run-owned` semantics hold literally. Launchers submit bounded requests and claims still coordinate them. | Gives up separate-JVM isolation for those tests and needs a separate explicitly named isolated cluster for destructive cases. It does not satisfy the target that all worker JVMs themselves execute the tests without an owner-approved constraint. |
| 3. Isolated computation with remote database operations | Workers execute code but read/write the original named cluster through its holding process. | Requires a deliberately designed remote custody contract for reads, transactions, temporal database values, listeners and callbacks, not a serializable Datahike connection. Largest cross-owner cost; gives up the existing in-process connection semantics and cannot be assumed inside the estimated Stage 3 slice. No such protocol is proposed for implementation here. |

No option can promise both arbitrary literal live-connection semantics and
unchanged isolated execution for free. Owner acceptance of option 1 should name
which agent-owned tests must instead stay in-process. The same selection,
resolution, execution and recording functions still apply to both; host choice
does not introduce a second policy. Stage 1 can proceed after its own review;
Stage 3 must not begin until this custody decision is settled.

**Verification/landing boundary.** This note is the only owned path. All
pre-existing edits in program/SCI/turn/issue/db/fn owners, their schemas/tests,
issue index, and other research files were preserved. No foreign session was
resumed, messaged, edited or operated; no worktree or scratch root was created.
All shell invocations completed. No tests, transactions, fixture acquisition,
reloads, restart, publication, schema edit, or production edit was performed.

`runtime_status` found pid 53320 alive but reported component health/flow
**unknown: “Read timed out”**; this was reported immediately and matches the
existing open
[default component-probe issue](../../../seon/issues/default-component-probe-times-out-after-adoption.md).
The successful read-only JVM queries above do not certify component health or
development adoption freshness. This is the exact live verification boundary,
not a reason to stop the source review. An oversized source search hit a retained
raw dump; no conclusions use its truncated output. Subsequent evidence reads
were restricted to named files. Proposed changes remain unexecuted design;
acceptance is the orchestrator's review and the owner's answers, not a green
gate. No RESET NEEDED claim is made because no schema was changed.

Documentation checks: the canonical Markdown validator was run directly in
Babashka with `script:src:resources`; the initial unused-tag warning was removed.
`git diff --check` is clean. The edit hook separately reports existing repository
dependency-pin errors in
`docs/prds/context-generation/research/agents-md-audit-2026-09-15.md`; this lane
did not edit that foreign authority or treat the repository-wide lint as green.
