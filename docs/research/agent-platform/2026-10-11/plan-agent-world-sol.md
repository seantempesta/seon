---
title: "Sol plan: what an agent's world holds and where its writes go"
type: research
status: independent plan, design round 2
basis: dig f1b42b836; hybrid-start-a-flow.md; runtime-follows-db.md; owner directions in this round
---

# Design

An agent has one custody, its branch connection, built by `seon.turn.world/world`; root has exactly the same
world with main as that connection. Every agent operation evaluates and writes locally inside its Flow, including
task refinements, messages, attempt ends and test evidence. Functions declare `:seon.db/connection`,
`:seon.db/database-value`, `:seon.db/writer`, agent/task identity and other existing world-member names;
none declares a main connection. The cluster's existing db<->flow proc holds explicit source/target connections
and moves committed entity changes under rules declared on entity schemas. It uses `seon.db/transact!` with
`:parents` for Datahike merge commits, preserving lineage while selecting only the declared data. Program acceptance
continues through root's reviewed merge. Start remains `seon.flow/start-flow!`: fork, one world, SCI fork, paused
graph, resume by intent; before first evaluation, the system imports the assigned task and attempt into that branch.
Root, workers and test members differ in the connection/writer/context values supplied, never in operation dispatch.

This supersedes round 1's live-main reads from worker context. Q3 remains true of the authoritative cluster roster:
the agent's admission, branch association and runtime observations belong to main. A worker reads an imported
assignment and maintains branch execution facts, not a second authoritative roster. Importing the task/attempt is
necessary when the program fork point precedes assignment or comes from a predecessor. A branch's inherited roster
facts describe its captured commit; they are not a current directory. Inbound task/control changes arrive as data.

## Fact placement and writers

“Work” below is `:seon.db/connection`; “system target” is an explicit argument held by db<->flow, never agent supply.
Proposed symbols are marked **new**; existing symbols name owners to change, not a claim that the design is installed.

| Fact kind | Lives initially → destination | World member writing initially | Function / movement |
|---|---|---|---|
| Agent identity, admission, branch, observed runtime | main; captured/imported facts are historical on work | root's Work; system target for observations | `seon.cluster.agent/creation-tx`, reconciler/heartbeat |
| Agent namespace, answered basis, evaluations, calls, usage | work; retained there, selected end evidence reaches main | Work | `seon.turn/settle!`; execution attachments separated from roster fields |
| Task declaration, children, refinements | author's work → main; accepted changes → assigned branches | Work | `seon.task/create!`, `my.task/refine!`, `seon.task/adopt-tasks-tx` |
| Assignment / attempt creation, execution intent | root's main → worker branch | root's Work | `seon.task/assign!`, `seon.task/attempt-tx`; db<->flow imports |
| Attempt end, reason, target, end commit, end summary | work → main | Work | `seon.turn/end-task-call`, `seon.context/end-summary-tx` |
| Question answer | work → main with attempt identity | Work | `my.agent/answer!` (target entrance); schema-validated answer |
| Message content, sender, recipient, cause | sender work → main → recipient work; root begins on main | Work | `my.message/send`, `seon.cluster.message/send!`, `send-call` |
| Handling / received observation | recipient work → main; not copied into unrelated branches | Work | `seon.cluster.message/received!`; message identity preserved |
| Test run, member reports, definition/input evidence | test-run agent work → main as observations, not program | Work | `seon.test/run`; member body returns report to its run |
| Test member fixture writes | member's `d/with` value; discarded | injected member writer | `seon.test/bounded-result`, `seon.db/with`; no cross-branch writes |
| Functions, deftests, schemas, analysis | work → root's reviewed program merge → main | Work | `seon.turn/settle!`, `seon.cluster.source/prepare-merge!`, `accept-merge!` |
| Merge request, send-back, problem occurrence | work → main; root works the resulting task on main | Work | `seon.task/request-merge!`, `my.task/send-back!`, entrant error recording |
| Cluster configuration, schedules, roster and branch lifecycle | root's main; imported relevant control only | root's Work / explicit system target | existing configuration, clock and registry owners |
| Live results, SCI private objects, Flow graph | runtime cache / roster, never transported | existing cache/context/Flow members | `seon.cache`, `seon.sci.eval`, `seon.flow` |

## Movement, including conflicts

Retain `:seon.program/partition` with its present meanings. Add **new** schema properties
`:seon.data/movement` and `:seon.data/merge` on entity schemas, not a code-side list of entity kinds.
The first declares directed destinations (`:main`, assigned branch, recipient named by a declared ref), the second
declares ordinary three-way merge or append-only observation semantics. These names enter vocabulary before code.
Projection derivation uses `malli.core/properties` through `seon.schema.internal/entity-properties` and declared
entity entries, as `seon.program/program-attributes` already does. Compile once per projection content.
No policy is inferred from namespaces, a message subject, structural resemblance or an agent id equaling "root".

The proc's input is a declared map of main connection and branch executions, with their fork points and installed
projection. Proposed **new** `seon.cluster.agent/move-data!` takes explicit source connection, target connection,
source commit and rule projection; **new** `seon.cluster.agent/movement-tx` derives transaction input from held
source/base values and the target database supplied by the writer. Keep them in the existing db<->flow owner.
This is one movement mechanism in both directions, not a second message dispatcher or per-kind executor.

1. Listen before deriving. Every committed recording or effect transaction can expose due movement, not just a
   turn end: a worker must be able to send a message then wait. A payload-free wake coalesces commits; it is never
   the transport payload. The listener does not transact. The proc pins the current source commit and derives the
   source delta since the last durable movement basis for this directed pair.
2. Select changed entity identities by their declared rules; expand complete components and required refs.
   Translate cross-branch refs through declared identity attributes, never reuse numeric eids or source tx numbers.
   Existing independent entities are referenced by identity, not overwritten by a copied dependency closure.
   Blob digests refer to the same cluster store; live result handles convey no live object.
3. One target writer operation applies that batch plus its movement basis/provenance. Use
   `seon.db/transact!` with immutable `:parents`; it already routes to Datahike's merge writer and final validation.
   `datahike.versioning/merge!` does not calculate a diff or resolve a conflict: those are this caller's data work.
   Compute target-sensitive decisions in `:db.fn/call` against the writer's database, not by adding a pre-read fence.
4. Persist the source commit processed for that directed pair as an attachment of the existing branch/attempt
   execution facts, in the same target transaction. It is durable progress needed to recover a lost wake, not a
   process cache. Re-derivation after restart compares that basis with the held source head. Equal identities and
   content are already applied; a duplicate wake produces no target transaction or message wake.
5. For a message, main retains the sender's observation, then the same proc imports the complete message into the
   recipient's branch. Only after that durable target transaction may `seon.cluster.wake/route!` wake the recipient.
   The recipient derives its inbox locally. A paused/offline recipient's delivery remains due from retained facts;
   an absent recipient is a durable problem for root, not a silently successful send.

Append-only facts have producer-derived identities (message, attempt, test execution); union identical content.
Different content under one immutable identity is a merge conflict, not last-writer-wins. Mutable task refinements
merge against the last common imported task value: unchanged on target accepts source, unchanged on source keeps
target, equal changes coalesce, different edits to independent declared members compose. Competing edits to the
same member remain unmerged and produce root's existing problem task with source/base/target commit references.
Retractions participate in the same comparison and final owning-value validation. Do not transfer source history's
negative datoms blindly over later target changes. For an end, the attempt owns it; it does not overwrite main's
agent admission or heartbeat. Main derives scheduling from the accepted end as runtime-follows-db specifies.

An unresolved conflict is a durable outcome attached to the movement's changed entity; preserve its source commit
and branch until root resolves it. Other independent entities in the batch can move. Advancing the processed basis
does not mean accepting the conflicted value: its problem task explicitly retains the outstanding merge.
Resolution writes the selected facts through the same owner; no retry loop, lock or exemption is added.
The reverse direction imports only main-origin accepted controls/messages; provenance and directed source ownership
make imported facts inputs rather than new outbound edits. A returned message never becomes a fresh message.

Local send returns a durably authored message; it must not claim the recipient has received it. A caller needing
delivery proof reads the durable destination observation through the same imported facts. Likewise a local end
is durable before the worker stops, while its successor starts only after main holds it. Root's own locally
committed facts are already on main, so only downstream movement remains. No identity-based root branch is needed.

Keep program acceptance's base independent of data-movement lineage. A selective merge having source as a parent
does not mean that source's program was accepted. `seon.cluster.source/compare-branch` must continue comparing
program identities against the explicit attempt fork-point / last accepted program basis, not the latest generic
Git-like common ancestor. This is essential when data moved many times before program review.

## Cost and two-custody comparison

Necessary work is O(changed datoms + selected owned entities/refs), plus indexed identity lookups and one durable
commit per changed target. No whole-program pull, full store copy or scan of every branch per datom. Compile rules
once; obtain a fresh database per operation, hold values through each derivation. Coalesce due commits per target.
A worker→worker message costs three durable commits (author, main, recipient); worker→root needs two, root→worker
two. Main-only root control costs one. Historical ordinary recording need not all be copied to main.
Using round 1's measured raw/checked commit costs (12–25 ms), the estimated extra movement floor is 15–50 ms;
this is an estimate, not a measured transport benchmark. Record per-leg ms, allocation, datoms and writer counts.

Two explicit custodies would be cheaper for messages and shared task edits (one destination write, immediate latest
main read), and easy to supply under distinct schema names. Its map cost is negligible; its drawback is semantic:
the agent still invokes shared platform writes and has two coupled current databases. It contradicts the owner's
direction. One-custody movement buys independently runnable worlds and replayable merge data at the cost of delivery
latency, conflict handling and durable movement progress. Those costs must be proven, not hidden by “git-like.”

## Reused / changed / deleted by symbol

- Reuse `seon.turn.world/world`, `basis`, `with-world`; `seon.batch/world-members`;
  `seon.call-preparation/arm-plan`, `prepare`, `supply-from-world`; `malli.core/-instrument`, `validator`.
- Reuse `seon.db/transact!`, `with`, `identity-attributes`, `seon.schema.internal/entity-properties`;
  `datahike.api/listen`, `since`, `commit-as-db`, `release-materialized-db`, `merge!` and writer serialization.
  Dependency pins read: Datahike cb3bce514507ecc96d36b6957ad10f6e892f51cf; Malli dd13ff81139159778edb0a67b491eccc0baa991d.
- Change `seon.cluster.agent/armer-step` (db<->flow), `seon.cluster.wake/route!`, `seon.cluster.message/send!`,
  `send-call`, `received!`, `seon.task/task-call`, `assignment-read`, `seon.turn/task-end`, `end-message`,
  `end-task!`, `end-task-call`, `seon.context/end-summary-tx`, `current-state`, `seon.turn.world/record-fault`:
  local data operation or explicit system movement, never lookup-and-write-main from an agent call.
- Delete `seon.task/main`, `main-connection`, `written`; remove their callers in the same slice.
  Delete recipient-home connection lookup from `seon.cluster.message/send!`, dual-home read from
  `seon.turn/admission-closed?`, and main indirection in `seon.context/recorded-end-summary`.
- Change `seon.turn.world/settlement-connection`: no worker/main escape; member reports return to the test-run
  owner as data. Change `seon.test/bounded-result`, `run` to the agreed `d/with` members and local run recording.
  Keep round 1's deletions of `seon.cluster.host/host!`, `seon.cluster.agent/arm!` assembly duplication.

## PHASES (red proofs are proposed; no tests or production edits in this plan)

Each phase owns all its files; split listed substeps into separate fifteen-minute implementation tasks.
Use real SCI/Flow and canonical writers, no Var redefinitions. Name each new test in its task before assignment.

| Phase | Owned files | Red deftest + assertion |
|---|---|---|
| A. Declare movement and merge data; publish/adopt it | vocabulary; `resources/seon/schemas/seon.data.edn` (new), affected agent/task/message/test schema files; `src/seon/schema/datahike.clj`; `test/seon/schema/datahike_test.clj` | `seon.schema.datahike-test/entity-movement-is-declared-and-adopted`: installed projection derives the declared directions for a newly admitted entity without changing a routing list |
| B. Local task writes, with authoritative imported assignment | `src/seon/task.clj`, `src/my/task.clj`, `src/seon/context.clj`; `test/seon/task_test.clj` | `seon.task-test/a-refinement-writes-only-the-calling-branch`: branch task intent changes, injected main's held commit remains equal; existing producer conflict proofs stay green |
| C. Local message and end | `src/seon/cluster/message.clj`, `src/seon/turn.clj`, `src/seon/context.clj`; corresponding tests | `seon.cluster.message-test/a-send-is-durable-on-the-sender-before-delivery`: authored message exists only on source; `seon.turn-test/an-end-is-written-on-the-attempt`: end/reason on branch attempt, main unchanged |
| D1. Derive one entity merge at target writer | `src/seon/cluster/agent.clj`; `test/seon/cluster/agent_test.clj` | `seon.cluster.agent-test/data-movement-is-an-identity-aware-three-way-merge`: table asserts independent edits compose, conflicting member retains both referenced proposals, refs resolve by identity, retractions validate |
| D2. Listener + durable directed progress | same owner as D1; `src/seon/cluster/wake.clj`; both test files | `seon.cluster.agent-test/a-lost-movement-wake-loses-no-facts`: restart/prime derives due source head and target facts; `.../imported-data-is-not-exported-again`: two passes change neither head after first convergence |
| D3. Recipient import then wake; lifecycle | same files as D2 | `seon.cluster.agent-test/a-recipient-wakes-only-after-its-message-is-durable`: actual recipient Flow reads message locally, one handling observation after duplicate wake; paused-recipient row becomes readable on resume |
| E. Assembly + root parity | `src/seon/flow.clj`, `src/seon/turn/world.clj`, `src/seon/cluster/agent.clj`; world/agent tests | `seon.cluster.agent-test/a-historical-program-fork-receives-its-live-assignment`: first context sees assigned task/end basis locally; `.../root-and-worker-use-the-same-local-writers`: table changes only injected custody, destination equals that custody |
| F. Test run + error observations | `src/seon/test.clj`, `src/seon/turn/world.clj`, `src/seon/fault.clj`; corresponding tests | `seon.test-test/members-return-reports-without-writing-another-world`: fixture mutations stay in each `d/with`, run evidence lives on run branch; `.../a-test-injects-main-as-data`: movement targets fixture main and real default remains unchanged |
| G. Separate program merge basis, whole loop | `src/seon/cluster/source.clj`; `test/seon/cluster/source_test.clj`, `test/seon/cluster/agent_test.clj` | `seon.cluster.source-test/data-movement-does-not-accept-program-changes`: message moved twice, unreviewed function absent on main and still present in program diff; `seon.cluster.agent-test/a-local-end-starts-its-successor-after-main-movement`: successor forks named end commit with accepted refinement |

Order: A → B/C → D1/D2/D3 → E/F → G. Source merge-base proof in G must accompany first production use of
`:parents` in D2; do not deploy the transport while its lineage would hide program changes. Measure D2/D3 through
`seon.profile/measure` on same parent/change fixture; current recording budget stays <100 ms, movement separately.

## Evidence, risks, where unsure

Default remained running throughout. Both `bin/seon status` and MCP `runtime_status` showed readiness complete,
health `:faulted`, two open faults and two failed tests; these predate this plan. No runtime reset/reload was done.
MCP `eval_clj`, explicit root/default, JVM, let-local probes: world construction + supplied connection/database
returned true/true; missing `:sol.probe/main-connection` returned that unavailable key (2 ms, 0.6 MB;
request 77.2 ms, recording 53.0 ms). Slot was `{:seon.fn.argument/index 0 :seon.call-preparation/key k
:seon.call-preparation/schema-key k :seon.call-preparation/supplier-symbol 'seon.batch/world-members}`;
call `#'seon.call-preparation/supply-from-world` inside `seon.turn.world/with-world`.
`seon.profile/measure` of 100 `world` calls: 1.43 ms / 1,195,456 allocated bytes; 100 calls followed by
`assoc :sol.probe/main-connection conn`: 1.11 ms / 1,202,424 bytes. Both references used the same held connection:
this measures map assembly, not second-branch acquisition or transport. Constructor drops an undeclared input key.
Request: 53.4 ms, evaluation 9 ms / 5.4 MB, recording 25.5 ms. Initial incomplete-slot and delimiter mistakes
returned errors as data; corrected probes above succeeded. No scratch defs, branches or live objects were retained.
Benchmark forms, under the let-local `conn = (seon.cluster.boot/connection "default")` and
`request = {:seon.boot/cluster-name "default" :seon.db/connection conn :seon.agent/id "sol-plan-world-probe"}`:
`(seon.profile/measure (fn [] (dotimes [_ 100] (seon.turn.world/world request))))` and
`(seon.profile/measure (fn [] (dotimes [_ 100] (assoc (seon.turn.world/world request) :sol.probe/main-connection conn))))`.
Full-tree `clj-kondo --lint src script test --cache false`: 7,583 ms, zero errors, 1,513 warnings.
Document-only change has no reaching program tests; `bin/lane` explicitly skips program publication and boot for it.
Foreign boundary: default's existing health findings above; no foreign file was edited or loaded.

Main risks: selective merge lineage mistaken for program acceptance; root/main edits conflicting with worker
refinements; source numeric refs/transaction refs leaking across branches; retaining commits/blobs for unresolved
movement; stopped workers deleted before their ends move; delivered messages generating circular movement.
All have explicit data assertions above. Imported agent roster values must remain labelled as captured observations.
Unsure: the smallest durable directed-progress shape (existing branch vs attempt attachment), how fine task member
merge should be for ordered collections, and recipient-address admission when its directory is older than main.
Prefer authoring the address locally and deciding delivery from main's current roster; missing references require
an admitted address/request shape rather than inventing placeholder agents. Price that schema conversion in A/C.
Transport latency/allocation and crash convergence are not yet measured: source inspection establishes the seams,
not positive proof of this proposed loop. The hybrid should make D1's bounded merge table its first experiment.
