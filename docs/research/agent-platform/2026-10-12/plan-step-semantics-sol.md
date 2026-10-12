---
title: "Sol plan: step semantics and lifecycle"
type: research
status: proposed, independent design round 5
created: 2026-10-12
---

# Step semantics and lifecycle

## Design

Tasks already carry children, needs, instructions and attempt ends; tests already carry definition/reach digests and reports.
Use those values, one task-owned selection of obligations, and root's existing `seon.cluster.agent/db<->flow`.
An agent evaluates, tests and transacts only on its branch. Its completion is a local data derivation followed by
`seon.turn/end-task-call`; the branch's `datahike.core/listen!` route wakes root, which pushes declared facts to main
and derives successor assembly, graph control, problem tasks, message delivery and merge work. No agent starts another
agent or affects main. Completion changes by the task's declared meaning, never by suppressing a failing test.
The smaller alternative, “all tagged tests green and no red against main,” fails F1–F3: a red proof is Prove's product,
main lacks the successor's program, and a test member's custody is not its observation input.

This applies the owner's 2026-10-12 principle, superseding hybrid-agent-world's end-only push and direct inbox offer.
`hybrid-push.md` was absent when read. Authorities: `dig-step-semantics.md` (fe9f5d295), `chain-experiment-1.md`
(19bfdc087, F1–F5), `verify-astra.md` #6/#8/#9, TASK-CHAIN §8.1–§8.2, runtime-follows-db and R1.
The independent Opus plan was not read. Proposed names below require vocabulary entries before implementation.

## Data and one completion meaning per kind

Add `:seon.task/step-kind` on a leaf task, declared when created, immutable for an attempt: `:work`, `:design-prove`,
`:implement`, `:merge`. Existing `:seon.task/kind` classifies its subject and participates in identity; do not repurpose it.
Separate Mine/Design steps use `:work` with their own positive predicates; Prove uses `:design-prove`'s proof-product rule.
No stage entity, mutable current-stage fact, configurable done function or exemptions. A parent remains derived from children.
Publish the named schemas with ingestion, creation, task readers and consumers; existing ordinary tasks acquire `:work`.

| Step kind | Done rule (positive current observations required) | Where checked |
|---|---|---|
| `:work` | Nonempty declared obligations green; no test the change broke remains red. Query answers are validated ordinary facts read by those proofs. | Local test step over its captured candidate and fork basis. |
| `:design-prove` | Own interface/coverage obligations green; declared successor proofs exist; a nonempty set fails for the declared cases and reasons against the pre-change program plus candidate tests and declared new stubs; preservation cases remain green; no other newly broken reached test. | Local test step, comparing candidate evidence with the attempt's immutable fork program. |
| `:implement` | Nonempty behavior obligations all green, and no reached test this attempt broke is red; deletion/conversion obligations are ordinary proofs. | Local candidate versus its fork basis; root repeats integration on the merge branch. |
| `:merge` | Reviewed identity = accepted identity = written identity; positive git-commit evidence and all merge-branch tests green, with resulting proofs green on main. Closing with a reason is an unsuccessful end, never successful Land. | Root's merge world and resulting main; existing merge owner. |

Extend `seon.task/task-edges`'s selected data through proposed `seon.task/step-obligations`, shared by validity,
`seon.turn/task-proofs`, `seon.context/proofs`, completion and merge handoff. Declare named input/output schemas at this
producer. Ordinary tagged tests stay the proofs; product tests are those declared for the successor through its task
links and an explicit proof-product specification on the producing task. This specification names required cases,
test definitions and expected failure observations; no arbitrary predicate or text matching of printed reports.
Store that owning specification as declared components, with refs to living tasks/tests and digests for observed versions.
Red alone, `(is false)`, presence of a test, or a model's “done” cannot satisfy the proof-product obligation.
Existing preservation tests may already be green; do not demand that every product test be red.

The test owner returns one observation value: selected roles, subject/task revision, fork and tested program identities,
test/input/reach digests, evaluator, statuses and native failure reports. Exact equality of the declared failure cases
with observed baseline failures establishes expected red; an undeclared error is a failed obligation.
Use Malli `malli.core/schema`, `malli.core/validator` and `malli.core/explain` on these named owning values.
Completion, context and stage predicates consume this same observation, rather than each rediscovering a test set.

## Fork validity, regressions and F1

Root resolves the actual predecessor end commits before validity. One predecessor means its recorded end commit;
joins mean the decided merge of current completed needs, never lexical-first UUID or every historical done attempt.
No predecessors means the captured main commit. Persist and retain that exact fork point on the attempt.
Read through `seon.cluster.source/commit-database` / Datahike `commit-as-db`, not a moving branch name.
Root assembles the leaf's declaration and attempt facts on that basis; a main control edit must not replace its program.
`seon.cluster.agent/plan-facts` reads obligations there. Its task-quality problem names that basis and failed data;
main's missing proof is irrelevant. Reuse the same held database for validation and assembly, rather than compare two reads.

Regressions compare like evaluators against the fork program, with the candidate tests supplied to that baseline.
That also handles a new test absent at the fork. Baseline testing stays local, in a fork of the agent's environment;
delete `seon.test/change-reds`'s ability to run tests on main from an agent. Root alone tests main integration.
For Design+Prove, expected failing products are a declared output with its own obligation; every other reached result
is preservation evidence. This classification occurs at the test producer, not a reached-red removal at completion.
Missing/stale observations are unknown and cannot establish an obligation. Inherited baseline reds are shown separately;
they do not become failures the attempt caused, and they still stop root's merge if present on its merge branch.

F1: each stage test receives the task revision, tested program and immutable observation database/value as explicit
named inputs, including origin branch/commit. Its execution custody is its own test fork, never the observation origin.
Extend the result-read request with explicit origin; keep branch-native admission, not “accept any branch's latest green.”
`seon.test.runner/latest-results` reads the supplied origin for these observations, while the test's writes remain local.
Include these inputs in test evidence identity; result-dependent stage predicates rerun when those observations change.
Run behavior/preservation tests first, then stage predicates over that completed value, avoiding recursive test execution.

`my.task/current` and `seon.context/current-state` show kind, fork basis, own obligations, expected red products,
and reached tests the attempt broke, each with current status, evaluator, input/program evidence and failure data.
They use one projection; a red symbol without its report is incomplete context. Offer the existing send-back form
with target and counterexample. The value printer alone decides presentation; full observations remain accessible.

## One lifecycle intent table

`seon.task/execution` is the task's explicit control intent; creation defaults to `:held` as current R1 specifies.
Cluster pause suspends launch/resume, not end propagation, delivery, cancellation or cleanup.
Ended attempts take precedence over intents: `:running` does not reopen them. The roster is an observation, not a second intent.

| Intent/fact | No open attempt | Open attempt / live graph | End or consequence, executed by root |
|---|---|---|---|
| `:held` | No attempt or graph. | Pause after current transform; acknowledge by `flow/ping-proc`. | No model turn on a wake; release is an explicit intent edit. |
| `:running` | Once needs done and valid at fork point, assemble one attempt. | Resume paused graph; re-host an open attempt after process restart. | Same attempt identity; no replay of interrupted execution. |
| `:paused` | Assemble eligible valid task paused, without model calls. | `flow/pause`, then bounded acknowledgement. | Keep branch/context; cluster pause has the same suspension effect. |
| `:stopped` | No launch. | Finish current pass, pause/stop and await actual executor exit; release SCI context. | Keep open attempt and branch; later `:running` re-hosts it. Stop does not manufacture success. |
| `:cancelled` | No launch. | Interrupt; owning boundary records cancelled end on attempt branch; await actual exit. | Push end, release, retire branch after retained evidence obligations; no repair task for intentional cancellation. |
| Attempt `:done` | Never reopen it. | Drain/stop and await exit. | Push facts; derive successor from end commit. Implement's local merge-request fact becomes root's work. |
| Attempt `:capped`, `:stalled`, `:error`, `:given-back` | Never automatically launch another. | Stop and await exit. | Push end/reason; create one root repair task by attempt identity; retain recoverable evidence. |
| Attempt `:sent-back` | Source stays ended. | Stop source and await exit. | Push target/counterexample; root records an explicit new-attempt request for target, invalidating downstream completion. |
| Explicit relaunch request | New attempt of same task once prior work has exited. | Retain prior resources until actual exit. | Root consumes one request identity once; never rewrite the old end. |

Root alone writes proposed `:seon.task/relaunch` request facts on main through `my.root/relaunch!`: task ref, previous
attempt ref, reason, selected fork commit and task revision. Repairs may preserve an ended attempt's useful program;
root chooses that basis explicitly. A request does not replenish the subject's budget. Repeated observation creates
the same new attempt; merely editing `:running`, refining a task or receiving a wake does not request relaunch.
Send-back is an explicit authorized request in the source's end data; root materializes its target request, not a retry.

Root's starter establishes the branch/attempt and listen route before SCI acquisition or graph start. A start failure
is translated at that entrant boundary into `:error` on that attempt's branch, with the declared error and failed operation;
the listened end removes that attempt's start work. Do not continue into dependent start actions after failed admission.
Storage failure that prevents the branch/end transaction is a storage boundary failure, reported to root, not invented
success or an endlessly retried start. Cleanup derives independently from end facts plus actual roster/resources.
The lossy Flow error channel cannot be the only copy of an attempt's terminal observation.

Outgoing messages and merge requests are facts on the sender's branch. Root's listened pass executes deliveries and
creates root's merge work; the recipient owns its received fact. This supersedes direct sender-side `offer!`.
Push correctness (source-commit basis, writer-time decision, conflict evidence) belongs to the push owner.
Register branch listeners before deriving pending facts; payload-free coalesced wakes and resume prime are sufficient.

## Deletions by symbol

Delete `seon.cluster.agent/agents-to-start` and its launch loop in `db<->flow`; delete graph starting in
`seon.cluster.host/start!` (retain assembly without graph effects); remove the held-task exemption in
`seon.turn/next-agent-work`. MCP submission needs an execution/world, not a background model graph; autonomous work
declares a task/attempt and receives its graph only from root's plan. Convert fixtures to the same facts.
Delete catch-and-continue in `seon.cluster.agent/apply-plan!`, legacy starts and `land-ends!`; fold dependent
attempt/start actions into the one root assembly boundary. Delete `seon.cluster.agent/detached!`'s inline execution.
Delete `seon.cluster.agent/unlanded-ends`'s squash-marker exclusion when continuous push owns observation.
Remove `seon.test/change-reds`'s main acquisition/run, `seon.turn/record-reached-red!`'s root-execution dependency,
and duplicate selection in `seon.context/task-done?`. Keep named public owners, replacing their bodies in place.
Fork owner deletes `fork-attempt!`'s main fallback and lexical-first choice, `seed-attempt!` and `seon.task/attempt-rows-tx`.

## PHASES: red first, bounded slices

| Phase | Owner slice; red proof to run before implementation |
|---|---|
| 1 | Test/context input owner: F1 row in a stage-proof test reads the exact same red report as agent, despite different execution branch; changed observation invalidates stage evidence. Add to existing coverage, no parallel reader. |
| 2 | Task/test/context owners: declare kind, proof-product value and shared obligations; adapt patch `seon.cluster.db-flow-test/a-prove-steps-red-proof-for-its-successor-is-not-a-reached-red` to assert meaningful expected failure data. Add rows: wrong reason/error cannot complete; an unrelated newly broken companion still prevents completion. Patch's constant equality is only a lifecycle fixture, not production proof relevance. |
| 3 | Fork/plan owner: reuse patch `seon.cluster.db-flow-test/a-successor-whose-proof-its-predecessor-wrote-starts`; successor proof only at predecessor commit, main diverges, predecessor branch deleted. Verify same retained commit validates and forks. |
| 4 | Context owner: F4 row in current-state coverage shows green own proofs plus broken reached tests and native failures; `my.task/current` agrees. Context must show why Implement cannot finish. |
| 5 | Root lifecycle owner: R2 table `seon.cluster.agent-test/each-intent-names-one-roster-state`, held wake opens no model turn, graceful stop completes in-flight pass, cancel awaits actual exit; convert hosted/fixture starters in same slice. |
| 6 | Root boundary owner: `seon.cluster.db-flow-test/a-failed-start-ends-its-attempt-and-the-next-plans` (#6), plus capped/stalled relaunch table: no new attempt without root request, one with it, immutable old end, no overlap. Reuse existing problem-class detection table. |
| 7 | Chain owner: extend `seon.cluster.db-flow-test/a-green-first-step-ends-itself-and-its-end-starts-the-next-from-its-commit` with real expected-red Design+Prove → green Implement → one root merge request; no manual end edits, copying proofs to main or successor launch. Inject provider replies first, paid rerun only when deliberately authorized. |

Order: 1 → 2 → 3/4 → 5/6 → 7, with fork/push dependencies landed before integrated proof. Each listed test is
a planned red proof unless chain-experiment-1 records its actual run; no assertion of green from source inspection.
Each implementation slice names reached vars, compiles/adopts schema consumers, lints and proves through its branch.
Measure task selection O(selected proofs + reached tests), lifecycle O(changed tracked facts + affected attempts),
and fork lookup O(needs); reuse unchanged digest-valid results. Target ordinary calls below one second; record
end→successor, start/exit acknowledgement, transaction time, allocations and cache misses before/after.

## Evidence, risks and unsure

Live default archive 19bfdc087; operator/MCP health degraded: cache discipline, hot untested `seon.profile/adder?`,
and script namespace finding. Scratch SCI `sol-plan-steps` current-state: 22 ms/9.3 MB, request 417 ms.
Installed plan table returned held→pause, running→no action, paused→pause, stopped→stop, cancelled→cancel:
2 ms/1.3 MB, request 88 ms (result/ed26d259b465c). Fresh leaf: running→attempt/start-flow, cluster-paused→none:
1 ms/1.0 MB, request 73.7 ms (result/e620f83e1fb11). Finite let-values; no graph control or source loading.
An initial JVM probe as this agent was refused (root-only); initial scratch labels failed the 12-character contract;
corrected SCI probe used `seon.id/id`. Root read-only chain-proof query returned empty (5 ms, request 54.1 ms):
F1 is supported by recorded experiment/source, not a new reproduction of vanished evidence.

Risks: observation-dependent proofs need input-digest evidence, not green reused only by code reach; test reports
currently retain shown failure values, so exact case observations must originate before printing, never be parsed back.
Tagged successor proofs alone cannot prove relevance; explicit cases plus Implement's send-back and root diff review do.
Retained fork commits and conflict/merge evidence must survive branch deletion. Pause acknowledgement is not exit;
timeout retains ownership. A cancelled branch with unresolved evidence is retired only after those references are retained.
Unsure: concrete schema names for proof-product cases and observation inputs need the registry owner's design;
pre-branch storage refusal cannot produce a branch end and needs the existing storage failure boundary's durable account.
Do not invent another recovery path. This plan leaves those precise representation questions for its first red proofs.
Foreign boundary: shared deps.edn and Datahike/SCI gitlinks have in-flight edits; no source/test change belongs to this lane.
Dependency HEAD pins: core.async dc35f3e0d7, Datahike 61bca1edd6, Malli dd13ff8113, SCI f455b3b4a6;
local Datahike/SCI checkouts differed. Read seams: Datahike `listen!`/`commit-as-db`, Flow pause/ping/stop,
Malli schema/validator/explain. The probes exercise the installed archive, not those foreign edits.
Document-only diff has no reaching runtime tests. `clj-kondo --lint src script test --cache false`: 0 errors,
1,564 existing warnings, 6,552 ms; exit 2 reflects warnings. No source/test lines changed; no isolated JVM needed.
