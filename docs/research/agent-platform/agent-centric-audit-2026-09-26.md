---
type: audit
status: independent Astra audit; source unchanged; cuts require owner review
created: 2026-09-26
branch: refactor/agent-platform
source-pin: 10d6fdff5edf870ad31f7980b098a547d546fcc7
live-source-pin: f0efd9cc8ed393757b9347160de87b331f2f01ee
---

# Agent-centric deletion audit — 2026-09-26

Seon already has the parts: agent custody → retained SCI context → source submission → the agent's Flow turn → evaluation → settlement → saved shown text, with test and merge writers beside it. The principal Clojure approach is to pass the reader, evaluator and recording policy into that composition, and derive views from its values; remove adapters that reconstruct its world or infer its outcome from presentation.

## 1. Broken things and evidence limits first

The most urgent finding is **F13**: ordinary settlement traverses the entire live result, twice on some paths, looking for SCI's unbound marker. This bypasses presentation bounds after evaluation. **F14** is a reproduced language defect: a valid map literal is refused solely because it contains a keyword in the seon.repl namespace. **F17** is a previously recorded correctness defect, still present at the pin: a preview receives the live connection and can write despite its “persisting nothing” promise. These outrank deletion yield.

Default was alive, reported health :observed and no problem counts. Its actual source root was data/source/f0efd9cc8ed393757b9347160de87b331f2f01ee, with hook publication off, pid 33919, start instant 2026-09-26T02:55:54.868Z. Reported readiness was **60,685 ms**; that fails the ten-second target. The Now section's older pid, timings and running-lane descriptions are not installation evidence. This audit did not restart, adopt, publish, run tests, create a runtime branch, invoke a provider, or redefine a Var.

Static citations below refer to **10d6fdff5**, not whatever HEAD is when this is read. I extracted that commit's src/, script/, resources/ and test/ into a private temporary directory for stable read-only inspection; no JVM or worktree was created there. Other lanes changed files during the audit. Their edits were not modified or included in the pinned findings; limited end-of-audit revalidation is recorded next.

**End-of-audit revalidation at 97b70b1d2:** another lane committed removal of F16's synthetic namespace row in 24bff3388, and replaced F21's repeated pending-namespace derivation with a working database value in d62d32bf7. **Do not launch F16 or F21 again.** Their original evidence remains below to explain the model error and proof requirements; no installed proof of those newer commits was run here. Commit 544daefed narrows F15's catches to ExceptionInfo, fixing the plain-Throwable loss described at the pin; the remaining question is whether any ExceptionInfo is being mistaken for a declared refusal. The concurrent uncommitted merge changes are also outside this audit. F13, F14, F17 and F18's cited mechanisms still appeared in the checkout at this recheck.

Pinned tracked inventory: **116 files, 90,074 physical lines** in src/ and script/, including script tests, comments and blank lines: src/ is 84,591 and script/ is 5,483. The assignment's approximately 84.6k matches src/ alone. An initial filesystem count of 90,354 included an ignored compiled Python cache and concurrent source differences; it is not the audited source count. Largest owners: turn.clj 5,769; db.clj 4,924; sci/eval.clj 4,667; fn.clj 3,997; schema.clj 3,951; render/web.clj 3,900; cluster.clj 3,676; test.clj 2,511; render/transcript.clj 2,481; test/runner.clj 1,902.

Method: whole-tree inventory and searches for evaluator/reader/context entry points, waits, catches, compatibility terms and first-party symbol references; then caller/callee inspection along the requested path. This is not a claim to have read all 90k lines or proved every dynamically reachable function. Negative caller results include src/, script/, test/, resources/, config and launch configuration; the large parity snapshot is checked as a declaration mirror, not counted as an executable caller. A public callable with no first-party reference is a deletion **candidate**, not proof no persisted agent program uses it.

Read authorities: AGENTS.md end to end; the complete Now section of open-items.md; the complete fix-lane preamble, including NO HACKS; architecture overview; data-oriented Clojure, REPL and testing skills. Compared against the one-REPL design, duplicate-paths audit, atom audit, fast-harness design and existing issue searches. No issue index or source file was edited. The explicitly bounded auditor assignment precludes nested delegation.

## 2. What is already converged; do not recreate these assignments

- **MCP SCI is an ordinary agent run now.** script/seon/dev/mcp.clj:388 calls host/host! for a branch and agent/evaluate-source! for both branch and root runs. agent.clj:740 listens before submit-source!, checks closure again after submission, and awaits a declared bound. The old “MCP SCI records nothing” finding is obsolete.
- **Web preview calls the common evaluator.** render/web.clj:1475 → turn/preview-sources:4888 → evaluate-sources:4742. The defect is its write authority, not an independent SCI evaluator.
- **Test member custody already uses agent acquisition.** test.clj:1950 and :1980 acquire and resolve from the member branch; the request context is acquired once at :1851. Do not refile the old per-member full-acquisition defect as current.
- **record-evaluated-tx is live.** turn.clj:2229 calls it from system-turn. The older claim that it has only test callers is false at this pin.
- **Reload overlap detection exists.** sci/eval.clj:1391 implements call-detecting-program-change; :3898 wraps ordinary evaluation; test.clj:240 wraps test work. The Now queue's blanket “absent” statement needs revalidation, not another detector.
- **The reply planner's two passes were explicitly ruled.** Do not turn a lexical segmenter plus semantic reader into a duplicate-evaluator finding without disproving that authority. The reserved-key refusal below is a different defect.
- **Waits have different subjects.** Agent closure, a test thread's actual exit, and an OS process's actual exit are not interchangeable. test.clj:284's unbounded watcher join intentionally retains resources until actual exit; its caller waits under a bound at :288. Deleting the watcher or equating timeout with exit would regress ownership.
- Boot before facts and external process supervision cannot call a not-yet-existing agent. Keep those adapters. script/dev/markdown and citations parse documentation, not agent replies; harness_costs deliberately compares raw dependency and wrapped costs. Their presence alone is not duplication.

## 3. Ranked cuts at the audit pin

Estimates are physical production lines removed/added, excluding new tests and documentation. They are ceilings for reviewed cuts, not measured diff sizes. “Net/risk” uses low=1, medium=2, high=4; it is a scheduling aid, not a probability. Correctness priorities F13/F14/F17 override this numerical order. No estimates count deletion of an entire large file merely because one section is redundant. Rows are non-overlapping estimates; grouping appears in §6.

| Yield rank | Finding | Delete | Add | Net | Risk | Net/risk |
|---:|---|---:|---:|---:|---|---:|
| 1 | F06 Transcript representation round trips | 420 | 70 | 350 | medium | 175 |
| 2 | F01 Unreferenced helper/export candidates | 103 | 0 | 103 | low | 103 |
| 3 | F09 Test-wide ambient repair, after confinement | 215 | 20 | 195 | high | 48.8 |
| 4 | F04 MCP JVM parallel evaluation/projection | 260 | 70 | 190 | high | 47.5 |
| 5 | F08 Test request builds an execution by hand | 205 | 60 | 145 | high | 36.3 |
| 6 | F11 Refresh reconstructs whole history | 115 | 45 | 70 | medium | 35 |
| 7 | F02 Producerless MCP schema residue | 28 | 0 | 28 | low | 28 |
| 8= | F05 MCP owns a second error cursor | 60 | 15 | 45 | medium | 22.5 |
| 8= | F07 Test bodies bypass the evaluation composition | 150 | 60 | 90 | high | 22.5 |
| 10= | F03 Test-only PREPL server | 20 | 0 | 20 | low | 20 |
| 10= | F20 Bootstrap completion checks the same count twice | 22 | 2 | 20 | low | 20 |
| 12 | F14 Reserved output keys in the reader | 18 | 0 | 18 | low | 18 |
| 13= | F12 Global profile snapshots on every evaluation | 70 | 35 | 35 | medium | 17.5 |
| 13= | F18 Write-back has a second file installer | 50 | 15 | 35 | medium | 17.5 |
| 15 | F10 Regenerate the continuing context each turn | 95 | 30 | 65 | high | 16.3 |
| 16 | F15 Phase catches turn arbitrary faults into refusals | 40 | 15 | 25 | medium | 12.5 |
| 17 | F13 Scan arbitrary results for SciUnbound | 18 | 8 | 10 | medium | 5 |
| 18 | F21 Rebuild preceding namespace rows per form | 8 | 4 | 4 | low | 4 |
| 19 | F16 Fabricate a namespace declaration after in-ns | 35 | 20 | 15 | high | 3.8 |
| 20 | F19 Operator round-trips its result through EDN | 11 | 5 | 6 | medium | 3 |
| 21 | F17 Preview inherits live write authority | 25 | 20 | 5 | high | 1.3 |

Total provisional opportunity at the pin: **1,968 deleted / 494 added = 1,474 net production lines**. This is about 1.6% of the audited tree, not a claim that these cuts alone approach 10,000 lines. After excluding the subsequently removed F16/F21 mechanisms, **19 candidate findings remain, with a nominal 1,455 net lines**, subject to re-pricing the partially changed F15. F06/F08/F09 are prerequisite-gated. Reject an implementation that meets the count by replacing the old machinery with another registry or runner. F16's estimate excludes the eight lines assigned to F21; neither is current deletion credit.

## 4. Findings and proof obligations

### F01 — Unreferenced helpers and a superseded agent renderer

**Evidence.** No first-party call or schema attachment was found for db/deletion-error (src/seon/db.clj:4165–4182, 18 lines), schedule/valid-cron? and valid-timezone? (schedule.clj:103–132, 30), schema/commit-registration-delta! (schema.clj:3526–3532, 7), render.walk/join-membership (render/walk.clj:354–376, 23), or the 25-line render/agent.clj namespace. agent-ai is called only by its own unused agent-html. These are not the similarly named live write-deletion-error at db.clj:4204.

**Converge on.** The database writer's write-deletion-error/removed-definition-error; schedule/execution-time and ZoneId at actual schedule admission; schema/changed-keys; walk/graph; agent/render-identity-ai and render-identity-html (cluster/agent.clj:282/:316). Deletion does not require another wrapper.

**Model error.** A public compatibility/export surface survived after its consumer moved. An indexed function's existence is not evidence it belongs on the active path.

**Cut / risk / proof.** −103/+0, low. Before retirement, query current function references and reaching tests on the accepted branch and distinguish historical references. Compile the packaged projection and publish deletions through the existing reference checker. Keep schedule parsing/invalid-input coverage at the actual writer. Partition the changes between slices A and B; do not launch overlapping owners.

### F02 — MCP error schemas outlive their producers

**Evidence.** resources/seon/schemas/seon.dev.mcp.edn:17–44 declares jvm-exception-error, projection-failure-message, projection-offending-class, projection-failed-error and projection-failed-result. Exact producer/key searches find no src/ or script/ producer. Current mcp-project catches use render.value/floor (cluster.clj:429), not these shapes. Live probe P3 confirms the three named error/result schemas are still installed.

**Converge on.** The surviving error producer's declared schema and seon.error.refusal/diagnostic; publication's schema retirement mechanism.

**Model error.** Retired transport representations remain program declarations, increasing schema population and teaching a result model no producer emits.

**Cut / risk / proof.** −28/+0, low code risk; persisted-schema retirement must be incremental. Prove no current contract, render attachment or stored entity requires these attributes before removing them; perform the repository's staged retirement on a live-store branch. Do not delete generic exception-class/frame attributes still used by ordinary errors.

### F03 — A second PREPL server survives solely as a test fixture

**Evidence.** src/seon/cluster.clj:475–494 defines mcp-io-prepl. Its callers are test/seon/mcp_test.clj:22 and dev/mcp_bridge_test.clj:588/:609. Production boot accepts seon.operator.prepl/io-prepl, not this function.

**Converge on.** The installed seon.operator.prepl/io-prepl transport and its injected printer. This is transport setup, not a reason to force JVM bootstrap through an agent.

**Model error.** Tests verify a hand-maintained server rather than the server users reach.

**Cut / risk / proof.** −20/+0 production, low. Convert the three test fixture entry points, then prove stdout, exception events, namespace and socket termination against the real accept function. Retain mcp-valf until F04 replaces its live caller.

### F04 — MCP JVM mode still owns another evaluator and result lifecycle

**Evidence.** script/seon/dev/mcp.clj:355–386 reads and enforces one-form policy, then calls host eval. cluster.clj:243–473 holds a ThreadLocal projection marker, separately acquires configuration, admits results, creates artifacts, serializes content, writes oversized blobs, prepares a print tree, reconstructs semantic data and encodes it again. Bridge :288/:301 decodes that envelope. Its exception/shown/config-key branches are consequences of these parallel result shapes. P1/P2 returned windowed artifacts even though the forms were declared read-only.

**Converge on.** agent/evaluate-source! → turn/evaluate-sources, with a JVM reader/evaluator **stage value**, plus sci.eval/shown-result, ordinary evaluation recording and existing blob retrieval. The common composition at turn.clj:4742 does not yet accept a generic evaluator stage; that is a real prerequisite, not an installed API.

**Model error.** “JVM is a transport mode” has become “JVM has a separate result and observation model.” A mode should choose execution semantics while result, failure, shown text and ownership stay common.

**Cut / risk / proof.** −260/+70, high; separate F03's 20 lines. Preserve a usable host REPL before store/agent readiness. Prove one read/evaluation/print, structured failures and causes, exact stored shown text, explicit JVM custody, large-value retrieval, and no replay after transport timeout. Host execution is not generally interruptible: socket close must never claim termination. Compare trivial, failing and oversized forms through SCI and JVM on their actual subjects.

### F05 — The bridge has an independent branch-error cursor and extra round trip

**Evidence.** script/seon/dev/mcp.clj:39–44, :531–532, :540–591 and :662 maintain window-bases per root/cluster/branch, send another PREPL form after each evaluation and truncate at 20 errors. render/transcript.clj:346 already derives branch-error observations for evaluations. First-observation and unknown-fork cases require further bridge-specific rules.

**Converge on.** The agent run/evaluation's basis and ordinary seon.error/occurred-since observation, exposed by the run response; the shared error renderer owns presentation. Transport failures still belong to the bridge.

**Model error.** The connector acts as a second observer with a private history cursor, so reconnect and concurrent sessions change which faults the agent sees.

**Cut / risk / proof.** −60/+15, medium, after the run response includes complete fault evidence. Prove faults on success, faults on failed submission before a run exists, faults after evaluation, independent sessions, reconnect, branch retirement and explicit unknown basis. Do not simply delete fault delivery. Normal cost becomes one remote request, not evaluation plus a second query/projection.

### F06 — Transcript converts evaluations into another model and back

**Evidence.** src/seon/render/transcript.clj:254 receipt-entry maps evaluation fields into private ::source/::result/::namespace/etc.; :485 emission reconstructs a REPL emission; :663–733 candidate-history builds yet another “stored-like” evaluation from an in-memory result, including separate comment and print-option rules. :734 projection, :544 projected-entry and :750 render-ai assemble it. This is live in web preview (web.clj:1490), eval/drive.clj:284 and history views. It is not a second final text grammar: evaluation-text already calls repl/text.

**Converge on.** turn/evaluation-facts' canonical evaluation value, the evaluation schema's render pair, seon.repl/text/render-html and walk/graph's identity/order. Retain required queries for message ordering, selected runs, captured historical bytes and error windows until converted.

**Model error.** The view models an evaluation's storage shape and lifecycle independently of the evaluation owner; every new field needs another preservation patch.

**Cut / risk / proof.** −420/+70, medium. Delete the conversion/entry machinery with its callers, not all 2,481 lines of transcript UI. Prove byte equality for in-flight versus settled forms, comments, namespace changes, nil/false, output, errors, print options, missing live objects, selected-run boundaries, restart and historical text. The task graph must include the same required history once before any selection query disappears. Existing REPL-entry-renders work owns the convergence; this is supporting audit evidence.

### F07 — A test body has agent custody but not the common evaluation stages

**Evidence.** test.clj:211–318 bounded-result chooses host run-var! or sci.eval/run-test. sci/eval.clj:4551–4578 invokes kernel/with-arm directly around test.runner/run-vars!. test.clj:182–209 synthesizes an attempt agent and turn only when effect reach was detected (:250), because the body otherwise lacks ordinary evaluation context. This special case is the clearest symptom of a missing common stage contract.

**Converge on.** turn/evaluate-sources with a callable test-body evaluator stage, test.runner's assertion fold, and seon.test/run's existing admission/recording writer. A test need not pretend to be model text or start a provider turn.

**Model error.** “Tests are agents for one body” stops at connection custody; effect/run identity, errors and printing still have separate construction. Host versus SCI should select a stage, not duplicate lifecycle policy.

**Cut / risk / proof.** −150/+60 across test.clj/runner callers, high; evaluator-owner changes stay in slice D. Keep actual-exit ownership, test member records and assertion semantics. Prove SCI and host bodies, once/each fixtures, effects, nested tests, no assertions, setup/cleanup failures, timeout without exit, and recorded completion with no armed calls. No test body runs twice merely to manufacture a receipt.

### F08 — The test request reconstructs its own execution handle

**Evidence.** test.clj:1561 digest-differences scans both entire definition populations. :1593 program-overlay-tx re-reconciles rows and recursively discovers missing lookup refs by traversing tx-data and querying each occurrence. :1774–1872 clean-request-handle names/creates branches, opens connections, creates projection state, seeds checkpoints, acquires SCI, assembles a fresh routing roster and hand-assocs ownership flags. Members then use the ordinary acquire-context! owner.

**Converge on.** cluster.agent/acquire-context! and release-context! for execution ownership; registry/branch! and cluster/seed-cluster-facts! for the supplied prepared world; program/changed-identities and fn/reconcile-tx for declaration deltas. The checkpoint's reusable content identity is useful and should survive as data.

**Model error.** A “test world” is treated as a second kind of execution instead of a normal execution whose immutable starting value contains only program and declared seed facts. Global population comparisons compensate for a missing carried base/change relation.

**Cut / risk / proof.** −205/+60, high. Prove clean worlds exclude inherited root/turn/message/error facts, preserve candidate replacements/additions/retractions and schema attributes, keep config/process inputs, release on setup failure, and preserve provenance for merge acceptance. Capture the common lineage once; if a value truly lacks it, use the existing bounded comparison/refusal. Target work follows changed declarations and required referenced rows, not total program size. Do not substitute a branch of dirty runtime facts for the current clean-world guarantee.

### F09 — Test execution repairs every live cluster after each member

**Evidence.** test.clj:1997/:2021 snapshots and restores live-cluster schema states. runner.clj:488–701 derives all projections, takes global instrumentation/schema registrations/cluster snapshots, compares them and applies env/advance-projection!. run-vars! :382/:398 takes two more ambient snapshots. drift-directions at :664 exempts added registrations because expected test loading would otherwise look like a leak.

**Converge on.** The member's explicit environment/branch and installation owners; seon.test host eligibility must isolate tests whose subject is process-global mutation. Ordinary branch-local tests should retire their member, not repair unrelated clusters.

**Model error.** The harness allows bodies to mutate ambient process state, then compensates with whole-process reconciliation and direction-specific exemptions. That obscures concurrent writers and adds cost proportional to unrelated clusters/wrappers.

**Cut / risk / proof.** −215/+20, high, **blocked on confinement**, as duplicate-paths audit C2 already states. Prove deliberately mutating fixtures are isolated or scope-owned, concurrent legitimate adoption is not undone, wrappers survive, and setup failure cannot leak. The current restore derives current facts rather than reinstalling its entering snapshot; do not describe it as the already-fixed stale-snapshot bug or delete this guard while its prerequisite is false.

### F10 — Every continuing turn regenerates the private context

**Evidence.** turn.clj:4967 always calls fork-for-turn; sci/eval.clj:2979 calls regenerate-agent-context! whenever agent-ctx exists. :2900–2969 scans current/base namespace state, classifies private roots with generation metadata, allocates new atoms/fork, merges resolver differences, then resets the original handle's state. No same-base shortcut precedes that work.

**Converge on.** The retained agent SCI environment and sci.eval/acquire!'s content-valid program identity. SCI core/fork creates isolation for a new execution; namespace-state/install-namespace-state! are maintained-fork seams for resolver state. SCI does **not** supply a proven turnkey “advance arbitrary retained private closures” operation.

**Model error.** A stable agent context is modeled as something to rebuild every turn and then repair back into its original handle. The common unchanged-program case should retain it; changed program inputs should drive the minimal reconciliation.

**Cut / risk / proof.** −95/+30, high and provisional. Establish unchanged-program identity before skipping reconstruction; retain the changed-base algorithm until closure/Var/alias/import/deletion semantics are proven. Prove two agents, same-name private Vars, captured private atoms and closures, changed indirect callers, rejected definitions, and no advancement while another evaluation owns the context. Target unchanged work O(1) apart from actual per-turn bindings, instead of O(namespace entries).

### F11 — Refresh rereads and reparses all past evaluations

**Evidence.** turn.clj:1934 latest-evaluations pulls every evaluation/read-evidence component, sorts by turn/ordinal, parses repl/shown-value and replays displayed diffs to reconstruct previous values. :1968 read-only-evaluation? parses source and runs two queries per candidate; system-plan :1986 calls it in both candidate gathering and filtering. This happens before generated reads are selected.

**Converge on.** The evaluation owner's latest evidence per distinct source, db/read-evidence-current? and read-evidence-changes, with stored shown bytes remaining immutable observations. Use existing immutable-value/query derivations, not an atom beside the connection.

**Model error.** Historical presentation is used as the source of truth for current read identity and prior values. Refresh should derive from recorded read provenance; the old transcript should only be displayed.

**Cut / risk / proof.** −115/+45, medium. Prove latest-by-source ordering, same source in different namespaces, compaction, skipped failed reads, no replay of effects/writes, unrelated transactions causing no recomputation, and exact old bytes. Cost now at least O(H log H + total shown bytes), plus up to two queries per read-only check; target O(distinct reached reads + changed evidence). No live full-history scan was attempted.

### F12 — Profiling adds a whole-process allocation to the happy path

**Evidence.** profile.clj:181 begin locks and walks all live profiling cells and allocates an IdentityHashMap and a long array per cell. sci/eval.clj:3967 does this for every evaluation; cluster.clj:292 does it for each marked PREPL return; test.clj:1996 repeats it per member. profile/explain :209 includes other threads' counter growth by construction.

**Converge on.** The existing profile/timed wrapper and execution ownership carried through the evaluator/member/Flow environment. Retain cumulative cells; change how one execution's evidence is collected, not another profiling registry.

**Model error.** A global time window stands in for an execution identity. It both scans unrelated callables and attributes concurrent work to the wrong operation.

**Cut / risk / proof.** −70/+35, medium. P3: 3,418 cells, **806,608 bytes** and **0.236–0.260 ms** for warm begin samples, before useful evaluation work. The time is sub-second; the allocation scales with the whole process. Prove overlapping executions do not attribute each other's work, inclusive time remains labeled inclusive, and ordinary call overhead remains bounded. Target O(exercised calls), with no O(all live cells) start operation. Do not call global cumulative status numbers this audit's self-time.

### F13 — Settlement scans arbitrary live values for one class

**Evidence.** turn.clj:93–102 unbound-value? performs tree-seq coll? seq with no node/depth/work bound and no evaluator interrupt check. evaluation-facts :117 calls it after evaluation; problems.clj:219 also calls it before checking scoped?/red? (resume-turn :5066 reaches form-problem). The same value can therefore be walked twice. Printing a bounded prefix does not bound these traversals.

**Converge on.** SCI's value semantics at evaluation and the existing bounded admission/render classification. If an unbound dereference is an error, its producer should return the ordinary error once. If an unevaluated Var is a legitimate value, display it as such. Settlement consumes that outcome and never recursively classifies the result.

**Model error.** A recorder guesses execution success by hunting a private dependency class inside arbitrary returned data. Nested finite data, lazy sequences and live objects are incorrectly treated as another validation program.

**Cut / risk / proof.** −18/+8, medium semantics risk, **urgent**. P2's pure finite-vector samples returned false with approximately 9,424 / 62,392 / 486,872 bytes allocated for 16 / 128 / 1,024 elements. This is linear work even when no marker exists. Static inference: an unbounded seq with no marker has no completion condition; not run on default. Prove unbound dereference versus returned Var behavior, ordinary collections and lazy results whose tail must stay unrealized at settlement, through a bounded isolated test. Do not patch this with another settlement-specific node cap.

### F14 — The common reader forbids ordinary data based on output-key spelling

**Evidence.** sci/reader.cljc:567–580 adds a reader error if a top-level map has any keyword whose namespace is “seon.repl”. P2 read {:example/value 1} successfully and refused {:seon.repl/value 1} with “You wrote a response. Only the REPL writes responses; send forms and wait.”

**Converge on.** The SCI/edamame reader's grammar. If a provider reply needs anti-fabrication guidance, that is reply/protocol policy; a Clojure data literal remains readable by MCP, tests and agent code.

**Model error.** Source/data syntax is confused with the role of an output message. The content of a map cannot establish who emitted it.

**Cut / risk / proof.** −18/+0, low. Read/evaluate/render/round-trip maps containing each seon.repl key, nested and top-level; verify genuine parser errors still refuse. Do not enumerate allowed keys or special-case JVM mode. This is independent of the ruled two-pass reply planner.

### F15 — Generic phase errors are reclassified; one known exception gets special rescue

**Revalidation.** 544daefed now catches ExceptionInfo rather than Throwable. The plain IllegalStateException example below applies to the audit pin only. Check the residual class-based assumption with an undeclared ex-info and a real declared refusal; do not reapply the catch narrowing.

**Evidence.** turn.clj:3415–3444 phase catches Throwable, merges error/refusal's deepest ex-data, stamps :phase-failed and returns it. The catch does not pass the Throwable to error.refusal/diagnostic, so a plain IllegalStateException loses its class/cause/frames. analyze-submitted :3459 rescues just :seon.fn/dependency-analysis-missing-error through fault/fault! before calling phase again.

**Converge on.** Each producer's declared agent refusal, otherwise fault/fault! with the complete Throwable and the owning environment. seon.error.refusal/diagnostic :91 and chain :75 preserve causal data; error/refusal only selects ex-data and cannot substitute for that.

**Model error.** A stage-wide “failure result” hides whether a producer returned an agent mistake or threw a core fault. The growing exact-schema exception list compensates for that lost distinction.

**Cut / risk / proof.** −40/+15, medium. Existing lost-errors work owns the broader class. Prove expected parse/author mistakes return unchanged, arbitrary analysis/acquisition/render exceptions retain chain and deliver to root, storage failure panics, and InterruptedException remains an interruption. The arity-message patch at sci/eval.clj:3835–3860 is another presentation smell, but is not counted as a separate deletion: establish SCI's actual structured arity evidence before removing its current diagnostic.

### F16 — An in-ns result is promoted into a synthetic program row

**Subsequently removed from source in 24bff3388; retained as pinned evidence, not an open implementation assignment.**

**Evidence.** sci/eval.clj:4366–4380 constructs ending-namespace-row when no declaration row exists and ending namespace differs. It stores the entire source of the namespace-changing expression as :seon.ns/source. turn/evaluate-sources :4788 builds a map of earlier namespace rows to help later declarations survive deferred settlement.

**Converge on.** SCI's actual namespace/resolver state and the existing program/declaration-row admission of authored declarations. Namespace identity/reference admission belongs to that producer, while the evaluator's current namespace is runtime state. Working program visibility within a run must be explicit.

**Model error.** Any expression that ends in a different namespace is interpreted as the declaration of that namespace. Deferred database visibility then needs a parallel set of pending namespace facts.

**Cut / risk / proof.** −35/+20, high; already assigned to fix-dogfood-api in the ledger. Prove in-ns to an existing and a new namespace, return-to-previous, do/conditional forms, require/aliases, subsequent contracted definitions and refusing settlement, all without rewriting existing namespace source or losing private state. The estimate is subordinate to that lane's reviewed working-value design; no new alternate design is authorized here.

### F17 — “Preview” suppresses settlement, not effects

**Evidence.** turn.clj:4888 promises persistence-free preview, but :4918 invokes evaluate-sources with the live cluster. That function :4804 carries its connection into evaluation; write? only controls handle creation (:4796), not write admission. render/web.clj:1475 supplies that preview for authored render source.

**Converge on.** The same evaluation stages with an explicit read-only effect/custody policy enforced by seon.db and seon.effect at their writers. A disposable SCI fork isolates namespace mutations, not database writes or host IO.

**Model error.** Absence of a run/settlement is mistaken for absence of side effects. A view must not acquire authority merely because it can call the evaluator.

**Cut / risk / proof.** −25/+20, high; existing issue [preview-sources commits its forms' writes](../../seon/issues/preview-sources-commits-its-forms-writes.md). Preserve the shared evaluator; delete the false special lifecycle assumption. Prove database/schema/message writes and external effects refuse before entry, pure reads still render, private fork state is discarded, and the live basis remains unchanged. No mutating reproduction was run for this read-only audit.

### F18 — Write-back duplicates the filesystem's digest-fenced installer

**Evidence.** cluster/source.clj:903 spliced, :932 install!, :978/:1005 read/check file bytes and :1009 install via raw Files/move. fs/jvm.clj:670 already stages bounded content and :702–710 serializes precondition recheck plus move. The write-back installer does not participate in that lock, so two Seon writers can both pass checks before one overwrites the other's result.

**Converge on.** The existing seon.fs.jvm write handler (through its declared effect/request owner), preserving write-back's declaration reindex/equivalence gate. seon.edit's byte-splice logic at edit.clj:229 is a candidate shared transform; its exact accepted-input shape must be compared before reuse.

**Model error.** Program export is treated as a new filesystem writer rather than a producer of proven bytes plus an expected digest.

**Cut / risk / proof.** −50/+15, medium. Prove exact UTF-8 spans, multiple replacements, unchanged neighbors, rejected stale digest, two same-JVM writers to the same path, stage cleanup and failed atomic move. The existing filesystem lock cannot exclude arbitrary external editors; do not promise filesystem CAS. Merge's expected-basis writer check must remain. Current prepare-merge! deliberately supports replacements only and write-back one file; these honest scope refusals are not “fix” candidates to remove by weakening guards.

### F19 — Operator responses are printed, parsed, then printed again

**Evidence.** cluster/boot.clj:537–547 readable-response calls pr-str then edn/read-string to test transportability; on failure it replaces the value with bounded text. request! wraps every result through this function. script/operator.clj:246 sends that already data-oriented request over PREPL.

**Converge on.** The declared operator response value and the one transport serializer; world-reference/Throwable admission at their actual producers. Keep external boot/process transport.

**Model error.** A printer is used as a runtime schema/codec validator. Opaque evidence is discovered late and rewritten instead of being represented at its producer.

**Cut / risk / proof.** −11/+5, medium. Prove Date/symbol/UUID values, declared refusals and complete causes survive the wire; unrepresentable process/context objects must become their existing identity references before this point. Byte work should happen once at serialization, not an extra full response traversal. No alternative string-based result store.

### F20 — Bootstrap completion contains a tautological second query

**Evidence.** eval/drive.clj:29–55 queries receipt-count and form-count using the same :seon.cluster.eval/run relation and run id. Variable names differ; populations do not. The equality always holds for successful reads and does not prove forms have terminal outcomes.

**Converge on.** The turn owner's durable closed/terminal facts and ordinary bounded await (agent/evaluate-source! :740 and seon.await/await!). Keep the driver's episode/objective observation if it asks a different question.

**Model error.** Planned forms and completed receipts are imagined as separate populations after they became the same entities. Renaming query variables preserves an obsolete lifecycle check.

**Cut / risk / proof.** −22/+2, low. Prove a valid closed bootstrap succeeds, an unfinished bootstrap does not, and a refused query cannot become zero via or. Replace the duplicated test with the actual writer guarantee, not a third count. The driver's await-fact! is bounded and listener-first; it is not an unbounded polling loop.

### F21 — Per-form pending namespace derivation is quadratic

**Subsequently replaced in d62d32bf7; retained as pinned evidence, not an open implementation assignment.** Verify the new working-value path's cost and correctness instead of optimizing the deleted accumulator.

**Evidence.** turn/evaluate-sources :4788 rebuilds settled by walking the entire results vector on every loop iteration, although it needs only namespace rows accumulated so far. For m forms this visits 0+1+…+(m−1) prior evaluations.

**Converge on.** evaluate-sources' existing immutable loop accumulator, then the unified working-program value when F16 lands.

**Model error.** An already-owned derived value is repeatedly reconstructed from history instead of carried forward once.

**Cut / risk / proof.** −8/+4, low. Fold new namespace rows into the loop state and prove later declarations see exactly the same last row per namespace. Measure small multi-form runs and a finite larger test; target O(m) updates. Do not land this as another long-lived pending-row mechanism if the F16 cut removes that mechanism.

## 5. Happy-path cost ledger

N = total declarations/namespace entries, H = agent evaluation history, R = distinct read forms, C = live profiling cells, V = returned collection nodes, m = forms in a run, Δ = changed declarations. These are source-derived bounds, not invented runtime timings.

| Stage | Current evidence and cost | Target / evidence still needed |
|---|---|---|
| Acquire context | agent/acquire-context! :940 is the common owner; continuing fork :2971 still walks namespaces (F10). base-ctx :3164 already caches by program content. | Preserve the content cache. Measure unchanged turn versus changed definition; do not report cached base construction as the private-context cost. |
| Read | SCI reader supplies events; reserved output-map policy refuses real data (F14). Code segmenter differs intentionally from prose segmentation. | One semantic read per evaluation under its current namespace; preserve ruled segmentation semantics. |
| Evaluate | profile/begin scans C cells; context-capture :729 and admit-program-change! :880 walk generation-owned Vars and namespace maps. turn-interns :407 skips identical inherited maps but still visits namespace entries and changed namespace contents. | Remove whole-process starts; do not claim SCI fork alone captures writes through previously held mutable Vars. New per-form mutation tracking needs dependency proof. |
| Record/print | shown-result :3674 is common SCI rendering; MCP JVM duplicates it (F04). evaluate-sources may print a replacement disposition (:4850); settlement final-shown :3483 may replace again. | Count actual print invocations, not “one owner” names. Final outcome is chosen before final print where possible; preserve exact saved bytes on writer refusal. |
| Settle | F13 is O(V), potentially nonterminating for an unbounded seq; F21 is O(m²). Settlement remains one batch writer. | No traversal of arbitrary live results; incremental loop values. |
| Refresh | F11 is O(H log H + text bytes), with repeated per-read queries. | O(R + changed evidence), no whole-history parsing per turn. |
| Test | F08 compares both N-sized populations per request; F09 scans process-wide state per member. Bounded member waits retain live ownership. | Base/Δ reuse and branch-local member state; keep actual-exit proof. |
| Merge | source/merge-base :749 traverses commit lineage under 10,000 bound; prepare :781 compares changed identities; accept :831 verifies run/green membership/program basis and writer expected-basis. | Do not remove safety guards for line count. Measure combined-program test gate separately from lineage and delta reads. Reuse the canonical program delta in test setup. |
| Write-back | source/write-back! :946 reads complete file twice, reindexes it and performs a separate installer. O(file bytes + file analysis), justified by proving unchanged neighboring declarations. | Share the writer (F18); retain whole-file equivalence proof unless analyzer evidence proves a narrower input boundary. |
| Boot | Live readiness 60,685 ms. Current status names inclusive over-second calls, not boot-specific causality. | Root owes an attributed boot clock/profile and existing >10 s issue reconciliation; this audit did not rerun boot. |

Live P1: heap used 2,506,344,072 bytes; max heap 10,997,465,088. Heap is a concurrent process observation, not retained-memory attribution. Three held-database root pulls took 0.138250 / 0.036459 / 0.038458 ms and allocated 57,160 / 56,944 / 56,944 bytes. Thus repeated pulls can be sub-millisecond yet allocation-heavy. loaded-code-mark scanned 569 namespaces: 0.158625 / 0.046167 / 0.043708 ms, 23,400 / 23,320 / 23,320 bytes.

No sub-second claim is made for complete acquire/eval/settle/test/merge/write-back. Exercising those would write facts and exceed this audit's read-only probe scope. No before/after speedup is claimed.

## 6. File-disjoint implementation slices

These are **proposed ownership sets**, not edits to the orchestrator's ledger. Read its current holds before launch. The observed ledger had active holds in the evaluator, schema, database, rendering and write-back owners; later changes must be reviewed, not overwritten. F16/F21 are verification-only following the commits noted in §1. Tests listed below stay with their production slice; shared test files require serial ownership transfer.

| Slice | Exclusive production files | Findings and dependencies |
|---|---|---|
| A — unused exports | db.clj, schedule.clj, schema.clj | F01's 55 lines only. Existing database/schema lanes must release first. |
| B — canonical evaluation views | render/transcript.clj, render/web.clj, render/walk.clj, render/agent.clj, render.clj, associated render schemas | F06, remaining 48 F01 lines; F17's view caller. Use core D's read-only stage before accepting preview proof. |
| C — MCP transport | cluster.clj, script/seon/dev/mcp.clj, seon.dev.mcp.edn | F02–F05. F03/F02 can precede the larger migration. F04 waits for D's stage contract; F05 waits for fault evidence in the ordinary response. |
| D — common evaluation and settlement | sci/eval.clj, sci/reader.cljc, turn.clj, problems.clj, repl.clj and their schemas | F10/F11/F13–F17/F21; shared evaluator support for C/E. Ship successive small loadable cuts, not one replacement engine. Prioritize F13/F14. F16 has an existing active owner. |
| E — test entrant | test.clj, test/runner.clj, test/accretion.clj and test schemas | F07–F09. F07 consumes D's stage seam; F08 proves clean-world behavior before removal; F09 waits for proven confinement. |
| F — profiling | profile.clj, instrument.clj, profile schemas | F12. Preserve begin/explain API while changing internals so caller files remain owned by C/D/E. |
| G — program file installation | cluster/source.clj, fs/jvm.clj, edit.clj, edit/jvm.clj and source schemas | F18. Preserve merge gates; exact installer reuse contract first. |
| H — operator values | cluster/boot.clj, script/seon/operator.clj and operator response schemas | F19. Coordinate with C's transport retirement; bootstrap path must remain available. |
| I — episode observation | eval/drive.clj | F20. Use existing turn/await owners without editing their files. |

For each implemented cut: root runs the relevant existing focused namespaces through bin/test-check default --ns, then the affected integration tier once. Useful existing homes are seon.sci.reader-test, seon.sci.eval-test, seon.turn-test, seon.dev.mcp-bridge-test, seon.cluster.mcp-test, seon.test.one-request-test, seon.test.runner-test, seon.render.transcript-test, seon.render.web-debug-test, seon.cluster.write-back-test and seon.cluster.boot-test. Their current green/red state is **not** asserted here. Add a behavior regression for each newly demonstrated class; deletion-only candidates need current reference/contract/publication evidence, not tests that merely restate deletion.

## 7. Dependency guarantees and stale guidance

Dependency pins recorded: SCI 409888f61fa1f3bbade8d7e3eecb9b8e09c8004a; Datahike f3a3cb59380c8b1e9ea0a3d6074c0863012cf0dd; Malli befcd302a8e626a67d84b80aabee0cd1b38dadb6; Clojure 3bc2b3e91fdff462620d84bbdfd40379d9c562e5; core.async dc35f3e0d7bc2eef502e77982f48641f025c8051. The inspected guarantees below are from local pinned sources, not claims about an uninspected upstream release.

- SCI core.cljc:345 fork copies the environment reference and changes generation, providing isolation for new/redefined Vars. core.cljc:751 namespace-state is the persistent resolver snapshot; :762 installs it. utils.cljc:362 owned-var supplies copy-on-write. Inputs are the held context/state; a fork happens at execution acquisition, not because the transport changed. This does not justify discarding Seon's private-closure preservation without proof.
- Datahike versioning.cljc:212 branch! branches from a commit/branch and uses shared indexes, with roster/GC coordination; :323 force-branch! and the Seon writer fence publication. A branch is not a new store. Test-world migration must carry a clean committed input and preserve roster ownership.
- Malli core.cljc:3131 -instrument constructs argument/result checking from a declared function schema. That guarantee is not semantic interchangeability of two differently contracted pipeline stages. Validate the new stage value by its named contract.
- Clojure main.clj:368 repl accepts read/eval/print/caught stages; core/server.clj:194 prepl and :275 io-prepl own host transport. These justify composition, not a second recorder.
- The filesystem's existing recheck/move guarantee is local to its serializer (fs/jvm.clj:702); it is not an OS-level compare-and-swap and cannot exclude arbitrary external writers.

The REPL skill's “A lane works on its own branch” section still says MCP branch defs do not write rows and write-back is not built. Those claims are contradicted by the installed-tool description and current host/run/write-back source; treat them as high-priority documentation defects, not implementation targets. The testing skill's host-custody/ambient-snapshot descriptions also lag test.clj:263 and runner.clj:609. This assignment authorizes only the audit file, so corrections are recorded here rather than editing those skills.

## 8. Reproducible read-only evidence

Commands: bin/seon status; MCP runtime_status with root /Users/sean/src/seon and cluster default. All three eval_clj probes used mode jvm, namespace user, session_id agent-centric-audit-20260926, read_only true, timeout_ms 5000, and explicit root/cluster. Each has only finite inputs and three timing samples. Thread allocation comes from the executing thread's ThreadMXBean and excludes bridge/network serialization and other threads; preparation of the finite vectors is outside the timed function. JVM JIT, arming state and concurrent workload can affect results.

The tool itself creates/retains a PREPL transport session; read_only preserves page invalidation state, not a promise that its result projection writes no blob. P1/P2 returned retrievable content-addressed artifacts (5,454 and 8,281 serialized artifact characters respectively), while P3 stayed inline. These are observations of the existing MCP path, not audit-authored database transactions. Basis changed between P1 and P3 while other lanes were active; that is not evidence these pure forms transacted.

Exact executable forms and returned PREPL values follow. No failed or truncated probe was counted as success. The complete transport envelopes were inspected: all returned a normal ret, no exception event or appended branch-error report.

### P1

```clojure
(let [connection (seon.cluster.boot/connection "default")
      database @connection
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      thread-id (.threadId (Thread/currentThread))
      allocated (fn [] (when (instance? com.sun.management.ThreadMXBean bean)
                         (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean thread-id)))
      sample (fn [f] (let [a (allocated) t (System/nanoTime) v (f)]
                       {:ms (/ (- (System/nanoTime) t) 1000000.0)
                        :bytes (when a (- (allocated) a))
                        :value v}))
      runtime (Runtime/getRuntime)]
  {:basis (seon.db/basis-t database)
   :heap-used-bytes (- (.totalMemory runtime) (.freeMemory runtime))
   :max-heap-bytes (.maxMemory runtime)
   :loaded (into {} (map (fn [s] [s (select-keys (meta (resolve s)) [:file :line])])
                         '[seon.turn/evaluate-sources seon.cluster.agent/evaluate-source!
                           seon.sci.eval/fork-for-turn seon.test/run]))
   :root-pulls (mapv (fn [_] (sample #(seon.db/pull database [:seon.agent/id] [:seon.agent/id "root"]))) [1 2 3])
   :code-marks (mapv (fn [_] (sample #(let [[revision namespaces] (seon.cluster/loaded-code-mark)]
                                      {:revision revision :namespaces (.size ^java.util.IdentityHashMap namespaces)}))) [1 2 3])})
```

PREPL time: 5 ms. Returned value (verbatim):

```clojure
{:seon.dev.mcp/value {:basis 536870955, :heap-used-bytes 2506344072, :max-heap-bytes 10997465088, :loaded {seon.turn/evaluate-sources {:file "seon/turn.clj", :line 4742}, seon.cluster.agent/evaluate-source! {:file "seon/cluster/agent.clj", :line 740}, seon.sci.eval/fork-for-turn {:file "seon/sci/eval.clj", :line 2971}, seon.test/run {:file "seon/test.clj", :line 2144}}, :root-pulls [{:ms 0.13825, :bytes 57160, :value #:seon.agent{:id "root"}} {:ms 0.036459, :bytes 56944, :value #:seon.agent{:id "root"}} {:ms 0.038458, :bytes 56944, :value #:seon.agent{:id "root"}}], :code-marks [{:ms 0.158625, :bytes 23400, :value {:revision 23972, :namespaces 569}} {:ms 0.046167, :bytes 23320, :value {:revision 23972, :namespaces 569}} {:ms 0.043708, :bytes 23320, :value {:revision 23972, :namespaces 569}}]}, :seon.dev.mcp/windowed? true, :seon.blob/digest "ebb1c411edf986f44332f728f2479a3e21b4584e1682043314f7545cc4c6f175", :seon.blob/size 5454, :seon.dev.mcp/retrievable? true}
```

### P2

```clojure
(let [bean (java.lang.management.ManagementFactory/getThreadMXBean)
      thread-id (.threadId (Thread/currentThread))
      sample (fn [f]
               (let [a (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean thread-id)
                     t (System/nanoTime) v (f)]
                 {:ms (/ (- (System/nanoTime) t) 1000000.0)
                  :bytes (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean thread-id) a)
                  :value v}))]
  {:settlement-scan
   (mapv (fn [n] (let [v (vec (range n))]
                   {:n n :samples (mapv (fn [_] (sample #(seon.turn/unbound-value? v))) [1 2 3])}))
         [16 128 1024])
   :reader
   (mapv (fn [source]
           {:source source
            :sample (sample #(let [r (seon.sci.reader/read
                                       {:seon.sci.reader/text source :seon.sci.reader/ns 'user
                                        :seon.config.eval.result/max-source 200})]
                               (if (vector? r)
                                 (mapv (fn [event]
                                         (select-keys event [:seon.sci.reader/form :seon.sci.reader/error])) r)
                                 r)))})
         ["{:example/value 1}" "{:seon.repl/value 1}"])})
```

PREPL time: 5 ms. Returned value (verbatim):

```clojure
{:seon.dev.mcp/value {:settlement-scan [{:n 16, :samples [{:ms 0.013583, :bytes 9568, :value false} {:ms 0.003208, :bytes 9424, :value false} {:ms 0.002542, :bytes 9424, :value false}]} {:n 128, :samples [{:ms 0.01325, :bytes 62392, :value false} {:ms 0.01175, :bytes 62392, :value false} {:ms 0.012417, :bytes 62392, :value false}]} {:n 1024, :samples [{:ms 0.094958, :bytes 486872, :value false} {:ms 0.090708, :bytes 486872, :value false} {:ms 0.104542, :bytes 486928, :value false}]}], :reader [{:source "{:example/value 1}", :sample {:ms 0.19525, :bytes 47632, :value [#:seon.sci.reader{:form #:example{:value 1}}]}} {:source "{:seon.repl/value 1}", :sample {:ms 0.104666, :bytes 51088, :value [#:seon.sci.reader{:form #:seon.repl{:value 1}, :error {:seon.error/expected :seon.sci.reader/accepted-source, :seon.error/operation seon.sci.reader/error-value, :seon.error/message "You wrote a response. Only the REPL writes responses; send forms and wait.", :seon.error/offending "{:seon.repl/value 1}", :seon.error/layer :seon.sci.reader/source, :seon.error/member :seon.sci.reader/response-keys, :seon.error/data #:seon.sci.reader{:text "{:seon.repl/value 1}", :line 1, :column 1, :phase "reply"}, :seon.error/at #inst "2026-09-26T03:02:13.897-00:00", :seon.sci.reader/response-keys #{:seon.repl/value}}}]}}]}, :seon.dev.mcp/windowed? true, :seon.blob/digest "e1e61208a2bb6106c4be102c67bd239a2a610125bafd853aaed3bddb49e56286", :seon.blob/size 8281, :seon.dev.mcp/retrievable? true}
```

### P3

```clojure
(let [connection (seon.cluster.boot/connection "default")
      database @connection
      projection (seon.db/carried-projection database)
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      thread-id (.threadId (Thread/currentThread))
      sample (fn [] (let [a (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean thread-id)
                         t (System/nanoTime)
                         mark (seon.profile/begin)]
                     {:ms (/ (- (System/nanoTime) t) 1000000.0)
                      :bytes (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean thread-id) a)
                      :cells (.size ^java.util.IdentityHashMap (:seon.profile/snapshot mark))}))]
  {:basis (seon.db/basis-t database)
   :profile-begin (mapv (fn [_] (sample)) [1 2 3])
   :schema-presence (into {} (map (fn [k] [k (contains? (:seon.schema.projection/forms projection) k)])
                                 [:seon.dev.mcp/jvm-exception-error
                                  :seon.dev.mcp/projection-failed-error
                                  :seon.dev.mcp/projection-failed-result]))})
```

PREPL time: 11 ms. Returned value (verbatim):

```clojure
#:seon.dev.mcp{:value {:basis 536871028, :profile-begin [{:ms 0.516417, :bytes 806752, :cells 3418} {:ms 0.259834, :bytes 806608, :cells 3418} {:ms 0.236375, :bytes 806608, :cells 3418}], :schema-presence #:seon.dev.mcp{:jvm-exception-error true, :projection-failed-error true, :projection-failed-result true}}, :windowed? false}
```

## 9. Landing

Only this audit document is delivered. Production/test change: **0 lines**. No tests or runtime changes were needed to validate a documentation-only audit; the source findings' required proofs are listed individually. Static references are pinned and live evidence is explicitly limited to the older archive. No source readiness, publication, test green or performance improvement is claimed. The private static extraction was removed after verification. The audit commit is identifiable by this document's Git history; it does not accept any proposed production cut.
