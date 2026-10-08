---
title: Agents changing the platform from their REPL — independent Astra design
type: research
status: proposed
created: 2026-10-08
---

# Agents changing the platform from their REPL

Seon already has branch-local program entities, SCI forks, an incremental test owner, guarded merge and write-back,
and an operator that boots a committed archive for isolated checks. Compose those owners: ordinary changes run in
the agent's fork; changes requiring compiled execution become a root-owned test request whose results return to the
same task. Keep the main JVM's evaluator, writer and runner stable while they supervise the change.

**Recommendation:** root should own the exceptional execution, not take over writing the agent's fix. First make
proof honest about which definitions actually executed, expose the existing fixtures at the agent REPL, and let an
agent request compiled proof before it qualifies for merge. Use a disposable JVM initially. A bounded, reusable
development JVM is a later measured optimization, not one JVM per agent.

This is a design proposal, not an implementation or an installation claim. I did not read the independent Opus note.
Only this file is owned by lane `hostbound-astra`; no production code, task resource, schedule or skill was edited.

## 1. What I verified

Authorities read: AGENTS.md in full; the current
[owner rulings](../review/owner-rulings.md); [agent-loop](../../../../seon/specs/agent-loop.md) and
[merge](../../../../seon/specs/merge.md); the [pilot](../dogfood/pilot.md),
[REPL-first research](../repl-first/repl-first.md), RF-1 through RF-6 under docs/seon/schedule/cards, and
[PB-03a](../model-pass/pb-03a-notes.md). I also read the testing, REPL, data-oriented Clojure, Datahike and Flow
skills, all of test/seon/test_support.clj, the relevant callers and dependency source, and the Git history of those
owners. The last two skills were used for branch and process guarantees, not to design another store or scheduler.

### Live evidence, read-only forms

Default was running archive `6a244149dee2708b47b06b1d65e7a3bbaef79450`, PID 36950, start instant
2026-10-08T07:55:16.577Z. Initial Git HEAD was `8bfd1c3aa`; the working tree contained other lanes' changes to
the evaluator, tests, schemas, context, operator clients and bin/lane. Installed claims below come from the archive
or MCP, not those uncommitted edits. No source was loaded or redefined by this research.

`bin/seon status` and MCP `runtime_status` both reported **degraded**, not healthy: 26 errored receipts, 10
over-bound warnings and one unowned namespace; live heap 1,467,488,384 bytes, maximum 4,294,967,296 bytes;
boot readiness 38,157 ms. The over-bound owners included `seon.batch/evaluate-batch`, `seon.sci.batch/around`,
`seon.await/await-port-operations`, `seon.fn/analyzed-files` and `seon.test/run`. These are baseline observations,
not costs attributed to my reads. Existing cost rows include PB/RP and the one-test cost task in
resources/seon/tasks/seon.test.edn. This lane does not authorize a restart or a long performance experiment.

All MCP reads used agent `hostbound-astra`, cluster `default`; SCI unless explicitly marked JVM. Reply timings
are evaluation timings, not end-to-end transport or recording measurements. Every reply had `isError false`.

| Probe | Actual answer | Reply ms / calls |
|---|---|---|
| `(seon.context/current-state)` | No assignment; turns-left plus the evaluation's connection, database, caps, context and render inputs, rather than only the block | 2 / 732 |
| Public/resolve checks below | Support: zero SCI public Vars and unresolved with-database; write-branch present; merge! absent; prepare-merge! present | 17 / 49 |
| Count functions by host-bound fact, below | 85 true, 5,615 false | 17 / 98, including reading the host-bound names |
| JVM inspection below | Support loaded with 53 public Vars; task source is in the running archive; no task AOT class resource; all 2,509 stored tests have core admission | 70 / not reported |
| Selected stored rows and resolution, below | Evaluator and Flow launcher host-bound; writer and runner not host-bound; support indexed but unresolved | 16 / 439 |

The current-state result is `result/e711baaeb01de`; the support/entrance result is `result/e79cef9904f59`;
the corrected census is `result/e30e10eb6591d`; JVM inspection is `result/e53d9c920ba66`; selected rows are
`result/e72e56cc48204`. Saved forms and answers here survive loss of those live handles.

```clojure
;; Public/resolve checks (the original request also contained the discarded census below).
{:test-support-publics (count (ns-publics 'seon.test-support))
 :test-support-with-database (boolean (resolve 'seon.test-support/with-database))
 :write-branch (boolean (resolve 'my.program/write-branch!))
 :merge (boolean (resolve 'my.program/merge!))
 :prepare-merge (boolean (resolve 'my.program/prepare-merge!))}

;; Count entities, not distinct booleans.
(seon.db/q '[:find ?b (count ?e)
             :where [?e :seon.fn/sym] [?e :seon.fn/host-bound? ?b]]
           (seon.db/db))
;; => [[false 5615] [true 85]]

;; JVM mode, explicit connection; does not require or reload any namespace.
{:loaded-program @seon.sci.eval/loaded-identity
 :test-support-loaded? (boolean (find-ns 'seon.test-support))
 :test-support-host-publics
 (when-let [n (find-ns 'seon.test-support)] (count (ns-publics n)))
 :classpath
 {:task-source (some-> (clojure.java.io/resource "seon/task.clj") str)
  :task-class (some-> (clojure.java.io/resource "seon/task__init.class") str)}
 :test-provenance
 (seon.db/q '[:find ?a (count ?e)
              :where [?e :seon.test/sym] [?e :seon.schema.admission/source ?a]]
            @(seon.cluster.boot/connection "default"))}
;; loaded program commit: 6ac74c90-5af6-5716-81c2-fdcfe5e2e65b

{:functions
 (mapv (fn [s]
         (let [r (seon.db/pull (seon.db/db)
                             [:seon.fn/sym :seon.fn/host-bound? :seon.schema.admission/source]
                             [:seon.fn/sym s])]
           [s (:seon.fn/host-bound? r) (:seon.schema.admission/source r)
            (boolean (resolve s))]))
       '[seon.sci.eval/evaluate seon.db/transact! seon.test/run
         seon.cluster.boot/boot! seon.test-support/with-database
         seon.test-support/read-operations seon.flow/var-process
         seon.cluster.agent/->CountedSlidingBuffer])
 :support-rows
 (count (seon.db/q '[:find [?s ...]
                    :where [?e :seon.fn/sym ?s] [?e :seon.fn/ns ?n]
                           [?n :seon.ns/name seon.test-support]] (seon.db/db)))}
;; support-rows: 67
;; evaluate: true, :core, resolves
;; transact! and run: false, :core, resolve
;; with-database and read-operations: false, :core, do not resolve
;; var-process and ->CountedSlidingBuffer: true, :core, resolve
;; seon.cluster.boot/boot! was an unsuccessful lookup, not an installed API.
```

An initial `(frequencies (seon.db/q '[:find [?b ...] ...] ...))` returned `{true 1, false 1}` because Datalog
projects distinct values. I discarded it and used the entity aggregate above. Likewise, the 86-function count in
RF-3 is a prior census, not a fixed property. I did not invoke reload to test PB-03: the current task class resource
is absent, so this JVM cannot establish whether the program-class configuration still has that defect.

### Dependency seams and cost

Pins below are Git gitlinks read during this lane. They describe inspected source, not proof that every symbol is
exposed in the agent's SCI context. The SCI and Datahike additions are maintained-fork seams; do not call them
upstream guarantees merely because their namespaces are unchanged.

| Dependency and pin | Seam and guarantee | Inputs, recomputation and cost |
|---|---|---|
| SCI `010c86df4668b57bc16e50bce7a5a3f93e4d01ff` | `sci.core/fork`, `sci.core/bind-root!`; isolated interpreted Var changes. `sci.core/copy-var*` copies the host root value | Fork the held base; reinterpret only changed functions and affected interpreted callers. Does not fork JVM classes, host Vars or captured objects |
| Clojure `3bc2b3e91fdff462620d84bbdfd40379d9c562e5` | `clojure.core/require`; `clojure.lang.RT/load` selects class/source by resource availability and modification time | Reload pays for namespace initialization and compilation as applicable; it changes process-wide definitions |
| clj-reload `61c6fa77855efc0b4d052f06c7cc8b2b8817c4aa` | `clj-reload.core/reload` loads source via its namespace loader, tracks dependencies and reports loaded/unloaded/failed namespaces | Files and dependency graph; changed namespaces and dependents. Does not create an isolated namespace universe or restore side effects |
| core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051` | `clojure.core.async.flow/process` accepts a step function, recommends a Var for hot reload; `clojure.core.async.flow/stop` is control, not proof of exit | Step calls follow messages; topology is constructed at start. Keep explicit completion/actual exit ownership |
| Datahike `86c3301a00de8c49b2690083b2b1e69677bf0c8d` | `datahike.versioning/branch!`, `datahike.versioning/merge!`; shared immutable roots and a writer-serialized merge with supplied tx-data and expected basis | Branch metadata, changed datoms and required index work. A branch isolates database writes, not the JVM. `datahike.versioning/fork-database` copies between stores; it is a different, larger operation |
| Malli `418df4417827b66846b799b5e87267a344dffc29` | `malli.core/validator`, `malli.core/explain`, `malli.core/-instrument` | Validate request/report at their owner; compile by schema content, not once per polling read |

The upstream [SCI documentation](https://github.com/babashka/sci) and
[clj-reload documentation](https://github.com/tonsky/clj-reload) were also consulted. The local pins, including SCI's
factory hooks and fork behavior, are the authority for this design.

## 2. Which code the branch can actually change

There are three different questions: can SCI evaluate the declaration; can this call reach its branch version;
and does the test exercise the real host integration? A boolean about the first cannot answer the other two.

| Case | Installed boundary | Smallest sound approach |
|---|---|---|
| Ordinary function and callers entered through SCI | Branch overrides and affected callers can interpret stored source | Keep the existing acquisition owner; include the test body and its helpers when needed |
| Unchanged function copied from a host Var | A callable host function object still calls host Vars/closures, not SCI's replacement table | Interpret the affected caller chain up to a controlled test entrance; do not rewrite JVM Var roots |
| Changed host-bound declaration | `seon.sci.eval/install-row!` refuses the changed declaration; an unchanged host-bound caller may keep its compiled body | Store a proposed edit without claiming it executed; compile and test in the isolated request |
| Evaluator, writer, runner or boot invoked by the supervising system | The running compiled stack does not turn into the agent's SCI fork because its database connection changed | Unit-test interpretable decisions on the branch; prove actual machinery in another JVM |
| Java interop with existing classes | Class use alone is not host-bound; configured SCI permits classes on the classpath | Use the existing interop seam. A missing namespace import is an acquisition defect, not evidence that all interop requires a JVM |
| New/changed JVM classes, classpath, protocol implementations | SCI's representation is not a replacement for already loaded JVM classes or their dispatch tables | Compile in another JVM; restart for activation when existing objects/class identity cannot safely survive |
| Host macros such as core.async's go | A compiler macro may expect JVM compiler locals in &env | Use the library's supported SCI integration if one exists; otherwise retain compiled construction and test it in the child |
| Flow step logic | A pure step can be interpreted; installed `seon.flow/var-process` requires a host Var, and the live graph holds that Var | Test the step as data → data in SCI; test launcher, actual dispatch and graph lifecycle in isolation. Rebuild for topology changes |

The installed classifier, `seon.fn/host-bound-callers` and `seon.fn/host-bound-declaration?`, recognizes selected
forms (reify, proxy, definterface, import, defining types/records) and macros from non-interpreted namespaces. It
does not propagate host-bound status through every caller. Missing classification is treated as unknown by
`seon.sci.eval/host-bound-row?`. This is a conservative installation decision, not a complete theorem of SCI's
language. In particular, SCI supports interpreted protocols/records: the existing
`seon.sci.eval-test/letfn-and-an-inline-protocol-record-evaluate-in-an-agent-fork` tests one. On the JVM,
`sci.impl.reify/reify*` and `sci.impl.proxy/proxy*` need configured factories; the inspected Seon base does not
configure them. Resolving arbitrary classes does not provide those factories.

**Interpreting more is useful, but not a replacement compiler.** LR-STALE2,
`seon.sci.eval/reinterpret-inherited-callers!`, repairs agent-authored inherited callers inside the fork. Its
`seon.sci.eval/inherited-callers` deliberately filters on agent-authored metadata. Extend the acquisition/test
owner where a first-party body and its caller chain can be interpreted with the same inputs and effects. Do not
teach a second evaluator or recursively interpret the whole platform merely because the reverse graph reaches it.

The sound stopping points are concrete: a compiled caller you cannot redirect; a JVM class/protocol identity the
subject requires; a macro whose expansion depends on the host compiler; an already-created closure/listener/graph;
or process-global state that a database branch does not isolate. A direct call to an interpreted writer helper is
useful unit evidence, but does not prove that the real compiled writer called it. The same distinction applies to
testing the evaluator with the evaluator under test: the stable supervisor remains outside the changed path.

Do not “fix” this with with-redefs, restoring Var roots after a test, a thread-local map consulted by every host
call, namespace renaming, or a custom classloader universe. They either leak across concurrent agents or recreate
an execution system whose semantics and lifecycle Seon would have to own.

### What RF-3 must prove

RF-3's execution evidence is the right direction, but “every changed digest appeared in some green test” is
insufficient alone. One test could enter the new body while another relevant test quietly passes through the old
host body. Also, deletions, macro expansions, schemas and namespace bindings do not have an ordinary body to count.

Keep two facts on the existing test result:

1. Its tested inputs: test source, namespace/aliases, transitive definitions and macro expansions, contracts,
   schemas, fixture inputs, loaded host program and dependency/classpath identity where relevant.
2. Its observations: actual executed callable identity/digest, and the implementation it used (interpreted or
   compiled). Record the digest at installation/compilation of that callable; never look up the current branch
   digest at the call and stamp it onto an old host body. Preserve observations through asynchronous work the test owns.

The run is current only when its observed code is compatible with its tested inputs. An observed old compiled
version of a changed declaration makes that test unable to prove the branch, even if another test reached the new
version. Unknown observations are unknown. Static dependency/input evidence remains necessary for unexecuted
paths and for test selection; call coverage cannot replace it. Deletion is proved by absence/integrity and surviving
callers, a macro by its expansion users, a schema by registration and the relevant values. No blanket body-hit rule
for those changes. Fix stale evidence at the runner and recording owner before RF-2 accepts it.

This also reveals a larger present gap: `seon.test/resolve-test` chooses SCI for agent admission and a host Var for
core admission. All 2,509 tests in the live census are core. Some host tests explicitly enter the agent path and
can prove branch behavior; ordinary compiled calls in a host test cannot. The test's file origin must not decide
whether its body sees the changed program. Interpret affected compatible test bodies/helpers in the member's
fork, or name the compiled requirement and use isolated execution. Both use `seon.test/run` and the same reports.

## 3. What an agent does for the compiled remainder

### Alternatives

| Option | Guarantee and agent experience | Cost and root review |
|---|---|---|
| **Root-owned disposable check, recommended first** | Agent records branch edits and requests proof at its REPL. Root materializes exactly those inputs in a private archive, executes the existing test path in a child JVM, returns reports to the task. Main's host definitions stay stable | Existing pilot: 207 s including 126 s waiting, 45 s boot, 18 s tests, 6 s lint. Other REPL-first checks: 2–4 min. Root reviews exact source/target/input identities, reports and upgrade evidence; failure goes back to the author |
| One hot-reload JVM per agent | Agent can compile and interact repeatedly without affecting main. Class/boot changes still need fresh starts; each JVM has mutable history | Startup paid per agent, then namespace reload cost; N heaps, stores, endpoints and process lifetimes. Default already retained about 1.47 GB. With a 4 GB cap per process this is a poor default for many agents on 16 GB. Root still needs a fresh exact-input check before acceptance |
| clj-reload in main on a disposable database branch | No isolation of compiled definitions. Reload removes/reloads global namespaces, changes classes/dispatch and may run top-level effects; the branch does not make rollback possible | Cheap reload does not establish a valid guarantee. Reject for proving unmerged changes. Reserve reload in main for root activating an accepted program |
| One bounded reusable development JVM, leased to a request | Repeated platform experiments use the same independent process through the ordinary REPL. No simultaneous incompatible programs within it | Amortizes boot; must dispose after classpath changes, failed reload or non-resettable global effects. Retained heap and warm iteration benefit must be measured. Keep fresh boot/upgrade checks for acceptance; use existing operator ownership rather than a second scheduler |
| More SCI coverage with explicit inputs | Fast isolated tests of ordinary platform decisions, including pure Flow transforms; no global replacement | Best first optimization where it removes unnecessary host coupling. Does not prove compiler, startup, writer thread or live graph integration |

PB-03a measured source reload at 30 ms for my.task, 354 ms for seon.id, 406 ms for seon.task and 1,613 ms for
seon.fn, with 7–175 MB transient allocation. Those are prior isolated measurements, not a promise that a warm
development JVM will have that complete loop cost. A two-slot limit is an installed configuration value, not an
architectural truth; retain bounded process admission, measure memory, and reduce unnecessary check work before
raising concurrency. “Root does it” changes custody and delivery, not how long the computation takes.

### One test request, with root owning process execution

The following is a proposed extension of the existing request/runner/operator owners, not an installed API:

1. **Author on the branch.** An ordinary defn remains ordinary evaluation. For syntax SCI cannot execute, the REPL
   accepts a declared edit request carrying exact source/form, namespace/resolver context and expected base digest.
   Analyze through the existing file/declaration owner and store the proposed source as data. Mark execution
   evidence absent; do not claim the unsuccessful SCI form defined a working function. The child JVM supplies the
   host read/eval stage through the one path and produces the compiled proof. Do not fall back to the old body.
2. **Request proof before merge readiness.** The request refers to the source branch/commit, task, exact tests,
   source content and required host inputs. The request is durable at the task home before the agent receives it.
   The agent continues ordinary work or waits. It does not run a shell self-edit command or block an eval request
   for minutes. An ordinary function change continues to use the synchronous incremental step.
3. **Root derives the work from facts.** Root's ordinary task path starts the existing operator check; the existing
   process owner retains `(pid, start-instant)`, root and exit ownership. A wake is only notification. Root need not
   make a provider call to launch or collect a declared test request. Reuse the operator's slot configuration.
4. **Materialize immutable inputs.** Use write-back into a private archive based on the fixed target, plus the
   source diff and any resource/pin changes. Check file bases and reindex equality. Use committed pins. No shared
   working-tree snapshot containing another lane's edits; no git worktree. The artifact has its own input identity.
5. **Prove the right observation.** Ordinary compiled behavior: child boots the exact archive and runs the same
   runner with member branches. Boot: prove readiness. Upgrade/schema/storage change: also start from a supported,
   consistent snapshot of existing target data and exercise actual publication/reload/conversion with the new
   host. Fresh install alone does not prove upgrade. Do not let two JVMs write main's store. The present Datahike
   separate-store fork does not yet supply the selected-branch snapshot guarantee described below; resolve that
   library gap before claiming this check. Do not write an ad hoc datom serializer or copy the live directory.
6. **Record before cleanup.** Return clojure.test report data, failed assertions/errors, tests executed/reused,
   positive completion, exact tested inputs, phase timings and measured memory to the parent recording owner.
   Persist the report under the originating task/request before deleting the child's only evidence. Current
   `seon.operator/run-candidate-tests!` reduces the child result to summary text and a boolean; that is insufficient
   for agent repair and evidence reuse. Extend that owner and the existing test records, not another results store.
7. **Review and merge.** Root resolves the source/target diff on the merge branch. Test that resulting program;
   source-branch green never automatically proves a different merged program. Reuse individual results only when
   their code, test, fixture/read and host inputs still match. A changed target, source or dependency cannot silently
   inherit the old request's verdict. Commit with the existing writer's basis/digest guards.

After a timeout, do not release resources or launch overlapping work until actual exit. After a parent restart,
derive unfinished requests from facts, inspect the exact child process identity, and collect or terminate through
the operator. Never resume a partly executed test body. A child crash is an unavailable/error result, not a red
assertion or green zero-test run. Preserve enough error data to fix boot/compiler failures even if Seon never
reaches readiness in the child.

This is a narrow extension: the existing `seon.operator/check!`, candidate archive, process owner and
`seon.test/run` remain. The shell-only result consumption and any duplicate platform runner disappear. A remote
worker could later consume the same request, but network execution is unnecessary to solve today's problem.

### The upgrade snapshot is an unresolved library boundary

At the pinned revision, `datahike.versioning/fork-database` copies every konserve key, reads the source `:db`
head directly, and accepts a transaction id or instant as `:at`. It does not accept an arbitrary branch head or
commit UUID. Its documentation warns that copying during concurrent writes can tear; external secondary indexes
are not copied. Semantic store settings are inherited, and incompatible overrides refuse. Its reachability permits
are not evidence that concurrent transactions produce a consistent copy. A cluster's named branch is not necessarily
the source `:db` head. Therefore this is not an installed, safe, live-cluster snapshot transport.

The precise missing guarantee is: an independent writable store containing the selected immutable target commit,
its required reachable data and blobs, with retention protected during transfer and declared handling of secondary
indexes. Resolve it in Datahike/konserve, with their tests. The simplest initial upgrade regression can instead
create representative data under the previous committed program in a private store, stop that process, and start
the proposed program on the same private store. That proves the specified fixture's upgrade, not compatibility with
all of the current target's data. It complements, rather than replaces, target integrity validation. Decision 6
prices these alternatives; no unsafe live-store copy was performed here.

## 4. Fixtures an agent can use

Expose the existing support namespace by the same declared namespace acquisition as other first-party code.
`seon.sci.eval/load-core-namespaces!` deliberately excludes the test source root; initial base construction only
copies loaded namespaces. The live observation—support loaded on the host but absent in SCI—is consistent with
that acquisition/first-load gap. Verify the precise sequence in its regression; do not simply copy every test
namespace into every context or add a new fixture API beside test-support.

The smallest useful surface is already there: `seon.test-support/with-database`,
`seon.test-support/transacted!`, `seon.test-support/fork-cluster-ctx`, `seon.test-support/agent-value`,
`seon.test-support/define!`, `seon.test-support/apply-config!`, `seon.test-support/seed-cluster!`,
`seon.test-support/agent-tx` and `seon.test-support/with-running-agent`. Make their contracts, source and examples
discoverable with doc/source. Use the same functions in the child JVM.

| Need | Setup and evidence |
|---|---|
| Pure function | Plain values, no fixture |
| Database read/write | with-database branches from the executing test's captured commit; transacted! surfaces a refusal at setup |
| Agent entrance | Fork the member context onto its fixture connection; evaluate through agent-value or define! according to whether recording program facts is part of the behavior |
| Two agents sharing a cluster head | with-running-agent already uses the member's branch as the fixture cluster head. Add a second agent through agent-tx on that same connection, as the differential tests do. They share only the fixture head, never default |
| Store-global behavior | Explicit fresh-store fixture or a private file store; a branch cannot isolate blob keys, roster or GC |
| Boot, compiled replacement or real process lock | Declared isolated JVM fixture and actual process-exit evidence |

`with-database` needs an execution handle. Merely exposing its Var will not make arbitrary top-level exploratory
fixture calls work. First support the common loop: write deftest at the REPL, let the ordinary runner supply its
member handle, then read its report. For interactive fixture exploration, let the same owner acquire a disposable
handle from the agent's explicitly supplied execution and bracket it with cleanup. This is one additional arity or
request on the existing helper, subject to its contract; never a booted “test cluster” per form. Keep nested
with-database semantics explicit: it branches from the captured member commit, not from its parent's new writes.

The pilot's failure classes and changes needed:

- **Test definition digest:** file-backed deftest admission failed. Commit `7f0458ffd` fixes the namespace resolver
  digest path; the running archive predates it. Treat this as landed but not live-verified here.
- **Support visibility:** reproduced above. Proof must originate in MCP SCI, use the helper, run automatically,
  show a deliberate red then green, and verify fixture cleanup. A host test calling the helper is not that proof.
- **Dynamic observation:** SCI binding of a copied dynamic Var does not bind the original host Var. The existing
  task `seon.sci.eval/copied-vars-pin-old-wrapper-generations` names the seam. Prefer explicit observer input or
  a host helper that binds the host Var around the supplied function; `seon.test-support/read-operations` already
  does the latter. Expose and verify it before designing universal binding forwarding. Flow does not convey
  bindings by itself. Process-wide `seon.test-support/armed-counts` is not an exact per-test measure under concurrency.
- **Red-proof assignment cycle:** root sees proofs at the home, the proof is on the branch, and the commit gate
  refuses a deliberately red proof. Pin a branch proof's source/digest on the assignment and execute it against the
  baseline before assigning. No need to install failing program changes on main. Cost proofs use the owner-approved
  observation primitive; an unchanged value alone cannot prove an optimization. This changes assignment semantics,
  not merely test fixtures, and needs the owner's choice in §6.
- **Test status/context:** the pilot's no-task block problem reproduced. F4a is present in the pilot's archive but
  the full every-evaluated-form and reaching-test behavior is still being changed. Never claim F4a-d all installed.
  Fix the producer so failures, excluded/compiled requirements, pending checks and runnable next operations are data.

Several skills are stale relative to the authority/code: the REPL skill says host-bound declarations always keep
the compiled Var; the testing skill describes the refactor proof rule as unresolved although owner rulings allow
cost proofs; the Flow/Datahike skills still teach the retired system-turn context. Amend those skills in their owning
slices, not in this file's lane. These are evidence limits, not instructions to follow the stale behavior.

## 5. The complete incremental path and its gaps

Write-back is materialization, not acceptance. For an outside lane it may produce the claimed files before the Git
commit. For a product agent it should produce a private review/check archive before acceptance and accepted files
afterward. RF-1 is installed, but a default pointing every agent at the shared checkout would undermine independent
branches. Expected file digests detect collisions; they do not make shared unmerged file installation desirable.

| Step | Installed mechanism / present gap | Required fix and cost |
|---|---|---|
| Task and baseline proof | Assignment exists; pilot could not get a deliberately red branch proof assigned | Pin proof inputs on the assignment, baseline run, no main-code merge. One selected proof run |
| defn/deftest | SCI evaluation and record exist; unsupported host declarations cannot be made executable by this route | Ordinary defn remains; declared source edit for the compiled route. Analyze changed text only |
| Later form in same request | SCI sees the new Var; diff/write-branch read the pre-record database | Keep the REPL law. Either derive inspection from observed pending facts or offer the write-back after RECORD/next request. Do not silently say there was no change |
| Reaching tests | Selection/reuse exists; automatic step incomplete; core tests resolve to host Vars | F4a-d plus the test acquisition/evidence changes in §2. Follow selected dependency inputs, not the whole program |
| Branch evidence | RF-2 owner landed as `seon.cluster.branch-evidence/branch-evidence` at `871a28901`; bin/lane wiring was dirty during research; current checks use reach/verified facts, not executed implementation evidence | RF-3 before trusting compiled-sensitive changes; compare files to exact branch digests including removals. Unrelated data writes do not invalidate unchanged code evidence |
| Write-back | `my.program/write-branch!` resolves live; cross-namespace analysis fix landed. Pilot found undigested scratch namespace poisoning and same-request staleness | Fix scoped diff/namespace admission and destination semantics. One analysis per changed file; pilot measured 484 ms |
| Request merge | propose-merge! exists; target request verbs not installed. Proof-only readiness cannot represent “needs compiled proof” | Root test request before readiness; F5 updates one request when a newer proven source exists. Keep code task pending until required proof is current |
| Root review | Own-branch diff exists; spec documents G7: root cannot diff a request conveniently | MA/GV rows: request has fixed source and target; root gets diff, source, reports and next operations at the REPL |
| Build merge branch | prepare-merge! exists; documented G1/G2/G3/G5: new-function arming, schema comparison/proof visibility and test-only namespaces fail | MA-1/2 and associated task fixes; do not copy “already evaluated” state into a fresh context that never evaluated it |
| Resolve/test/merge | prepare/accept exist; live merge! absent. Spec documents wrong-target custody, leaked red branches, only-proof gate and absent kept resolution | One merge! per spec, explicit target, preserved merge branch, all selected tests green, complete integrity/norm checks and writer fence. Compiled request tests the actual merged inputs |
| Activation | merge of data is not JVM installation; reload exists but PB-03/RED-5 remain | Reload correct source, arm against adopted schema, recopy/advance base, verify a changed behavior and loaded identity. Classes/dependencies/boot changes use operator replacement |
| Next context / completion | No evidence here of automatic end-to-end activation feedback | Record merge and activation separately; return failures to task. Agent reacquires after confirmed activation; preserve its private values across compatible reload, report lost live values after restart |
| Retirement | Branch retirement exists; spec G8/G9 identify failure/leak cases | Delete only after task/request terminal state and actual agent exit; preserve failed checks and kept merge branches until resolved/aborted |

The merge spec's G1–G11 are prior recorded findings, not rerun in this read-only study. They must be reconciled
against current commits before implementation. The pilot and RF documents disagree about some “not installed”
claims because work has landed between them; the live resolution checks and named commits above take precedence.

For schema resources and dependency/fork updates, carry the resource or pin identity with the ordinary program
diff. Declaration-only write-back cannot invent deps.edn, arbitrary resource bytes or a gitlink revision. Root may
materialize approved immutable artifacts; a product agent needs a declared request for them. Until that is built,
these updates still require outside root work, and the interface must say so. Do not advertise end-to-end self-change
for that class prematurely. Library fork changes use the dependency's own tests plus Seon's integration proof.

The old-host live-store rehearsal in `seon.operator/check!` and the new-host empty-store boot in
`seon.operator/candidate-tests!` answer different questions. For a schema/parser/writer change neither alone proves
new-host upgrade of existing data. Retain both useful checks and add the missing new-host upgrade observation.
If old code cannot even read the proposed dependency/resource, let the isolated new-host upgrade judge it; do not
make old-host comprehension a universal prerequisite. An unsupported storage format still requires an explicit
conversion decision, not a silent fresh-store fallback.

## 6. Ranked decisions for the owner

Costs below describe engineering scope; they are not measured delivery estimates. Each row should split into
file-disjoint, independently proven cards before launch. No new task is claimed assigned or ingested by this note.

1. **Where compiled proof runs.**
   - **Recommended:** root-owned disposable check requested from the agent REPL. Smallest complete guarantee;
     medium scope (request/result persistence and operator integration), current minutes-per-check cost.
   - Add one reusable leased development JVM after measuring repeated iteration. More lifecycle work and retained
     memory, faster experiments, still a fresh acceptance check for restart-sensitive changes.
   - One per-agent JVM: strongest independent interactive workspace, highest memory/process cost. Choose only if
     platform work dominates enough to justify giving up the one-main-JVM ordinary-work economy.

2. **What “green” certifies.**
   - Conservative isolated check whenever execution cannot be tied to the changed definitions: simplest safe gate,
     more slow checks, no new interpretation assumptions.
   - **Recommended:** RF-3 records actual implementation evidence; affected compatible test bodies/helpers run in
     SCI, compiled integration uses the child. Moderate runner/acquisition work, fastest honest incremental path.
   - Interpret the whole platform in every branch: largest semantic change, unclear compiler/class guarantees and
     duplicated supervision. Do not choose it without a concrete workload that the smaller option cannot serve.

3. **How agents author code SCI cannot run.**
   - Root accepts exact source data attached to a task and performs materialization: smallest initial route; agent
     still authors and fixes, but root must manage the source proposal explicitly.
   - **Recommended:** a declared edit/test request integrated with existing program/source owners. Medium scope;
     preserves one analysis/recording path and exact digest review without shell self-modification.
   - Direct host evaluation in main: superficially small but changes every agent's platform before review; rejects
     the branch guarantee and is not recommended. A child REPL may offer host evaluation without that defect.

4. **How red proofs and long checks enter the task loop.**
   - Root lands an explicitly accepted failing-proof-only commit: less assignment work, but intentional reds on main
     interact badly with the all-green merge rule and require precise exemptions.
   - **Recommended:** assignment pins proof definitions on the task branch; baseline proof is run there, and a root
     test request may be pending before merge eligibility. Medium assignment/context change, no failing main code.
   - Relax red-before assignment for research/refactors: easiest admission, weakens “proof defines done.” Keep
     question tasks' specced answers and cost proofs instead where those fit.

   The owner must explicitly reconcile asynchronous compiled checks with the current rule that tests finish inside
   a serial eval request and nothing is “running.” Proposed wording: ordinary test work is serial; a separate root
   task owns process execution, and the source task shows a pending external proof until a completed report exists.
   A queue acknowledgement is never a test result. This is a real semantic decision, not a larger timeout dial.

5. **When a platform merge is complete.**
   - Data merge first, activation separately visible: simplest and consistent with irreversible external effects;
     leave the code task waiting on activation when its intent requires installed behavior.
   - **Recommended:** the same separation plus root's automatic activation task and behavior probe. Medium operator/
     context work; durable distinction between merged, installed and unavailable; restart preserves recovery facts.
   - Pretend data commit, Git write-back and JVM reload/restart are one transaction: impossible with the current
     independent resources. If atomic user-visible availability is required, a separately designed process handoff
     is needed; do not claim database rollback restores JVM definitions or an incompatible store conversion.

6. **What upgrade data the isolated JVM receives.**
   - **Recommended first:** previous-program fixtures in a private store, followed by new-program upgrade. Smallest
     working composition; another acquisition/start cost, narrow but exact behavioral proof, no pause of main.
     State the fixture coverage and retain target integrity checks; do not label it a live-target rehearsal.
   - Quiesced whole-store fork using the library, then explicit target-branch selection in the copied store:
     potentially less library work, cost proportional to the entire store and a writer pause. Prove branch
     availability and secondary-index handling first. A pause of default needs a separate owner decision.
   - Improve the maintained Datahike fork to export a selected retained commit consistently while main runs:
     larger dependency work and dedicated concurrency/GC tests; best eventual exact-target guarantee without a
     main writer pause. Only choose after measuring whether fixtures plus integrity checks leave a real need.

### Resulting rows/cards

These are proposed card subjects, not additions to the owner's schedule. Extend existing rows first.

| Priority | Existing row / proposed subject | Owner and positive proof |
|---|---|---|
| 1 | RF-3 and RF-2: evidence names the implementation used | Test runner/instrumentation/acquisition. A host caller executing the old body cannot certify the branch; a second green direct-call test cannot hide the mismatch; deletion/macro cases use their proper evidence |
| 1 | RED-5, then PB-03 and UL-9 | Operator/reload. Under program-class caching, change source, adopt and observe the changed result through a compiled caller and a retained SCI context; failed load never records installation |
| 1 | Pilot support-visibility and deftest admission | SCI/test-support. MCP-written test using with-database runs with the changed body, reports red/green as data and releases its fixture. Verify `7f0458ffd` after activation |
| 2 | Red branch proof assignment, linked to pilot friction 2 | Task/test/context. Root assigns a baseline-red proof on a branch without merging it into main; unchanged-value cost regression has an observable failing measure |
| 2 | Root-owned compiled test request | Existing test/operator/task owners. Source change which SCI cannot run returns a child failure then success to its author; wrong input identity cannot certify; child crash/timeout preserves evidence and joins exit |
| 2 | Declared source/resource/pin request | Program/source/write-back. Unsupported source survives as exact reviewable data; compiled installation is separate positive evidence; a resource and dependency pin reach the child unchanged |
| 2 | New-host upgrade proof and snapshot guarantee | Publication/store/operator and Datahike. New code upgrades previous-program fixture data, including a refusing conversion; main stays untouched. Exact-target claims additionally require selected-commit transfer, concurrent-write/GC and secondary-index proof; empty-store success alone cannot pass |
| 3 | MA-1/2/3/11/14/15/17 and G findings | Existing merge owners. One end-to-end task changes code, sees red/green, merges into its fixed target, installs it and sees the next context; conflict, schema and host-bound cases included |
| 3 | RF-1/4/6 and fixture exploration | Source/fixture owners. Private write-back has exact file/digest equality; same-request observation is honest; two agents share a fixture head without touching default |
| Later | Reusable development JVM experiment | Operator. Compare complete repeated-fix loop, allocation, retained heap and actual exit with disposable checks; retain only if it removes measured work and preserves fresh acceptance proof |

The first competency should be deliberately small: an agent changes a host-bound declaration, its proof fails on
the unchanged host, it submits exact source, root runs the isolated check, the agent repairs the reported failure,
and root merges/activates the exact proven revision. Follow with a schema upgrade against existing rows and a fork
pin bump. Those three exercise the hard boundaries; another ordinary pure-function pilot would not.

## 7. Landing and verification limits

This document changes no source/test lines. Read-only probes establish counts, visibility, entrances and the
running archive; source tracing establishes dispatch and process boundaries. They do not demonstrate an unmerged
platform reload, new-host upgrade, cross-process report ingestion or the proposed APIs. Those require the proofs
above. No paid provider run, shared JVM reload, reset, nested delegation or worktree was used.

The requested commit gate will name existing boundary tests individually rather than run their whole namespaces.
No executable test reaches a Markdown-only change; these tests check the premises, not the proposed implementation.
Known pre-existing namespace reds from the named research: `seon.sci.eval-test/the-evaluator-remains-live-after-its-namespace-reloads`
(RED-2); and `seon.cluster.write-back-test/a-merge-writes-each-change-into-its-own-file`,
`seon.cluster.write-back-test/a-require-added-at-the-repl-merges-and-writes-back-its-ns-form`,
`seon.cluster.write-back-test/an-agent-replacement-merges-and-writes-back-its-exact-span` (REPL-first §1).
They are documented historical reds, not freshly rerun or newly attributed by this lane.

Full working-tree lint, `clj-kondo --lint src script test --cache false`, completed in 15,869 ms with **0 errors,
1,478 warnings** (exit 2 for warnings). This is zero errors, not zero findings. The concurrent working tree was not
edited to silence them. The documentation has no changed contracts, resources or production inputs to compile.

The first gate tested two premises on candidate `87cb0ec89202ce12d734c0daded8241d2ab00e21` (HEAD plus this
document; no production overlay). Run `6f2b9952454a` executed two tests: four assertions passed, zero failed,
one errored, 3,020 ms. `seon.sci.eval-test/a-branch-change-leaves-its-compiled-callers-compiled` passed.
`seon.test-test/resolution-follows-admitted-source-and-acquisition` errored when its fixture passed nil as
the evaluation row to `seon.program/declaration-row`; the producer's map contract refused it. The earlier reason
for the absent row is not established by this report. This matches the already recorded missing-row boundary in
[test-refusal-observations-overflow-in-projection-acquisition](../../../../seon/issues/test-refusal-observations-overflow-in-projection-acquisition.md#2026-09-20--results-reuse-baseline-and-candidate),
not a defect introduced by this document. No commit was made. This is the foreign boundary excluded from the
second gate, rather than a claim that its namespace is green.

The failed gate took 293,600 ms overall: slot wait 234,137 ms, boot 45,023 ms (readiness 39,723 ms), and test phase
3,121 ms. Archive preparation was 1,449 ms and publication rehearsal 672 ms. These are operator phases, not an
ordinary REPL latency. They substantiate the queue/process cost of the proposed compiled route and do not justify
making an agent's ordinary serial evaluation wait that long. The report is retained by the operator at
`data/clusters/default/logs/operator-check-20261008T173743.142.edn`; the exact result and timings above survive
independently in this committed note.

The landing command selects the passing boundary test individually:

```sh
bin/lane commit hostbound-astra -m 'hostbound-astra: design REPL platform changes and root-owned compiled proof' \
  --test seon.sci.eval-test/a-branch-change-leaves-its-compiled-callers-compiled \
  docs/research/agent-platform/2026-10-08/hostbound/hostbound-astra.md
```

That gate uses HEAD plus this file, excluding foreign uncommitted production edits. Its terminal report is the
commit's verification result; the named boundary tests are not claimed green merely because this note names them.
