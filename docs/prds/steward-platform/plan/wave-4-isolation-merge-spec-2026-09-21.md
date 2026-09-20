---
type: plan
status: specified; launch after prerequisites
created: 2026-09-21
tags: [namespace-agents, isolation, merge, datahike, sci, tests]
---

# Wave 4 — isolated cluster work, tested before merge

Owner requirement, verbatim (2026-09-20):

> "run from within the runtime (agent triggered) tests just for their cluster and their sci context (they can work on a branch and only if all tests pass do we merge functions and schema changes and additional tests to the cluster branch)"

This document supplies the three launch specifications for wave 4 of
[namespace-agents-plan-2026-09-19.md](namespace-agents-plan-2026-09-19.md).
The quoted assignments below are intended to be passed verbatim, following
the form of §7 of
[malli-native-bridge-prd-2026-09-20.md](malli-native-bridge-prd-2026-09-20.md).
They specify work to implement; they do not claim implementation or green proof.

## 1. Binding order and verification boundary

Read the namespace plan's **whole history and final rulings**, not only its
wave table. Its §5 combined-candidate rule and §§6/8 D6, D8 and D13 supersede
the isolation research's after-merge testing sketch. A conflict creates one
fingerprinted task for root. Re-forking is the response to an obsolete tested
target, not an automatic resolution of a conflict.

Launch prerequisites, to be named by commit and proof in the assignment:

1. Bridge steps 1–3 have landed, **including step 3's orchestrator-owned
   reset and durable candidate/stamp proof**. The source read for this design
   still contains pre-step-3 acquisition paths. Do not copy them forward.
2. The shared test admission, recording and reuse owners have landed and their
   relevant outstanding verification is green. The
   [results-reuse research](../research/results-reuse-everywhere-2026-09-20.md)
   establishes host reuse through `run-owned`, fresh request events,
   `covered-by`, and the confidence tuple. Its final section also records a
   slot-blocked final run and an intermediate red tally. It is not a claim
   that all integration gates passed.
3. D2 task-keyed routing and the wave-3 `seon.task` owner are available before
   4b/4c integration. At the inspected snapshot that rename is not present;
   `seon.issue/subject-id` is the existing derivation to preserve. Do not
   implement a provisional second task family to bypass this prerequisite.

Land **4a → 4b → 4c serially**. They share program, cluster and operator
owners; this document grants no concurrent ownership of them. 4a and 4b prove
their runtime APIs with explicit scratch-root requests before 4c adds CLI
composition. A missing future CLI is not a reason to postpone those proofs.

The new cluster carries the selected branch's connection, stamped projection,
environment and agent SCI context. It never borrows the target agent's private
objects or changes that agent's connection. Ordinary interpreted changes need
no separate JVM. Host implementation changes which require JVM reloading are
outside this wave; an unavailable SCI test is a named refusal, not a green
host substitute.

### What 4a assumes from bridge step 3

The [step-3 research](../research/step3-carried-projection-2026-09-20.md)
was read through its scripts and failed experiments. Its persisted candidate
probe ran out of heap; its passing candidate probe used speculative `with`.
Neither substitutes for the prerequisite durable writer proof.

4a consumes the **landed** non-identity population stamp and acquisition API.
The bridge PRD §§2.3/6 place the stamp on the existing source population row;
the research prototype calls a cluster attribute
`:seon.cluster/projection-digest`. Wave 4 does not create either spelling as a
second stamp. Refresh the actual declaration after step 3 lands and use it.
The fork basis below is an observation of a commit, a different fact from the
population stamp.

An exact fork inherits the stamp. Its acquired DB/environment/context agree
on that generation, with unaffected compiled objects reusable. A declaration
transaction installs its rows and new stamp atomically; replacements recompile
their dependent schemas/contracts. Old held DBs retain their world. Temporal
views obtain schema/stamp through their origin, even when `since` contains no
stamp datom. Mismatched retained metadata, absent stamp and unacquired
generation refuse. Restart acquires the exact persisted population once;
ordinary reads never rebuild it from files or merge a different cluster's
registry. These are prerequisites, not new mechanisms for 4a to write.

## 2. Dependency and first-party ledger

Line anchors below refer to the inspected working-tree definitions. Refresh
anchors at launch after predecessor cuts; retain the owning functions and
semantics. Do not interpret an old line number as permission to restore old code.

| Boundary | Source and exact responsibility |
|---|---|
| Datahike branch | `reference-code/datahike/src/datahike/versioning.cljc:212` `branch!` accepts a branch or immutable commit, refuses missing/existing targets, acquires the roster permit, shares primary persistent roots, branches supported secondary indexes, writes the new head and then `:branches` (`:259`, `:270`). It does not copy a store. |
| Datahike merge | `reference-code/datahike/src/datahike/versioning.cljc:734` `merge!` explicitly requires caller-supplied transaction data and dispatches to `writer/merge-db!` (`reference-code/datahike/src/datahike/writer.cljc:440`). `writing/merge-writer!` (`reference-code/datahike/src/datahike/writing.cljc:860`) calls `core/with` on the writer's current DB, then supplies the parent set including its current branch. The commit loop consumes it (`writer.cljc:247`). **It records multiple-parent ancestry; it does not compute a three-way merge, content union, semantic conflict, test gate or Seon declaration replacement.** |
| Datahike deletion/retention | `versioning.cljc:279` removes a branch from the roster after its connections are released; physical reclamation is later GC. `fork-database` (`:550`) copies store keys and is not the branch operation for this wave. |
| Temporal reads | `src/seon/db.clj:390` `database-value-identity`, `:416` `basis-t`, `:2595` `database-view`, `:2661` `history`, `:2674` `as-of`, `:2688` `since`. Use `since(history(db), t)` for assertions **and retractions**, then historical identities. A transaction number alone cannot identify a basis across diverged branches. |
| Projection/custody | `src/seon/db.clj:232` `carry-projection-state`, `:1115` `schema-database`, `:1178` `carried-projection`; step 3 changes acquisition. `:343` `call-with-custody` and `:435` `foreign-connection-error` preserve exact branch custody. |
| Program ownership | `src/seon/program.cljc:14` `identity-attributes`, `:120` `derived-shape`, `:200` `shapes-in`, `:305` `row-identity`, `:779` `canonical-row`, `:874` `changed-attributes-in`, `:908` `replacement-tx`, `:928` `exact-replacement-tx-in`, `:943` `exact-replacement-tx`, `:950` `deletion-row`. Row ownership derives from `:seon.program/row-schema` and excludes entries owned by another writer. |
| Existing divergence/admission | `src/seon/turn.clj:1111` `declared-content`, `:1156` `declaration-diverged-since-open?`, `:1232` `row-tx`; schema removal/native change owners at `:1013` and `:1028`. Reuse their declared-content interpretation; do not invent a second source-only comparison. |
| Final write authority | `src/seon/db.clj:3462` `write-entity-value`, `:3523` `write-owned-values-error`, `:3789` `removed-definition-error`, `:3883` `write-deletion-error`, `:3900` `write-report-error`, `:3962` `write-report-validator`, `:4121` `transact-call`, `:4409` `transact!`. Every declaration merge enters `seon.db/transact!`; its codec, retention, typed refusal and final-report validator remain in force. |
| Final report in dependency | `reference-code/datahike/src/datahike/db/transaction.cljc:1206` `validate-report` rejects before commit and includes attempted datoms. An exception rolls the transaction back: a rejected merge cannot also commit its conflict task inside that same rejected transaction. |
| Test selection/admission | `src/seon/fn.clj:1438` `gate-sets-in`, `:1487` `gate-sets`; `src/seon/test.clj:666` `changed-definition-symbols`, `:711` `selection-seeds`, `:833` `select`, `:1136` `reaching`, `:1149` `selection-admission`, `:1249` `admit-run`. Reaching uses calls, references and declared subjects, not merely past dynamic reach. |
| Runtime test execution/reuse | `src/seon/test.clj:496` `host-admission!`, `:576` `run-owned`, `:1427` `recorded-result`, `:1475` `resolve-test`, `:1545` `prepare-tests!`; `src/my/test.clj:30` `run`. `src/seon/test/runner.clj:2240` `program-digest`, `:2290` `provenance`, `:2874` `record-tx`, `:3076` `record!` are the existing provenance/recorder, not a merge-owned replacement. |
| SCI acquisition/execution | `src/seon/sci/eval.clj:989` `acquired-program`, `:1049` `install-evaluated-rows!`, `:1662` `acquire-program!`, `:2063` `fork-for-turn`, `:2095` `base-ctx`, `:2199` `fork-cluster-ctx`, `:2961` `fork-candidate-ctx`, `:3057` `run-tests`, `:3073` `run-test`; `reference-code/sci/src/sci/core.cljc:345` `fork`. SCI forks do not retarget compiled JVM call sites or deep-copy arbitrary mutable roots. |
| Branch/cluster acquisition | `src/seon/cluster/registry.clj:132` `branch-commit-id`, `:140` `connection-branch-commit-id`, `:176` `branch!`, `:224` `ensure-cluster!`, `:250` `reset-cluster!`, `:291` `retire-branch!`; `src/seon/cluster/source.clj:167` `database` materializes the selected commit. `src/seon/cluster.clj:1990` `source-base!`, `:2780` `ensure-cluster-entity!`, `:3533` `stand-cluster-runtime!`, `:3617` `stand-boot-layers!`, `:3705` `start!`, `:3933` `stop!` own construction/lifetime. Refresh these cluster anchors after its in-flight cut. |
| Agent activation | `src/seon/cluster/agent.clj:685` `acquire-context!`, `:706` `arm!`, `:854` `disarm!`, `:899` `armer-step`. The inspected armer selects every agent row: copying a live branch and starting it unchanged would also activate inherited agents. 4c must scope activation through the candidate's task relation before first arming. |
| Error/task identity | `src/seon/error.clj:186` `signature` implements D13; `:1583` `recording`, `:1651` `commit-tx` own occurrences. `src/seon/issue.clj:470` `subject-id` derives detector + subject value. Follow its landed `seon.task` successor, with D2 atomic task/agent routing. No future task function is claimed to exist at this snapshot. |
| Operator | `src/seon/operator.clj:154` `connection`, `:533` `acquire-operation-store!`, `:545` `quiesce-cluster-under-lock!`, `:580` `cleanup-cluster-under-lock!`, `:596` `finish-cluster-cleanup!`, `:630` `cleanup-cluster!`, `:1076` `refork-under-lock!`, `:1112` `refork!`. These own store reacquisition, exact cleanup and refork. |
| CLI and execution handoff | `script/seon/fresh_operator.clj:440` `with-operator-lock`, `:687` `parse-init-arguments`, `:1605` `live-root-value!`, `:2384` `start!`, `:2594` `named-init-form`, `:2637` `init-form`, `:2815` `init!`, `:3587` `help!`, `:3623` `-main`; refresh anchors below by function before editing. `src/seon/flow.clj:762` `submit!` and `:821` `submit!!` supply bounded existing work submission, not another dispatcher. |

The isolation finding and the distinction between the two Datahike operations
are grounded in
[isolation-merge-writeback-2026-09-19.md](../research/isolation-merge-writeback-2026-09-19.md),
read end to end. The historical 17 ms measurement is for the branch primitive.
It prices neither environment acquisition nor instrumentation, graphs or tests.

## 3. Shared acceptance contract

### Bases, changes and exact definitions

Use **B** for the immutable fork commit, **T** for the immutable target commit
whose combined candidate is tested, and **C** for the immutable source commit
containing the accepted definitions and completed test evidence. These denote
values with store/branch provenance, never unqualified local entity IDs.

The minimal first implementation requires **B = T for automatic landing**.
This is the simplest combined-state construction: fork the actual target at T,
apply the task's proposed declarations on that candidate, then test there.
If another disjoint task lands first, the old candidate receives a stale-target
refusal, re-forks from the new T, reapplies its still-disjoint proposal through
ordinary declaration admission, and re-gates. Both changes then merge without
root conflict work. This specification does not promise that two candidates
tested against the old B can both land without re-testing.

Check same-identity divergence against the original B **before** classifying a
request as merely stale. Otherwise re-forking would erase the very evidence D6
requires. A refusal reports the old proposal and current target, with B. A
conflict task's root resolution is a new proposal on a fresh candidate and
passes the identical gate. No automatic reapply precedes that resolution.

`changed-since` identifies declaration events, including removal/recreation;
the merge planner then compares complete canonical owned content at B and C.
An unchanged final declaration is not replayed merely because it was touched.
Source bytes, contracts, admission, namespace bindings, schema properties and
owned components participate. Test-run counters, occurrence evidence and
another writer's attributes do not become authored changes. Component-only
edits must lead back to their owning program identity through before/after
relations. Deletion is `:db/retractEntity`; there is no stored tombstone.

Transport declaration refs as stable lookup identities and owned children as
their values. Rebuild deterministic analyzed/contract components with the
existing declaration owner where necessary; preserve the accepted source bytes
and canonical meaning. Never transact a source branch's numeric entity IDs
into the target. Native schema installation, logical schema rows, functions,
new tests, deletions and same-transaction caller repairs are one accepted
target transaction under the final candidate projection.

### The merge gate is the existing test system

Select the union of every test reaching changed functions, changed/new tests
themselves, and schema/namespace-dependent definitions' reaching tests. Compute
reach over B/T and the candidate so removal of an edge cannot hide an existing
obligation. A surviving required test cannot be removed from the gate by
deleting its declaration in the proposal; report that missing obligation.
Explicit root resolution of a test retirement must supply its replacement
acceptance obligation through the existing task mechanism.

Use `seon.fn/gate-sets`' shared frontier and `seon.test/select` /
`selection-admission` / `admit-run`. Execute needed members through
`seon.test/run-owned`, supplying the candidate connection and **its acquired
SCI context**, and retain results through the shared recorder. Do not invoke
shell gates or `clojure.test/run-tests` as the runtime merge gate. No wholesale
namespace/all/platform widening silently changes this selected scope.

The observed `selection-seeds` refuses schema keys and `select` explicitly
refuses unproven schema dependency coverage (`src/seon/test.clj:748`, `:964`).
4b must extend that selector using declared schema references, contract shape
relations and program edges. Literal keyword usage
(`src/seon/fn.clj:1770` `functions-using`) is not complete dependency evidence.
If bounded coverage cannot be established, return the missing schema/edge/test
evidence; never treat an empty selection as proof of safety.

A fresh request is admitted even when it executes nothing. A reused member
retains the original confidence tuple:
`[:seon.test.run/basis-t :seon.test.run/program-digest :seon.test.run/input-digest]`
(`resources/seon/schemas/seon.test.selection.edn:15`). Different whole-program
digests can still admit reuse when the selector proves unchanged reachable
content and external inputs. Do not replace that authority with whole-digest
equality or entity-ID equality.

`covered-by` includes reservations as well as completed evidence. Before
landing, resolve every selected symbol through actual admitted/covered members
and require positive assertion evidence, begin/end, completion and termination,
zero failures and errors, and compatible selector confidence. Missing,
in-progress, excluded, unlaunchable or timed-out work blocks acceptance and
names the test. Wait for exact terminal facts under the declared bound; a
zero-member selection is valid only with recorded complete coverage or an
explicit, successfully derived empty obligation set. No wildcard pull's
implicit 1,000-member limit may decide completeness.

### Durable acceptance and conflict evidence

4b declares one acceptance observation attached to the accepting transaction;
it is not another test result registry. Proposed names are exact additions
unless predecessor schema discovery finds an existing equivalent:

| Declaration | Grammar and ownership |
|---|---|
| `:seon.cluster/fork-basis` | Non-identity UUID commit value on the candidate cluster row. Derive its transaction number by materializing that commit; do not store a second basis counter. |
| `:seon.cluster/fork-source` | Nonempty source-cluster name **value**, with identity disabled; provenance survives source rename/removal and is not a cross-branch ref. |
| `:seon.cluster/task` | Peer ref to this candidate's locally present task. Absent on ordinary clusters. The stored cluster schema requires it whenever fork-basis/fork-source are present; the final-report validator must enforce that conditional stored shape, not only the acquisition request. Sweeping the required task ref therefore refuses; branch destruction owns removal of the candidate. This is the declared work assignment used for activation, not a kind stamp. |
| `:seon.program/changed-identity`, `/changed-identities`, `/changed-since-request`, `/changed-since-result` | Read envelopes in `seon.program.edn`: identity, observed assertion/retraction transactions, supplied basis locator and current locator. Positive examined/complete evidence distinguishes an empty answer from missing population/history. No new durable changed-row collection. |
| `:seon.program.merge/id` | Fresh request identity through `seon.id/id`; carried unchanged through retries of that request. Unique identity on the successful accepting transaction, enabling outcome lookup after a lost response. A different proposal requires a new request. |
| `:seon.program.merge/evidence` | Blob digest **value**, identity disabled, on the accepting transaction. The existing blob owner makes the typed acceptance payload durable before publishing the transaction. No source-local blob entity ref is transported. |
| `:seon.program.merge/acceptance` | Typed blob payload: request id; store id; source/target branch values; B, T and C commit UUIDs; target basis-t; proposed identity-to-definition-or-retraction data; candidate generation digest; schema dependency definitions/digests; complete selected test symbols; source run-id/member-symbol locators and each result's original confidence tuple. Records observations, not mutable latest-result copies. Declare its nested keys under this namespace in `seon.program.merge.edn`. |
| `:seon.program.merge/request`, `/result` | Runtime envelopes additionally carry the acquired source/target environments, immutable DB values and declared deadline. Runtime objects are not serialized into the acceptance payload. Result carries accepting transaction/commit, or a specific composed refusal. |
| `:seon.program.merge/conflict`, `/stale-target`, `/gate-refused`, `/evidence-unavailable` | Specific error schemas composed with the landed error base, with identity, B/T/C and appropriate evidence. No `:kind` discriminator or boolean class marker. Conflict contains both sources or explicit absent-side observations. |
| `:seon.db/merge-parents` | Optional transaction-request member, a nonempty set of immutable commit UUID values. An instruction to `transact-call`, not a stored attribute or user-supplied validator override. The source evidence commit C is the parent supplied by the merge owner. |

The exact read-envelope additions in `seon.program.edn` are
`:seon.program/basis`, `/current`, `/change-events`, `/examined`, and
`/complete?`, alongside the four shapes in the table. Basis/current carry
the existing `:seon.db/database-value-identity` grammar; the runtime basis
request also carries `:seon.db/db`, never serialized as an identity.
Each changed-identity value carries the existing `:seon.program/identity`
and change-events, whose members use `:seon.db/tx` and `:seon.db/added?`.
The result carries the two locators, changed-identities, the nonnegative
examined count and the asserted completion observation. These are read
values, not stored counters/flags. The existing `:seon.program/change`
describes an affected-subject read and keeps its semantics.

The acceptance payload adds these exact value keys under
`seon.program.merge`: `/store-id`, `/source-branch`, `/target-branch`,
`/base-commit`, `/target-commit`, `/source-commit`, `/target-basis-t`,
`/definitions`, `/generation-digest`, `/schema-dependencies`,
`/selected-tests`, and `/test-evidence`. Store/branch/commit values use
the dependency's identity grammars. Definitions use stable program identity
keys and portable owned values; retractions use the existing deletion-row
grammar. Each test-evidence member reuses the existing run-id, test symbol
and three confidence keys, with explicit terminal evidence from the shared
record. Only `/id` and `/evidence` are new stored acceptance attributes;
the other keys describe the blob or runtime envelopes. Declare the exact
composed error outputs of each new function, not a catch-all result map.

Canonicalize the typed acceptance payload with the existing identity/blob
owners. Preserve exact source strings; no pretty-print/read round trip may
change the accepted definition. The merge writer verifies the payload against
the immutable source DB and its recorded members, not a caller's `green?` flag.
C includes the completed evidence; ordinary test recording must not alter the
tested declaration/input generation. If it did, the request needs selection
again. Successful Datahike ancestry retains C after candidate branch cleanup.

On a conflict, the rejected program transaction writes nothing. The runtime
owner then records the writer's exact refusal using `seon.error/recording` and
the landed task writer through a separate `seon.db/transact!` on the target.
Use D13's existing signature: layer, operation, sorted satisfied facets,
optional throwable class/frame, violated key/shape, location path. Put the
conflicting **program identity** in that location; source bytes, basis, time,
message and process belong to occurrence evidence, never the fingerprint.
Derive the root task with the existing detector + error-signature subject-value
identity. Repeated delivery updates one error/task and its occurrences, with
one task agent/notification as ruled, rather than starting duplicate roots.

This is deliberately two transactions, not a fictional atomic failed write.
Keep the request/evidence durable through the existing task/effect request
before attempting the merge. Report a conflict-task identity only after its
recording commits. Recording failure is a typed unresolved outcome with the
request id; retain its source evidence. After interruption, a new task attempt
queries the accepting transaction/task by identity before doing work; it does
not resume an interrupted evaluation or blindly resubmit a possibly committed
write. The ordinary task recovery/settlement owner supplies that obligation;
there is no new retry daemon. Source destruction refuses while an unresolved
request still needs its sole evidence, or first preserves that evidence through
the existing target error/blob owner.

## 4. Lane 4a — basis and changed program projection

> Implement **wave 4a only: fork-basis facts and `seon.program/changed-since`**
> in /Users/sean/src/seon, branch steward-platform. Use astra low for this
> bounded slice. Read this document end to end, the entire namespace-agents
> plan, the isolation-merge-writeback, results-reuse-everywhere and
> step3-carried-projection research documents linked above, AGENTS §§1–5,
> and the data-oriented-clojure, data-modeling, datahike, repl and
> clojure-testing skills. Verify the named step-3/reset prerequisite before
> production edits. The population stamp is already owned and implemented.
>
> You are not alone in the codebase. Preserve unrelated edits and adjust to
> landed predecessors. No delegation, worktrees, default restart or foreign
> session operations. Ask the orchestrator to release held paths; continue
> independent work when a foreign path is unavailable.
>
> **Own** `src/seon/program.cljc`, `resources/seon/schemas/seon.program.edn`,
> `resources/seon/schemas/seon.cluster.edn`, the basis initialization/read
> seams in `src/seon/cluster.clj`, and
> `test/seon/program_test.clj`, `test/seon/cluster/registry_test.clj`.
> Own one landing note
> `docs/prds/steward-platform/research/wave-4a-basis-2026-09-21.md`.
> If S6's identity derivation is still unfinished, also own the direct
> identity-consumer conversion in `src/seon/fn.clj`,
> `src/seon/cluster/source.clj`, `src/seon/sci/eval.clj`,
> `src/seon/turn.clj`, `src/seon/test/runner.clj`,
> `test/seon/schema/admission_test.clj`; convert all callers in the same
> slice. Ownership of `src/seon/test.clj` is limited here to making
> `changed-definition-symbols` consume the common change projection while
> preserving its content-digest and namespace-expansion semantics. Hand it
> to 4b on landing. Do not change execution or merge policy in 4a.
>
> **Ground existing seams:** read `program.cljc` end to end; its identity
> vector at :14, shape derivation :120/:200, identity :305 and ownership
> :874 are the owners. Read `db.clj` temporal/carried-projection seams
> :2595/:2661/:2674/:2688 and :1115/:1178, the registry :176/:224/:250,
> source `database` :167, cluster `source-base!`, `ensure-cluster-entity!`
> and `stand-boot-layers!` from the ledger, and `test.clj:666`.
> Read Datahike `versioning.cljc:212` and SCI `core.cljc:345`; branch
> acquisition is not a copied database or an agent connection override.
>
> **Add** `:seon.cluster/fork-basis`, `/fork-source`, `/task` with the exact
> value/ref semantics in §3. Search the merged declaration population first.
> Enforce the fork-basis/fork-source/task relationship on the stored cluster
> shape as well as the acquisition request. Add a canonical transaction
> regression which deletes the referenced task and observes refusal from
> the swept cluster value; a constructor-only assertion does not prove it.
> Add `seon.cluster/fork-basis` as the read of the selected cluster row and
> `seon.cluster/initialize-fork-call` as the transaction function which
> establishes the selected physical fork's local cluster identity and basis
> from the supplied immutable source value. Never infer B from
> `:seon.source/commit-id` (publication/adoption identity), the current
> target head, or the current maximum transaction after initialization.
> Repeated initialization at the same facts is a no-op; mismatched
> initialization refuses and names both bases. A refork establishes new
> facts only after the existing physical refork owner has replaced the branch.
> Do not claim ordinary setters can reset a running candidate's basis.
>
> A live-cluster fork inherits a cluster row. Re-identify that existing
> branch-local row for the candidate and install its candidate config/task
> relation through the existing initialization writer; do not append a
> second row and leave singleton cluster reads ambiguous. Preserve program
> rows and the inherited step-3 stamp. 4c owns starting the assigned task,
> including suppressing unrelated inherited-agent activation; 4a can prove
> initialization against dormant fork connections.
>
> **Add** `seon.program/changed-since [database basis]`, with a basis
> envelope carrying the immutable B locator/acquired database. Resolve and
> verify B through the existing branch/source owner, then use
> `seon.db/since (seon.db/history database) (seon.db/basis-t basis-db)`.
> Require same store and actual branch ancestry: equal transaction numbers
> on two forks are not interchangeable. Missing/history-disabled/unavailable
> B is a typed refusal, never an empty result. An ordinary in-memory fixture
> may pass its acquired basis directly; persisted fork acquisition verifies
> the immutable commit before handing the request to this pure read.
>
> Derive program identity families from declared `:seon.program/row-schema`
> on actual identity declarations, under the carried generation; derive
> owned attributes from `shapes-in` and the existing writer-ownership
> properties. Replace the existing literal membership mirror in place if
> still present; do not add a second list. Preserve deterministic admission
> order through declared dependencies. Convert every public Var consumer
> before changing its callable/value shape; refresh the caller inventory.
>
> Read both asserted and retracted datoms, join identity through history,
> and follow component ownership on both sides. Return unique program
> identities plus their observed change evidence, including deleted and
> recreated identities. A component-only contract or namespace-alias edit
> names its owner. Current state is read from the supplied current DB,
> not from the `since` slice. Keep event detection separate from net owned
> content comparison. No file scan, name regex, result-counter change,
> global cache, copied branch roster or persistence of this projection.
>
> **Canonical regressions:** extend the real `with-database` fixture
> (`test/seon/test_support.clj:1028`), `program-fn-row` (:1064),
> `seed-cluster!` (:1096), and `transacted!` (:309). Fork two branches from
> exactly one B through the registry. Verify equal fork-basis facts and
> initial stamps, distinct connections/environments/SCI handles; writes in
> A never change B or the target. Edit a function, remove a caller-free
> declaration, and delete/recreate another identity; assert exactly those
> identities and both event directions. Add schema-only, metadata-only and
> owned-child-only cases to the same class regression. A test result write
> yields no authored declaration delta. Same-source recreation can yield
> events while the net comparison remains equal. A missing basis/program
> population and a foreign-lineage basis refuse positively. Ordinary empty
> since views still resolve the origin's stamped projection. Attempted
> live-caller deletion still refuses through the existing final validator.
>
> **Live proof:** on only `tmp/wave-4a-root`, use the existing operator to
> publish/start a scratch target. Resolve its immutable commit and use the
> registry + initialization owner to create two dormant candidate branches;
> acquire their stamped environments/SCI contexts with the existing owners.
> Commit one accepted declaration on A and query changed-since on A, B and
> the target. Record B's commit/t, branch names, projection digests, exact
> source bytes, positive delta and negative sibling observations. This is
> a new scratch fork proof, not default hot reload or adoption. Release
> materialized DBs/connections; clean branches through the operator, down
> the scratch root, prove its JVM exited and remove it. Measure branch and
> projection/SCI acquisition separately; no unsupported 17 ms boot claim.
>
> Iterate serially with `bin/test-fast --paths <all owned changed paths> --
> seon.program-test seon.cluster.registry-test seon.test-test`, adding the
> named caller namespaces needed by an identity conversion. Use real SCI,
> armed contracts, canonical schema population and loud bounded event waits.
> No cold `bin/test`, `--all` or `--full`. Before committing require every
> changed production namespace in one JVM, serial with the test run. The
> orchestrator owes path-limited cold and platform proof.
>
> **Deliver:** path-limited coherent commit, this slice's landing note with
> all changed paths and exact fast/live evidence, counts derived from facts,
> any RESET NEEDED statement and the exact integration proof still owed.
> Estimate **1–2 lane-days**, including identity conversion and fork facts;
> the earlier half-day estimate covered only the projection sketch.
> **Stop** after 4a lands. Stop before a second projection authority,
> unconverted public caller, unauthorized native storage change or missing
> prerequisite; name the exact boundary. Foreign load/gate breakage is not
> evidence against this slice: use HEAD-plus-owned-paths fast iteration and
> continue independent work, never operate another lane or create a worktree.

## 5. Lane 4b — merge writer, runtime gate and conflict task

> Implement **wave 4b only: basis-aware exact replacement, runtime merge gate,
> accepting writer and fingerprinted conflict task** in /Users/sean/src/seon,
> branch steward-platform. Use astra low; bring a real design decision to the
> owner with exactly three priced options before crossing it. Read this
> entire spec, the entire namespace plan including §§5/6/8 D6/D8/D13,
> all three linked research notes, AGENTS §§1–5, and the
> data-oriented-clojure, data-modeling, datahike, repl, clojure-testing and
> seon-flow-architecture skills. Verify bridge-step-3/reset, shared-test,
> task-routing and 4a commits/proofs. Do not interpret historical green
> subsets as a passed integration gate.
>
> You are not alone. Preserve unrelated work; use released paths only,
> no delegation, no worktrees and no default lifecycle changes. Implement
> the existing owners in place; do not repair a foreign lane's session.
>
> **Own** `src/seon/program.cljc`, `src/seon/db.clj`,
> `src/seon/turn.clj` only for shared declared-content/admission extraction,
> `src/seon/test.clj`, `src/seon/test/selection.clj` only for dependency
> selection, `src/seon/sci/eval.clj` only for candidate test acquisition,
> and `src/seon/operator.clj` for the system-side merge handoff.
> Own `resources/seon/schemas/seon.program.edn`, the new
> `resources/seon/schemas/seon.program.merge.edn`,
> `resources/seon/schemas/seon.db.edn`, `seon.store.edn`,
> `seon.test.edn`, `seon.test.selection.edn`, `seon.operator.edn` in that
> same schema directory, and the landed `seon.task` schema/owner only for
> conflict detector integration. The prerequisite task owner supplies its
> actual writer symbol; do not create a replacement while it is absent.
> Own `test/seon/program_merge_test.clj` (new canonical class regression),
> `test/seon/program_test.clj`, `test/seon/test_test.clj`,
> `test/seon/test/selection_test.clj`, `test/seon/sci/eval_test.clj`,
> `test/seon/db_test.clj`, `test/seon/operator_test.clj`, and
> `docs/prds/steward-platform/research/wave-4b-merge-2026-09-21.md`.
> Consume error/recorder/Flow owners without changing their mechanisms;
> identify any required expansion before editing them.
>
> **Grounding:** read program/db/test/cluster source owners end to end and
> read every ledger seam this slice touches. In particular, replacement
> (`program.cljc:908/:928/:943`), turn divergence (:1111/:1156/:1232),
> DB final validation (:3789/:3883/:3900/:3962/:4121), gate-sets
> (`fn.clj:1487`), runtime admission (`test.clj:496/:576/:1149/:1249`),
> resolution/acquisition (:1475/:1545), SCI :1049/:1662/:3057/:3073,
> error signature/recording (:186/:1583) and task subject identity
> (current `issue.clj:470`). Read Datahike merge dispatch and final-report
> validation from §2. `merge!` computes no merge content for you.
>
> **Extend `exact-replacement-tx` to take a basis**, with an explicit
> shapes-carrying basis-aware arity in its existing implementation path.
> Keep existing non-merge arities until their callers are converted; none
> silently gains permission to perform a merge without B. Factor the
> existing turn `declared-content` into `seon.program/declared-content`
> and have both owners call it, retaining turn-specific own-write logic
> only in the turn owner. The pure replacement result is transaction data
> or the specific conflict refusal, not a write. Compare canonical owned
> B/current/proposed content with absence represented explicitly. Cover
> add/add, edit/edit, edit/delete and delete/edit; equal final content is
> an idempotent no-op, not a conflict. Metadata and namespace/schema/contract
> changes count, numeric component IDs and result counters do not.
>
> **Add** `seon.program/merge-call [target-db request]` as the sole
> `:db.fn/call` acceptance decision and `seon.program/merge! [request]` as
> the runtime orchestration over it. Add `seon.program/merge-acceptance`
> for immutable gate/evidence construction and
> `seon.program/record-merge-conflict-call` for composing the existing
> error and task writers. Declare the §3 schemas and acceptance attributes.
> Every function, including private helpers, carries a complete contract.
>
> `merge-call` reads current target facts at the writer. It validates the
> named request, B/T/C, stamp/dependencies, exact proposal bytes and complete
> shared-recorded test coverage. It checks same-identity conflicts against
> B before stale-target refusal. If clean, require the actual target
> commit **and basis-t** to equal tested T and B=T. A caller/contract/schema
> change after testing blocks the commit even when changed function
> identities are disjoint. Resolve current numeric IDs only on the target.
> Generate exact replacements, additions and retractions together, with
> native schema changes and the step-3 candidate stamp in the same
> transaction. The final-report validator remains the authority for
> components, swept refs, required members, calls, render relations and
> same-transaction repairs. No special merge exemption.
>
> **Accrete `seon.db/transact-call`** with optional request
> `:seon.db/merge-parents`. After the same preparation, codec, retention
> and internally supplied final-report validator, dispatch such a request
> through Datahike's asynchronous merge API; ordinary requests retain their
> existing dispatch. Do not call raw `d/merge!` from program/operator code
> and bypass `seon.db/transact!`. Preserve the current wait/outcome-unknown
> semantics and typed refusals. Strip the request instruction from domain
> transaction data. Use immutable C UUIDs, never mutable branch keywords,
> as supplied parents. A successful commit has current target and C as
> parents; a refusal must add neither program rows nor ancestry. Check the
> head inside `merge-call`: Datahike's ordinary transact expected-basis
> option is not by itself a verified guard for `merge-writer!`.
>
> **Use §3's combined-state gate exactly.** A stale disjoint source does
> not merge its old green results: return stale-target, let the existing
> refork owner create the new candidate, admit its proposal and run selection
> again. Schema dependency support belongs in `selection-seeds`/`select`,
> with the graph's declared references; no private merge selector. The
> complete union of reaching tests and new/changed agent tests is an
> explicit eligibility scope. Long selected tests remain obligations under
> their declared durations. A destructive/unavailable member blocks and
> names the required stronger proof; it is not silently excluded.
>
> `run-owned` remains the admission/execution/result path. Make its
> `prepare-tests!` consume the candidate's carried generation/context and
> make `resolve-test` resolve the actual selected definition in that SCI
> context. The inspected core-admission arm uses `requiring-resolve`; a
> compiled core test calling a JVM function does not test its SCI override.
> Use the existing SCI stored-source test acquisition for such a selected
> test, preserving its declared fixture behavior, or return a named
> not-runnable refusal. Do not relabel a host execution as candidate proof,
> change global JVM Vars, clear another agent's private layer, or fork a
> replacement runner. Reuse also requires compatibility with this execution
> context: old host-only evidence for an overridden call cannot certify it.
>
> The agent invokes the merge through an explicit **system-side target
> request**. Add `seon.operator/merge! [request]` to resolve source/target
> once at the operator boundary, carry both environments as values, and
> submit work on the existing launcher. Test work receives source custody;
> the accepting transaction and conflict recording receive target custody
> through the existing DB scope owner. Neither operation rewrites the
> agent's environment or disables `foreign-connection-error`. Submission,
> waiting and results use existing bounded Flow/task execution. Record the
> request in the existing task/effect facts before dispatch. No cross-cluster
> connection lookup inside per-test or per-row loops and no new scheduler.
>
> **D6/D8/D13:** preserve the writer's refusal and both source definitions
> plus B. Record it in the separate target observation transaction described
> in §3. Use the existing D13 signature with program identity in its
> location and the existing detector + signature-subject task identity.
> Root is acquired by task routing when needed. Duplicate requests create
> one conflict task and one task agent; occurrence/repeat evidence remains
> queryable. Missing task recording returns evidence-unavailable and leaves
> the request unresolved; never claim a task exists from its computed id.
> Root's resolution and tests pass the same gate, with no privileged write
> or automatic reapply before resolution. After a lost response, query the
> accepting transaction by merge id before another attempt.
>
> **One canonical regression class**, using real branch forks, canonical
> population/helpers, real SCI acquisition and armed contracts, must prove:
>
> 1. Two candidate clusters A/B fork one target basis. A's function change
>    and B's disjoint schema/function/test change eventually both land:
>    A lands, B's old tested head refuses, B re-forks/re-gates, B lands.
>    No target declaration changes during either test execution. The target
>    source strings equal the submitted accepted source UTF-8 bytes, and
>    canonical schema/contract/test facts equal the accepted definitions.
> 2. Both edit one identity differently. The second merge refuses, names
>    both sources and B, preserves target rows/stamp/head, then commits one
>    error/task through the separate recorder. Repeated and concurrent
>    deliveries share that task/fingerprint despite different occurrence
>    timestamps/bases/messages. A different program-identity location yields
>    a different fingerprint. Root resolution requires its new test green.
> 3. A reaching test that fails on the candidate blocks the merge and names
>    its qualified symbol and recorded result. Include a caller changed
>    only on the target, and a schema-dependent test: disjoint identity
>    checks alone must not admit the old candidate. Include a core-admitted
>    test whose expected answer distinguishes candidate SCI from JVM and
>    sibling definitions, plus a new agent-authored test with the same
>    distinction. Fixtures/contracts must run, not merely be present.
> 4. A repeated green request is a fresh admitted event with complete
>    `covered-by`/confidence and no extra body invocation. Pending coverage,
>    missing member, zero assertions, failed recording, timeout without
>    termination and excluded selected member each block honestly. A
>    greater-than-1,000 obligation/read case cannot truncate silently.
> 5. Deleting a function with a surviving caller refuses at the real final
>    writer. Repairing that caller and deleting the function together can
>    pass. Exercise required-ref refusal, optional-ref sweep, component-only
>    edits and same numeric EID allocated to different identities on forks.
>    No component/result facts from an unrelated source writer are replayed.
> 6. Move target T after a green gate but before writer admission; no
>    program/stamp/merge-acceptance/parent change lands. A clean accepted
>    transaction has two parents and survives source-branch destruction.
>    Inject a lost response and conflict-recording failure: outcome lookup
>    and ordinary task retry neither duplicate acceptance nor lose the
>    unresolved refusal. Missing source evidence is never reported green.
>
> **Live proof:** only `tmp/wave-4b-root`, one runtime JVM, one scratch
> target and two candidate clusters. Trigger the gate from a real agent
> SCI evaluation through the runtime API, using fixture-controlled replies
> rather than a paid provider. Record source/target branch and ctx custody,
> B/T/C, selected symbols, run IDs and original confidence, pass/red
> evidence, exact definition bytes, accepting transaction and parents.
> Demonstrate disjoint re-fork/re-gate success, a red block, a moved target
> block and repeated same-identity conflict producing one task. Inspect the
> durable task's both-source evidence; do not infer it from console text.
> Use the existing refork/cleanup APIs until 4c's CLI exists. Down/release
> and remove only this root after observing termination; preserve the
> reproducible forms and measured phase results in the landing note.
>
> Iterate one JVM at a time with `bin/test-fast --paths <all owned changed
> paths> -- seon.program-merge-test seon.program-test seon.test-test
> seon.test.selection-test seon.sci.eval-test seon.db-test
> seon.operator-test` and the landed task namespace's regression. Before
> each coherent commit require changed production namespaces; no retired
> Var leaves a caller behind. No cold `bin/test`, `--all` or `--full`;
> the orchestrator owns cold/platform verification.
>
> **Deliver:** path-limited commit, every changed path, exact fast tally,
> measured gate/transaction/conflict-recording durations, live evidence and
> remaining cold/platform boundary in the named landing note. Estimate
> **3–5 lane-days**: the earlier 1–1½ days priced only a replacement call,
> not candidate SCI fidelity, schema selection, durable evidence and D6/D8.
> **Stop** after 4b. Stop before changing host-code execution semantics,
> inventing task/retry/registry machinery, weakening final validation or
> substituting a different gate. Missing prerequisites are named dependencies;
> foreign dirty/load failures are preserved and excluded with owned-path
> fast iteration. Continue independent work; never repair foreign sessions.

## 6. Lane 4c — one-command cluster fork, destroy and re-fork

> Implement **wave 4c only: runtime/operator composition for N candidate
> clusters and their cleanup** in /Users/sean/src/seon, branch
> steward-platform. Use sol low for the mechanical operator work. Read
> this document end to end, the entire namespace plan and its final
> D2/D6/D8/D13 rulings, all three linked research notes, AGENTS §§1–6,
> `src/seon/cluster.clj` and `script/seon/fresh_operator.clj` end to end,
> and data-oriented-clojure, data-modeling, datahike, repl,
> clojure-testing and seon-flow-architecture skills. Verify 4a/4b and
> the bridge-reset/task prerequisites before edits.
>
> You are not alone in the tree. Preserve unrelated changes; no delegation,
> worktrees, default restart or foreign session operations. The orchestrator
> releases shared owners before this serial slice starts.
>
> **Own** `script/seon/fresh_operator.clj`, `bin/seon` only if its argument
> forwarding needs change, `src/seon/operator.clj`,
> `src/seon/cluster.clj` at candidate initialization/activation,
> `src/seon/cluster/agent.clj` at task-based arming selection,
> `resources/seon/schemas/seon.operator.edn`,
> `resources/seon/schemas/seon.boot.edn`,
> `resources/seon/schemas/seon.cluster.edn`,
> `test/seon/dev/fresh_operator_test.clj`,
> `test/seon/operator_test.clj`, `test/seon/cluster/cohost_boot_test.clj`,
> `test/seon/cluster/armed_test.clj`, and
> `docs/prds/steward-platform/research/wave-4c-operator-2026-09-21.md`.
> Consume the existing registry primitives and 4b merge owner. No new store
> implementation, branch roster, test runner or merge policy.
>
> **Exact CLI:** `bin/seon [--root ROOT] fork --from TARGET NAME...`
> creates the named candidate clusters from **one captured immutable target
> commit**, initializes each candidate task through the existing task
> owner, and starts their environments in the root's existing JVM. One
> requested name means one independently mergeable task/candidate. The
> command returns every name, actual branch, task id, B and readiness.
> `bin/seon [--root ROOT] destroy NAME...` stops and destroys exactly those
> candidates through cleanup. `bin/seon [--root ROOT] refork NAME` derives
> its target from `/fork-source`, handles an eligible stale-target refusal,
> establishes the new B and starts the replacement candidate. No name
> pattern or group registry is used to discover what to destroy. Use an
> explicit nonempty vector of names; reject duplicates before mutation.
>
> **Add** `seon.operator/fork! [request]` and
> `seon.operator/destroy! [request]` as compositions of existing owners;
> extend `refork!` for a candidate request rather than adding a second
> refork implementation. Add `parse-fork-arguments`,
> `parse-destroy-arguments`, `parse-refork-arguments` and corresponding
> CLI dispatch/help/generated forms in `fresh_operator.clj`.
> Declare `:seon.operator/cluster-names` as a nonempty vector of valid
> names, `/from-cluster`, `/fork-request`, `/fork-result`,
> `/destroy-request`, `/destroy-result`; extend `/refork-request` without
> changing its existing exact-commit meaning. These are request/result
> schemas, not persisted batch entities. Reuse 4a's three cluster
> attributes and 4b's typed refusals; add no state flags.
>
> **Ground existing seams:** CLI lock/parser/live-root/start/init/help/main
> from §2; operator `connection` :154, store acquisition :533, quiesce
> :545, cleanup :580/:596/:630, refork :1076/:1112; registry branch
> :176, ensure :224, reset :250, retire :291; cluster `source-base!`,
> `stand-boot-layers!`, `stand-cluster-runtime!`, `start!`, `stop!` from
> §2; agent armer :899 and acquire/arm/disarm :685/:706/:854. Read their
> actual definitions after 4a/4b before changing generated forms.
>
> Capture the selected target DB/commit and carried generation once at
> the owner, pass it as data to every fork. Never resolve `current-src`
> separately for each name or assume it equals TARGET. Use
> `registry/ensure-cluster!` with the exact commit; acquire its own
> connection/environment/SCI context; keep compiled immutable sharing
> where step 3 permits it. Existing explicit commit initialization and
> ordinary `start` remain valid. A live target's copied cluster row is
> initialized through 4a's owner before any domain reader sees two cluster
> identities. Candidate config refers to the candidate name.
>
> **Activation is part of isolation.** A fork copies durable task/agent
> history, not live graphs or private objects. Before armer prime, the
> candidate's `/task` relation identifies its assigned work. Change the
> existing `armer-step` selection for a task candidate to derive eligible
> agents from that task's current agent relation; ordinary cluster
> selection keeps its existing semantics. Do not arm every copied agent,
> synthesize a permanent root worker, or replay inherited pending external
> work. D2's writer creates/updates the local task and its agent atomically;
> its normal wake reaches the existing armer. A later task assignment change
> goes through that same authority. No parallel armer or per-agent branch
> rebinding. Existing recovery marks interrupted execution; it does not
> restart old effects. Preserve read-only historical facts without treating
> them as fresh candidate work.
>
> **Re-fork semantics:** consult the recorded 4b outcome. A stale tested
> target permits a fresh candidate against the newly captured target head.
> Preserve the exact disjoint proposal/evidence before destructive refork;
> the task agent re-admits that proposal and calls the runtime gate again.
> The CLI itself neither rewrites definitions nor reuses old acceptance.
> A conflict returns the existing root task id and both-source evidence;
> do not refork/reapply it as automatic repair. A red test requires task
> repair; a recording/outcome-unknown refusal requires outcome lookup.
> Missing refusal evidence is unavailable, not permission to discard work.
> No unbounded refork loop: one command makes one attempt and returns the
> next explicit task/result state.
>
> **Destroy/lifetime:** use existing root lifecycle/control locking once
> at the outer operation, following the CLI's under-lock variants so the
> live child does not reacquire its parent's lock. Stop exact instances and
> await their published completion under existing bounds before releasing
> connections and retiring branches. Respect GC reachability permits and
> reuse the operator's store reacquisition after stopping the last instance.
> Never kill a shared JVM to remove one candidate. A batch is not one
> atomic Datahike transaction: return per-name terminal outcomes. On failure
> clean only resources newly acquired by this call, retain/report any
> cleanup failure, and preserve pre-existing names and siblings. Do not
> report absent/degraded resources as ready. Source-only unresolved merge
> evidence must be preserved or destruction must refuse as §3 specifies.
>
> **Canonical regressions:** use the existing cohost/operator fixtures and
> real registry/writer, not mocked Datahike branches. One fork command for
> two names must return two distinct branch connections/environments/SCI
> contexts and exactly one B, even if the target advances between creations.
> Their task agents see different accepted definitions in the same namespace;
> neither target nor sibling changes from a candidate write. Seed an
> unrelated inherited agent with pending work and prove it is not activated
> in the candidate. Ordinary cluster arming still works.
>
> Drive 4b's disjoint merge → stale refusal → refork → fresh test request
> → acceptance through these APIs, asserting byte-identical accepted
> definitions. Drive a repeated same-identity conflict and assert one root
> task with no automatic refork or reapply. A red reaching test names its
> symbol and prevents merge; live-caller deletion still refuses. These
> integration assertions reuse 4b's fixture setup, not another gate model.
>
> Exercise partial start failure, duplicate/existing names, missing source,
> destroy twice, destroy one sibling while the other stays live, last
> instance release, and restart of a persisted candidate with its own
> stamp/basis/task. Plant a symlink to an external sentinel in the candidate
> directory and prove recursive cleanup never follows it. Run actual CLI
> integration serially and declare long durations; do not launch nested
> cold gates or a second concurrent owned test JVM.
>
> **Live proof:** only `tmp/wave-4c-root`. Initialize/start scratch target;
> run `bin/seon --root tmp/wave-4c-root fork --from target w4-a w4-b`.
> Observe both readiness records and same JVM identity, task/agent routing,
> B/stamp and branch-local SCI results. Trigger the two agents' runtime
> tests/merge; demonstrate stale-refork-and-regate and one conflict task.
> Run `destroy w4-a w4-b`; query that the target still serves its accepted
> definitions and both candidate branches/advertisements/graphs are gone.
> Measure branch fork, projection/SCI acquisition, graph startup, tests and
> cleanup separately. Finish with root-qualified down, observe child exit
> and no held connections, then remove this scratch root. Preserve exact
> commands/forms, source bytes and measured outcomes in the landing note.
>
> Iterate serially with `bin/test-fast --paths <all owned changed paths> --
> seon.dev.fresh-operator-test seon.operator-test
> seon.cluster.cohost-boot-test seon.cluster.armed-test
> seon.program-merge-test`. Require changed production owners before each
> coherent commit. The CLI's actual boot proof is additional to the fast
> fixture proof; no default restart. No cold `bin/test`, `--all` or
> `--full`; the orchestrator owns cold/platform integration.
>
> **Deliver:** path-limited commit and the named landing note, complete
> changed paths, exact fast tally, live commands/results/phase timings,
> cleanup evidence and outstanding orchestrator proof. Estimate
> **2–3 lane-days**, including task-scoped activation and failure cleanup.
> **Stop** after 4c and its owned cleanup. Disk export/writeback is wave 5.
> Stop before a new scheduler/group registry, automatic conflicted replay,
> host reload design or changes to 4b's gate. Name any missing prerequisite;
> continue independent work past foreign breakage using owned-path fast
> snapshots. Never operate another lane's files/processes or make a worktree.

## 7. What wave 4 must NOT build

- No second registry, compiled schema holder, projection stamp or schema
  compiler. Consume bridge step 3's acquired generation.
- No copy of Datahike branches or whole stores; no `fork-database`, export
  copy, hand-maintained branch roster, batch registry or namespace lease.
- No scheduler, dispatcher, polling retry loop, custom writer queue or
  conflict worker pool. Existing task routing, Flow execution, Datahike
  serialization and operator lifecycle own these responsibilities.
- No new test selector, runner, result store, confidence rule or assumed
  green cache. No shell/platform gate inside an agent runtime merge request.
- No agent-level connection rebinding, shared mutable private roots or JVM
  global Var replacement masquerading as candidate SCI execution.
- No raw datom/EID replay, source-only conflict comparison, result-counter
  merge, deletion tombstone or bypass of final-report validation. **A
  function with live callers is not deletable until its callers are fixed.**
- No landing followed by testing/rollback. No automatic conflicted reapply,
  empty/absent-as-green check, or loss of evidence when a candidate is destroyed.
- No disk writeback, Git export workflow, migration of stored data, default
  reset by an implementation lane, or new presentation clipping. Those are
  separate owners/waves or prohibited mechanisms.

## 8. Design-lane evidence and landing boundary

Read end to end for this assignment: the entire namespace-agents plan;
isolation-merge-writeback, results-reuse-everywhere and step3-carried-projection
research notes; the Malli-native bridge PRD; AGENTS §§1–3; and
`src/seon/program.cljc`, `src/seon/db.clj`, `src/seon/test.clj`,
`src/seon/cluster.clj`, `script/seon/fresh_operator.clj`. The additional
dependency/first-party slices in §2 were read at their actual owners.

Dated source observation: branch `steward-platform`, HEAD
`fe1aaad68bdfa3e491e56439bbab2df9bbe5bad4`; Datahike
`e11845bac78e1241bca0766ddc07d978bd63d74a`; SCI
`fcbd8862800e638dc0f8f5521111f999279cbcd2`. Recent test history includes
`b0b5eedfe` (shared host admission/readers) and `7d27503b0` (fixture and
named-exclusion correction). This is source-grounded design, not a clean
HEAD execution proof. The inherited working tree contains concurrent bridge,
publication, render and test edits, including the cited cluster/fn owners;
they were preserved. Refresh those owners at launch rather than attributing
an unrun failure to their edits.

The revised estimate is **6–10 serial lane-days**, excluding queue time and
orchestrator cold/platform gates. It includes costs omitted from the early
4½–5½-day sketch: identity derivation, candidate SCI test fidelity, schema
dependency selection, durable conflict handling and copied-agent activation.
No timing or test tally in this document is a new measurement.

This design lane writes only this document. It launches no JVM, makes no
prepl evaluation, creates no worktree, changes no source/test/schema, and
operates no cluster or other lane. Verification is document/citation/diff
review plus a path-limited commit. The commands and regressions above are
the future implementation lanes' required proof, not work claimed here.
