---
title: Astra overnight review against the plan
type: review
status: complete
created: 2026-10-12
---

# Astra: overnight work against the plan

Reviewed 2026-10-12, lane `astra-review`. Scope: all **66 commits** returned by `git log --since='2026-10-11 18:00' af1752c04` (local +07), from `ff0492e60` through `af1752c04`; patches, tests, dependency changes and dated evidence. Foreign working edits are excluded. This is a review, not another implementation or test run.

**We are directionally on track, but have not completed the agreed launch/branch/message loop.** The strongest work removes repeated derivation at its producer and repairs measured regressions. The weakest work calls partial integration complete, leaves alternate execution paths, and postpones decisions by adding fallback behavior. Do not advance to W2 on the strength of the fixture chain or “all 125 green.”

Authorities read: [plan §0 and decisions](../../../seon/plan.md), [runtime](../2026-10-11/runtime-follows-db.md), [start hybrid](../2026-10-11/hybrid-start-a-flow.md), [world hybrid](../2026-10-11/hybrid-agent-world.md), [R1](../2026-10-11/r1-db-flow-design.md), both [Opus](../2026-10-11/eid-ranges-opus.md) / [Sol](../2026-10-11/eid-ranges-sol.md) eid assessments, [Q2](../2026-10-11/q2-revisions-decision.md), [benchmark](../2026-10-11/start-a-flow-benchmark.md), [central rounds 1–3](central-round.md), AGENTS.md and the Clojure/testing skills. The dig → independent Opus/Sol plans → Fable hybrid is visibly present for the world design. That is substantial process progress; implementers must now resolve contradictions in those authorities rather than choose silently.

## Build sequence: what actually landed

| Required step | Position at the reviewed commit |
|---|---|
| Combined cold-context regression | Fixed at reach-detail acquisition: 321.632 → 63.244 MB, then 39.083 MB on the refreshed seed. The additional 24 MB is not attributed to this fix. Real work proof, no raised ceiling. |
| H3–H5 | H3 removes parallel identity bookkeeping; H4 shares overlay sources. H5 **option A**, two forks, landed with recorded rationale; original one-fork target remains open. |
| P0–P1 | Partition, store eid allocation and pure squash landed. Immutable retained merge bases fix no-history loss. Superseded identity requirement, allocator retry and copy correctness need attention below. |
| S3 + R1 / H6–H7 | Attempt creation, pure plan, prove-first, successor and roster-rebuild fixtures landed. H7's single assembly is incomplete; cluster pause, joins and stopped-versus-cancelled semantics are not implemented by the full path. |
| P2/P3/P7 + all task IO in its graph | P2 copy/lifecycle and P3 sender landed; **P7 is only a patch**, so installed graph construction has no inbox. P4 only imports rows; task/main indirection remains. Detached work still exists. Whole-task pause proof absent. |
| First chain experiment | **Not done.** Tests exercise synthetic constant proofs and manual plan passes, including a red proof invoking a fixture provider. No Design+Prove → Implement on an actual defect, reviewed merge and measured retrospective. |
| W2 | Correctly not started in this range. Test-run tasks and parallel `d/with` members must follow the working chain, not substitute for it. P5/P6 custody/error work also remains. |

## Findings requiring action

Static findings below are deductions from the named owners at `af1752c04`; they are not claimed as freshly reproduced failures. Recorded measurements are identified separately.

| Priority | Finding, evidence and consequence |
|---|---|
| P0 | **P3 precedes its required P7 consumer.** `seon.cluster.message/send!` now offers to `:seon.agent/inbox`; committed `seon.cluster.agent/graph-definition` and graph creation provide none. [P3 landing](sol-p3-p7.md) explicitly says existing graphs return undeliverable. The passing tests supply a hand-built channel roster. File ownership was respected, but a known broken integration was committed. The world hybrid explicitly orders P3 after P7 and an offer→receipt measurement. |
| P0 | **Copy completion hides unfinished cleanup.** `seon.cluster.agent/unlanded-ends` excludes every attempt named by a `:seon.squash/attempt` transaction. `land-ends!` writes that marker **before** stop/join, connection release, deletion or detached landing. Failure/crash after the copy means the next pass no longer derives those remaining obligations. Its catch records a fault and continues. The proof repeats a fully successful pass; it does not interrupt between those effects. Derive copy and lifecycle work from their actual facts, not one completed-copy marker. |
| P0 | **Manual squash is not generally idempotent.** `seon.cluster.squash/squash-merge` always includes natural-identity collisions with `:add`; `add-count` adds source-minus-base to the writer's current count. `copy!` does not advance a data-copy basis. Two newly born occurrences with the same identity/count 1 yield 2 on first copy and 3 on repetition. The “second copy writes nothing new” claim and test cover only ordinary task values. Even an empty copy writes provenance. Add the collision/repeat proof before trusting occurrence totals. |
| P0 | **Target-sensitive squash decisions happen before the writer.** `copy!` captures target, computes replacements/conflicts, writes conflict tasks separately, then transacts prepared datoms. A root edit between capture and transaction is not included in the three-way decision; cardinality-one replacement can overwrite it. Move the decision into the existing writer using held source/base, as the design method requires. This needs a concurrent-target proof, not a lock or retry. |
| P1 | **Conflicts can outlive their evidence.** `conflict-task!` stores prose naming branch/entity/attribute, discarding the conflict's base/ours/theirs and source commit. `land-ends!` does not retain a successful source because conflicts exist; without an open merge request, deletion/GC can remove the values root is told to requery. The problem task is created `:held`, with no assignment or proof, rather than delivered as root's runnable problem. Preserve the decision's durable references and derive retention from unresolved work. |
| P1 | **Successor fork policy is contradictory and the implementation invents a third behavior.** World hybrid says main after attempt creation, no import; runtime/R1 says predecessor end commit, with joins. `fork-attempt!` sorts all candidate commits and takes only the first; if its source branch is gone, it silently forks main, assuming its program was merged. `plan-facts` collects every historical `:done` need, not just the relevant current attempt; no fork point is persisted on the attempt. Thus restart/deletion/history can change the chosen world. `seed-attempt!` imports rows to repair the symptom. Owner must settle the fork and join semantics first. |
| P1 | **New catches/fallbacks violate NO GUARDS OR FENCES.** `apply-plan!` catches per action and continues even from a failed `:attempt` write to its dependent `:start-flow`; `db<->flow` catches old starts and end processing separately; `land-ends!` catches per end; `detached!` executes inline when the work launcher accepts nothing. The latter adds a second execution path outside the owning agent graph. These are additional continuation mechanisms, not merely the required single entrant error boundary. |
| P1 | **The single launcher and intent semantics are unfinished.** `db<->flow` runs old `agents-to-start` before the task plan; `seon.cluster.host/start!` still starts through `start-branch-flow!` directly. The held-task exemption in `seon.turn/next-agent-work` preserves old fixture behavior. `plan-facts` hardcodes cluster `:running`; `follow!` routes both stop and cancel to `stop-flow!`, whose `stop-graph!` interrupts. Pause only writes an issued-verb flag, not an acknowledgement that all task IO stopped. Renaming these paths did not unify them. |
| P1 | **Allocator implementation changes the promised operation.** Datahike `61bca1ed`, `datahike.writing/with-store-eids`, retries the entire transaction after CAS contention. CAS itself is appropriate authority-owned machinery; re-executing arbitrary transaction functions is an added unbounded retry. The deviation is disclosed in `d281616fd` because increment-and-get changed 32 exact-numbering assertions, but no contention/transaction-function re-entry proof or measured contention cost accompanies it. Its “reopen” test removes a private allocator-map entry while connections stay open; it is not a process restart. |
| P1 | **Two restrictions need removal.** `seon.schema/assert-entity-partition!` still refuses identity-less cluster entities, although P1a explicitly retires that P0 rule. `seon.task/attempt-rows-tx` strips a hand list of task refs (children, needs, faults, creator, function) to import into an older branch; it loses declared task data instead of resolving the world contract. `assign!` can write main before answering `:no-world` for the second write. |
| P1 | **Central verification has its own forbidden recovery.** New `seon.central-round/-main` retries refused/unknown batches once and waits without a deadline; `value` catches every Throwable into a message-only map; `ready?` coerces that map to true. A timeout is not exit. The driver does not itself establish termination before resubmission, and only its final answer is emitted per batch. It also adds direct `pr-str` output outside the value printer and fixture-name regex exclusions. Keep each attempt's unknown evidence and use the actual runner terminal event. |

## Commit-by-commit verdicts

**A** aligned; **P** partial; **D** drift/defect; **E** evidence/design only. “Red→green” below means the committed evidence records both, not a rerun by this review. “No new fence found” applies to A rows; named exclusions, content selection and Malli contract validation are not automatically fences. Process confidence and exceptions follow the table.

| Commit | Slice / verdict | Proof, cost or qualification |
|---|---|---|
| ff0492e60 | E — start hybrid and H tasks | Explicit library composition and priced targets; task declarations/proposed proofs are not implementation proof. |
| 00da22467 | E — H9 attribution | Separates query/config work and expands diagnostic output; does not itself improve runtime. |
| f1b42b836 | E — world dig | Useful writer/reader inventory; marked in progress. Library/ambient claims required verification by the later plans. |
| da356e6c8 | A — H10 contract products | Retains products/references with projection; zero repeated reference walks, real alternate-contract case. |
| b7fdd1685 | E — Q2 decision | Live measurements support commit-native revisions; serialization/write-path clock remains needed at implementation. |
| 4f8e74c94 | E — Q2 publication evidence | Records candidate/task-ingestion gate; does not claim H2 implemented. |
| 619800483 | E — independent Sol world plan | Real world/supply probes; parented movement proposal subsequently rejected because it pins history. |
| 610add01d | E — independent Opus world plan | Real branch/message probes; plain transaction seam informs hybrid; continuous movement subsequently rejected. |
| 885489132 | A — four attribute ranges | Replaces N function pulls with four indexed reads; equivalence and cold acquisition evidence. |
| 6521ded43 | E — world hybrid | Chooses end-only, unparented copy; fork-from-main conflicts with runtime's predecessor policy, still unresolved. |
| c566e0401 | P — H2 revisions in fork | Deletes Seon bridge; real release/reopen proof. Stores resulting revision map outside commit hash, not the specified prior+delta form; raw-store materialization still detached. Explicit non-lane landing. |
| df70fe7c1 | A — authored test selection | Avoids reading unused test bodies at producer; selection is required work reduction. |
| 696a8cf46 | E — owner revision to world | Live database message relay was recorded, then superseded; not hidden implementation drift. |
| b1d30b2bd | E — phase tasks | Keeps task intents in sync with that interim decision. |
| edb8ecebe | E — channels correction | Replaces relay with offers/recipient writes and accepted in-flight loss; no runtime change. |
| 8e6972952 | A — remove self-comparison | Deletes whole-program comparison against the already held base. |
| 40d1416a2 | A — namespace joins | Removes repeated per-namespace reads; data-equivalence/cold evidence. |
| 798047e3c | E — evening order | Explicit dependency order and benchmark-after-each-landing requirement; later P3 ordering violates it. |
| 7d7da5865 | P — partition admission | Necessary schema addition; new identity-only restriction later superseded by P1a but never removed. |
| 663e105cf | E — Opus eid assessment | Finds actual scan/identity corruption beyond supported eid range; recommends squash, prices range alternative. |
| d884d4c96 | E — Sol eid assessment | Independent source/probe evidence; persisted blocks rejected by hybrid, not accidentally implemented. |
| 252107016 | A — visited reach details | Cold context 170.51 ms/321.632 MB → 82.95/63.244; final refreshed-seed sample 47.81/39.083. Bounded visited work. |
| 6989f4407 | A — cluster facts | Schemas declare task/attempt/occurrence movement and additive count; projection assertions inspect produced data. |
| 3c6753c8e | E — combined gate | Records 108 passing assertions and cost evidence; no extra mechanism. |
| 0777f8178 | E — eid/squash decision + red proofs | Owner's store counter replaces blocks; actual range/uniqueness failures specified. Explicitly retires identity-only P0 rule. |
| ee8eb4fae | A — squash vocabulary | Records agreed Git word; small follow-up rather than same-commit rename. |
| 2e06e5d38 | A — H3 identity | Removes listener/LRU identity mechanism; unchanged-program red 358 ms/1,135.8 MB → under 3 ms/3 MB. |
| 3ebf4a6ce | P — H4 sharing | Eliminates 3 duplicate overlays/+157 MB; introduced missing database during interpretation, repaired by e1c424db5. |
| d281616fd | D — P1a allocator | Distinct-id/range proofs and 953 fork tests green; whole-transaction retry is recorded drift, insufficient concurrency proof. Explicit non-lane landing. |
| a7195675a | P — H5 option A | Four forks→two, 25.6/16.6→14.5/9.1 ms/MB. Bound now 25 ms/15 MB; H3 proof prewarms config. Both disclosed, not original one-fork completion. |
| 0b734ee71 | A — S3 attempt data | Identity/numbering and repeated derivation assertions over database values; does not prove concurrent admission. |
| 8648e9661 | P — S3 end/wake facts | End fields/listens introduced; end still written through main path, not full branch-local final transaction. |
| 241b28434 | A — R1 vocabulary sweep | Broad caller/schema/test conversion; +15 net across 93 files, largely mechanical. Existing fences renamed, not eliminated. |
| dc5e1d348 | P — P1 squash / retained bases | Real conflict/ref/retraction/no-history cases; immutable base is justified producer fix. Full copy concurrency/idempotence not proven by pure cases. |
| c15d1eace | P — pure plan | Useful table and determinism assertions; re-running pure plan is not effect idempotence, join data not joined by executor. |
| 990a441cd | D — apply plan/start | Writes attempt+agent first, removes per-branch planning graph; keeps alternate starts, catch/continue and missing-predecessor fallback. |
| a730dfa35 | P — prove first / send-back | Real zero-model-call and target-fact assertions. Removes obsolete pre-assignment proof requirement; retains held-task legacy exemption. |
| 2b49056a8 | P — chain fixture | Demonstrates propagation/rehost with synthetic proofs; “end to end” is fixture scope only. Adds catch/continue around old starts. |
| 57388f28b | A — red proof opens model turn | Fixture provider called once after first red proof; send-back stored. Real transition proof, not a completed work chain. |
| bca39033a | A — connection retirement | Red 10 failures→18 assertions green; observes actual executor exit, released connection and deleted branch. Reuses release owner. |
| 1a729eee9 | A — fixture branch leak | Red/green source under same symbol now independently executes/reuses correct evidence; fixes lifecycle, not cache exemption. |
| cd59c9fa3 | A — instrumentation arm schema | `:guard` is Malli's existing contract arm, not a newly added fence. Tests prepare, store and pull all variants. |
| a997a81ef | E — central round 1 | 125 former greens fail alone, nine causes filed. Attribution explicitly not bisected; bulk green is not source-level proof. |
| e1c424db5 | A — overlay database | Passes held database into namespace interpretation; owner fix for H4 regression, no fallback custody. |
| c7dc2c3b6 | A — acquisition fork owner | Removes duplicate branch/fork-entity construction; seven named tests/43 assertions, explicit foreign-custody fixture repair. |
| e0bd37443 | A — writer projection | Hands writer the prepared projection; 14 raw-store/accounting cases green, other reds compared with parent. |
| 5d7567be1 | D/E — central round 2 + driver | 102 regressions repaired; adds retry/catch/poll/printing mechanism described above. Initial squash-regression attribution later falsified. |
| 7c4f74ab9 | A — reopened projection reuse | Strengthened public entrance red→green; cold fork 8.44→0.74 MB. Honestly leaves squash red. |
| d5912971e | A — named test reach / fixtures | Stops pending work widening named requests; real red→green, removes Var redefinition, repairs injected world. |
| 40e09e9c2 | A — launch fixture arity | Current launcher argument; one Var/six assertions. No production workaround. |
| 9cc893681 | A — changed-attribute squash | Cold 38 MB→4.85 MB, 7.53 ms; 5 MB/10 ms bound unchanged, retraction/temporal cases retained. |
| 607ec78f2 | P — competency attempts | Fixes overlapping repetitions; now serializes **all planned runs**, not just same-task repetitions. Recorded throughput sacrifice; no comparative throughput measurement. |
| 4cd9f79a6 | A/P — actual fork target / fixtures | Real diff failure fixed at owner; order-insensitive parity is appropriate. Projection proof bound 5→10 s for measured 7.287 s: honest but not faster. |
| 418ba8c01 | E — C2 current evidence | Already green at lane entry; correctly attributes resolution to previous work and does not claim a new fix. |
| d02a78686 | A — cost provenance / population | Explicit provenance stops reconcile deleting costs; index reports declared population. 55-Var gate; new catalog field broke retained-builder fixture later fixed. |
| 2319152dc | A — no-history evidence / repeated cost | Data regressions cover replacement/restoration/deletion; removes Var redefinition. Request 9,971→7,714 calls, **no latency/allocation gain established**. |
| aee027806 | E — central round 3 | All original 125 repaired; still 229 red+117 error, new digest failure and cost failures. Task retirement must not imply broad green. |
| a379516d2 | A — retained-builder fixture | Canonical digest producer restores original doc; red→green. Config timeout attributed to source-only classpath, bound unchanged. |
| 1df963d21 | E — digest gate evidence | 13 Vars/77 assertions; distinguishes measured source load from unavailable memory proof. |
| aaf1433eb | P — P2 copy / GC | Plain transaction avoids parent retention; ten task-refinement commits collected. Does not test large evaluation blobs/bytes; repeat-count and concurrent-target holes remain. |
| e91100941 | D/P — P2 lifecycle | Happy-path copy/end/refinement/provenance and keep/error behavior tested; completion marker strands later effects. Chain test enables keep dial to preserve its predecessor. |
| c1e0c97c9 | D/P — P3 sender | Red→green offer/keep/failure data, under 2 ms offer; graph receipt absent. Honest handoff note does not make broken integration acceptable. |
| 7e83184ac | E — mailbox patch correction | Removes proposed double wake; records 35-assertion sender gate and foreign lint boundary. P7 still uninstalled. |
| 4718638bd | D/P — P4 row import | Real missing-row/eid mismatch fixed, 17 tests/141 assertions. Ref-stripping workaround follows unresolved historical-fork choice; local writes still pending. |
| 141da5dfa | E — patch syntax | Corrects hunk counts; not receipt proof. |
| af1752c04 | E — patch context | Makes ordinary patch application possible; still no compiled mailbox integration. |

## Cost and proof trend

| Work | Recorded trend | What it establishes |
|---|---|---|
| Start components | Benchmark warm sum 103.11 ms/130.326 MB → 48.99 ms/42.811 MB after H1; H3/H4 further reduce duplicate cold work | Component benchmark predates this range at baseline; direct `base-ctx`/`fork-cluster-ctx` legs do **not** measure the new integrated attempt launch. |
| Cold base / complete SCI | H9 base 763.86 ms/2,446.279 MB → 130.46/294.781; full SCI still ~550–940 ms/~814–1,841 MB in H3–H5 samples | Base-only improvement is not complete cold-start improvement. “Cold” clears Seon caches inside a JVM; distinguish process-cold. |
| Later cold fork / squash | e0bd37443 fork 17.81 ms/8.44 MB → 6.15/0.739 at C5; squash 42.17/38.94 → 6.75/3.904 | [Cost evidence](astra-cost-handoff.md), [C5](astra-cost-handoff-c5.md): major measured producer fixes. Historical standalone squash was already red at cd59c9fa3. |
| Request / ranking | Ranking 100 costs: 3,270→300 calls, 0.82→0.14 ms. Trivial request: 9,971→7,714 calls; ~62 ms request unchanged | Less repeated work proven; no claimed latency win. Still thousands of calls for trivial work. |
| Central result | 2,346→2,456→2,486 green; final 229 red/117 error; round 3 bulk 61.2 minutes plus reruns | Regression repair is real; broad acceptance is absent. Source-only config ~9.2 s>5; flow world 85 calls>50 and SCI 1,346 ms>1,300 remain red. |

The constant `(is (= 1 1))` proofs are legitimate fixtures for proving **zero model calls and scheduling**, and `(= 1 2)` for exercising the red path. They cannot prove software implementation or stand in for the first chain experiment. Likewise, GC key counts prove the tested storage shape, not arbitrary blobs; successful repeated passes prove convergence only across completed effects, not crash recovery.

Net reviewed Seon diff (excluding dependency internals): **src +2,062/−1,121 = +941; tests +2,140/−571 = +1,569; scripts +169/−3 = +166**. Agent execution alone grows +501 net lines. Growth can be justified, but retained old launch/host machinery is an unfinished deletion commitment, not accretion of a distinct meaning.

## Process and current evidence

The two explicit bypasses are **c566e0401 and d281616fd**: their messages say “Committed outside bin/lane” because a changed Datahike dependency could not be rehearsed in the already running JVM; each cites same-tree `bin/seon check --at` candidate and fork-suite results. That is disclosed positive alternate evidence, **not compliance with the required commit entrance**. They also record pushed fork commits; this history does not establish the owner's push authorization. The dependency-change check must work without requiring future bypasses.

`git commit --only` is the normal final Git command inside `bin/lane`; it is not itself a bypass. Git commits do not retain shell invocations or historical file claims, so I cannot honestly label every untraced commit lane-compliant, attribute every direct `--only`, or certify no checkout restore occurred. No checkout-restore admission was found in the reviewed committed evidence. Landing notes document claimed-path candidate checks and excluded foreign files; current claims cannot prove past exclusivity. The P3 handoff and authorized C3 claim transfer are positive ownership evidence. Benchmark samples are present for major performance landings, not every one of the 66 commits as §0 requests.

This review used only `bin/seon status` and MCP `runtime_status`, no evaluator mutation or reset. Default PID **94708**, start **2026-10-11T21:22:23.023Z**, runs **2319152dc**, not the reviewed P2/P3/P4 HEAD. Readiness has no missing layers, but health is **degraded**: ten hot-untested warnings, one errored evaluation and one unowned namespace. Observed live heap ~0.96–1.03 GB of 4 GB; cache ~462–485 MB of 512 MB. These are observations, not peak/cost proofs; installed validation of later slices is unavailable here.

Review validation: full `clj-kondo --lint src script test --cache false` **0 errors / 1,564 warnings**, 7.401 s (exit 2 for warnings). Foreign in-flight P4 source/schema/tests, dirty http-kit and unrelated scratch files are untouched. The new Markdown file has no reaching program tests or publication input; `bin/lane` explicitly exempts documentation-only commits from JVM checks. Only this claimed doc is committed, using the owner's exact command; no broad suite, worktree, staging sweep, restore or history rewrite.

## Today's five recommendations, in order

1. **Finish one working message/task slice before another feature.** Land P7 with P3's actual callers and all relevant P4 readers/writers; prove recipient persistence→one wake→context, root parity, duplicate receipt and offer→write cost on real agent graphs. Include task IO in that graph and prove pause halts it. Keep W2 waiting.
2. **Make end-copy and cleanup independently derivable at their owners.** Prove interruption after each durable boundary, additive repeat, concurrent root edit, conflict evidence retention and branch GC with real blobs. Move target-sensitive decisions into the writer; delete catch/continue and inline fallback paths rather than wrapping them.
3. **Resolve the successor fork point and joins in the owning specs today.** Choose main-after-reviewed-merge or predecessor/join commits, record that basis once, then remove the missing-branch fallback and lossy imports. Also decide H5 B explicitly; option A is a useful measured intermediate, not an unspoken one-fork completion. Plan §8's “None open” is false while these remain.
4. **Close the execution-model gaps, then run the actual two-step experiment.** Remove alternate starters/held-task exemptions; prove cluster pause, non-interrupting stop, cancelling exit and restart on the same attempt. Use a real defect and red→green implementation, root's reviewed merge, and per-step calls/tokens/errors/send-backs. No autonomous merge expansion.
5. **Restore trustworthy measurement and landing evidence.** Replace the central driver's unknown-outcome retry with the runner's terminal event; repair dependency candidate checking; keep named parent comparisons and source/classpath labels. Re-run integrated start and affected tests centrally, classify remaining reds by producer, and price allocator contention and retained memory before claiming today's system lighter or complete.
