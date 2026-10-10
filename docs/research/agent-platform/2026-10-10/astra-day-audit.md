---
type: research
title: Independent audit of the 2026-10-10 landings
date: 2026-10-10
status: review delivered; implementation belongs to root
---

# Astra day audit

**The loop is closer to working, but its acceptance and landing guarantees are weaker than its automation plan assumes.** The most valuable changes removed real obstacles: retained merge bases, bounded node caching, refusing red merge members, and running agents' tests in their own interpreter. The next investment should make those guarantees compose. More unattended rounds on the present handoffs will manufacture ambiguous success, expensive refusals and unfinished landings.

## Ranked findings and recommendations

Costs below are estimated engineering effort, including focused regressions, not measured execution budgets. “Unfiled” means no matching failure class found in the task-file search; it does not mean nobody has ever mentioned the subject. Detail and reproduction limits follow the table.

| Rank | Finding and evidence | Smallest useful correction | Estimated cost / task coverage |
|---|---|---|---|
| **1** | **Merge acceptance does not establish that the current target is the program that passed.** `seon.cluster.source/prepare-merge!⟧ remembers expected digests only for the identities it changes. `seon.cluster.source/accept-merge!⟧ checks subsequent program writes on the *merge branch*, while `seon.cluster.source/declarations-held-tx⟧ checks only those changed identities on the target. An unchanged callee or a new reaching test can change on the target between prepare and accept. The writer serializes the writes but does not close this proof gap. | At the writer, fence the target program used by the run; refuse and prepare again when it changed. Start conservatively with program changes since the captured target basis, allowing unrelated operational facts. Optimize to relevant dependency/input evidence only with its own proof. | **2–4 h.** Specific concurrency regression unfiled. The target-tip guard is already intended in [merge spec, section 4](../../../seon/specs/merge.md); the installed implementation lacks it. |
| **2** | **A landing proves paths, not the exact bytes it commits, and its partial failures are not recoverable by the proposed rule.** `0e502a59e⟧'s `seon.cluster.source/commit!⟧ ignores candidate digests and commits whatever those paths hold later. Its test supplies digest placeholders “x” and “y”. Write-back checks all file bases before a sequence of atomic file replacements; it has no exclusion across that sequence and can return after installing only some files. ROOT-AGENT retires/decides before these effects finish. | Give the existing landing owner exclusive ownership of the affected checkout files through write-back and Git; compare accepted candidate bytes, commit tree and parent. Keep durable accepted inputs and completed effects on the existing merge task so retry resumes from evidence. Retire only after required evidence is safe. | **4–8 h in separate slices.** Exact-byte race, partial-install recovery and delayed write-back retention are unfiled failure cases. The general landing task exists. |
| **3** | **“One test system” is a good direction; interchangeable proof meaning is not established.** `e361536a9⟧ routes non-root file tests through SCI, root through compiled resolution. The census executed only 846/2,677 tests and found 205 interpreted-only reds. Several causes were fixed later; fixtures remain a filed semantic failure. `seon.test/select⟧ has no explicit evaluator/fixture-semantics discriminator in evidence reuse. | Keep one selection, result and recording pipeline. Declare the evaluator and initialization inputs as part of what a run proves. Repair per-context fixture/top-level initialization; rerun the relevant interpreted classes. Treat genuine host/platform tests as explicit platform requests, not arbitrary SCI exceptions. | **1–2 days**, beginning with a **2–4 h** fidelity slice. Fixture and most census classes filed; cross-evaluator reuse regression not found. No false-green reuse was reproduced in this read-only audit. |
| **4** | **The loop cannot yet perform its defining data-first task.** `seon.program/changed-identities⟧ still explicitly refuses every changed family except functions, tests and namespaces. A branch schema cannot merge. Plan section 8 also acknowledges there is no lossless task-file write-back. Three small function fixes do not establish an accretive producer/schema/consumer chain. | Before broad backlog rounds, make one real task carry a new named schema, its producer, consumer, proof and task description through the same merge/write-back path. Use the existing schema registration check and Datahike norms; do not add a separate schema landing system. | **1–2 days**, with the smallest additive-schema case first. **Filed:** `seon.cluster.source/a-branch-schema-blocks-diff-and-merge⟧, `seon.test/check-fails-on-agent-registered-schemas⟧; task write-back explicitly missing in plan. |
| **5** | **The bottleneck is repeated program/schema acquisition, and some “bounded” proofs measure too little.** Historical one-test execution: 577 ms / 472 MB on its caller versus a 13 ms / 6.5 MB body; worker allocations extra. `92a572404⟧ legitimately improves its measured caller from 110.5 to 78.8 MB, but that is not whole-run allocation. This audit's nine end summaries cost 1,553 ms / 1,941 MB request allocation on first observation, then 311 ms / 286 MB and 184 ms / 218 MB in measured warm calls. Publication still costs about 26 s/check in today's evidence. | Measure the request and owned workers with the existing cost machinery. Reuse the acquired program-only base and immutable namespace/schema derivations in `seon.cache⟧. Remove repeated whole-program work before expanding rounds or cost-report machinery. Keep initialization private. | **4–8 h** for measured owner fixes, **1–2 days** if namespace initialization needs separation. Existing test-run, merge-cost and current-state-cost tasks cover these costs; whole-run regression scope needs tightening. |
| **6** | **The “one end message” guarantee is a happy-path guarantee.** `a9feb9ba4⟧ sends a newly identified message, then closes admission in another transaction. A crash between them permits duplication. A refused send is recorded as a fault and closure still proceeds, allowing no end message at all. | Record one stable task-end occurrence and its delivery obligation at the writer. Use that identity to make the existing message delivery idempotent; derive pending delivery from those facts. Do not add a second scheduler or queue. | **2–4 h.** The end-message task exists; refused-send, crash-window and concurrent-call regressions were not found. |
| **7** | **Admission and completion still confuse unknown or partial proof with work ready/done.** `7ce008211⟧ now runs assignment proofs, a material improvement, but explicitly assigns when that run errors and attaches `:seon.task/proof-run-error⟧. Missing/incomplete results also fail “all green” and fall through to assignment. At the cutoff, task completion is still proof-only: r2-cost-diff reported done with three reaching reds, and acceptance correctly stopped it. | Require an observed failing task proof for ordinary work admission, with infrastructure failure returned separately. Use one completion predicate over the task proofs and the relevant reaching results; do not terminate or propose on proof-only green. | **1–3 h**, plus existing runner fixes. Reaching-red completion **filed and in flight**; unknown-run admission not separately filed. |
| **8** | **Retirement, historical evidence and health do not yet agree on lifetime.** Initial observation: 36 roster branches, 32 agent branches; 81 agents recorded. The store had 115,282 files, later 120,190, and no completed collection receipt for this root. `seon.context/ended⟧ derives historical calls/proofs from the agent branch; after that branch is unavailable it manufactures zero calls/tokens and omits proofs. Accepted agents' deleted roster branches were still readable during this audit, so the eventual loss was not exercised. | Preserve the small historical outcome needed after retirement, with identities/references and the existing stored-text/blob policy. Return typed unknown if evidence is gone. Then retire actual finished owners and verify a collection receipt and before/after bytes. Rank actionable health by proof/current producer, not old occurrence volume. | **2–4 h** evidence lifetime; **1–2 h** measured cleanup/health triage. Accumulation and stale-fault tasks already exist; end-summary-after-collection failure is not separately filed. |
| **9** | **Archive sealing is a useful accident guard, not archive immutability or an agent sandbox.** `9cd874d94⟧ clears file write bits but leaves parent directories writable and ignores the return of `File.setWritable⟧. Live probe: source file not writable, parent writable. Replace/rename remains possible. `seon.fs/checkout-root?⟧ checks an absolute directory outside data/source, not that it is the authorized Git checkout. | State the actual guarantee. Check sealing success; separate immutable program directories from writable caches; make the landing owner validate the configured checkout and candidate path containment. Preserve the owner's cooperative-agent model. If hostile-code isolation is wanted later, it needs OS authority separation, not another grant flag. | **1–3 h** for accidental-write protection and precise claims. Existing archive task covers relative spit; replacement and wrong-root cases are missing. |
| **10** | **Root is getting useful learning, but is ordering automation ahead of completed guarantees and retaining too many parallel accounts of progress.** ROOT-AGENT stage 3 landed while stage 1's new-namespace gate was blocked. Its retry claim says file conflicts disappear with Claude lanes, which is false for concurrent Seon landings. Only 80/674 live tasks had tagged proofs. Seven ledger claims were visible including this audit; claim count is not running-lane count. | Finish one exact, restart-safe landing, one honest interpreted proof path and one data-first task before tick/restart automation or top-30 triage. Turn the plan's opening into one current dependency list and keep dated rounds as evidence. Assign blocked owner work directly instead of launching a specialist with no editable slice. | **30–60 min** replan; implementation priced above. Process recommendation; no new code framework. |

## Scope, method and limits

Final review cutoff is **977384d089b2c640290006989ca5945486d898a8**. The requested Git date filter selects **134 commits**, starting at `fb98f1752⟧; comparison base is its parent. The opening cutoff was `1724f7078⟧, 131 commits. I also reviewed the three subsequent landings: `ee01a68ad⟧ (script roots/contracts), `13ca24ccd⟧ (task update), and `977384d08⟧ (living-document lint scope). Commits after this cutoff are outside the findings.

**Concurrent follow-through while this report was written:** `56d2efa61⟧ landed the already filed reaching-red completion and stalled-end changes, naming two red-before/green-after proofs; it addresses that portion of finding 7. Do not assign that fix again from this report. Its new implementation was not independently reprobed here. `e7987f477⟧ filed the live-checkout dependency of the new documentation lint; that qualifies the positive assessment of `977384d08⟧ below. Neither is silently included in the cutoff's counts.

Read end to end: AGENTS.md, the plan, FEEDBACK-LOOP, DYNAMIC-CONTEXT, ROOT-AGENT, the five requested research notes, and the merge spec. The boot note is [2026-10-10-boot-source.md](../2026-10-10-boot-source.md). I inspected the day's log/diff and the owners of merge, write-back, Git, task admission, test selection/resolution, task end, context, archive handling and storage. Relevant skills: data-oriented-clojure, REPL, testing and Datahike.

All live evaluations were MCP JVM requests as agent root on default, with explicit connection and no authored transactions, source reload, restart, reset, model call or test run. The MCP entrance itself records each evaluation; that bookkeeping is included in request costs. I did not exercise destructive race/crash cases. “Code-established” below means the missing check or sequence is directly visible; the proposed unsafe interleaving still needs a production-path regression.

**The live program was not always HEAD.** Opening status: PID 21713, start 12:51:45.967 UTC, archive `fd5ce083e⟧. Another owner restarted it during review. Later status: PID 48158, start 13:30:03.736 UTC, archive `13ca24ccd⟧. This audit initiated neither. The final stamped performance form names its archive and basis. Earlier observations spanning this boundary are not presented as controlled before/after comparisons.

Task coverage was checked against all task EDN files (674 parsed entries at the opening census), with targeted rereads of the relevant owners and searches for concurrency, prepared target, candidate digest, proof-run error, evaluator, delivery, retirement and retention. Root should add regressions/tasks from the unfiled cases; this assignment forbids me to edit tasks or code.

## Direction: progress, with an unfinished composition

### What grew and what disappeared

`git diff --shortstat fb98f1752^ 977384d08⟧: **200 files, 10,409 insertions, 2,274 deletions, net +8,135**. This is the final tree difference, not a sum of each commit's churn.

| Area | Added | Deleted | Net |
|---|---:|---:|---:|
| src | 2,342 | 1,016 | **+1,326** |
| test | 2,321 | 549 | **+1,772** |
| script | 107 | 252 | **−145** |
| resources | 949 | 311 | **+638** |
| docs | 4,560 | 69 | **+4,491** |
| AGENTS.md, skills, bin, config, gitlinks | 130 | 77 | **+53** |

Physical source lines grew from **91,683 to 93,009**, with source file count **110 to 109**. These are all tracked src lines, not an estimate of the intended 10,000-line semantic core. Tests grew from 100,632 to 102,404 lines. No new src file was added; `seon.profile.measurement⟧ was removed. Thus “150 commits of new infrastructure” would be a misleading description.

There are approximately **eight distinct new or substantially changed runtime mechanisms**, counting responsibilities rather than functions: fork lineage by t/id; cost-entity sampling/comparison; request/writer/commit accounting; interpreted file-test routing; assignment-time proof runs; task-end proposal/message/closure; archive sealing; and the Git landing owner. Several compose existing owners and replace machinery. This is not a count of eight new subsystems.

Important deletions are real: `d9eb15c05⟧ removes the parent-commit walk and its bound; `d676e9870⟧ removes the old profile-measurement namespace; `554db75c2⟧ removes begin/explain/explain-slow and duplicate health derivation; `78c32f6ff⟧ removes seven unread dials; `e361536a9⟧ deletes the separate interpret-test function; the final script-contract work replaces local contract definitions with registered schema resources. `df87cba96⟧ improves the name-distance algorithm rather than caching its waste.

The owner explicitly accepted temporary growth for correctness and efficiency in plan section 0. I agree with that tradeoff. The failure is not positive net lines. It is adding unattended orchestration before proving that the same accepted program reaches files, Git, later boot and retained evidence.

### What the loop has proved

The plan's later rounds and live end messages establish real progress beyond toy tasks: search snippets/limits and path-form changes were produced by model agents and landed through root. The good result is a small function change with a red proof becoming green on its branch and accepted on a merge branch.

They do **not** establish the whole data-first loop: schema merging is still refused; task EDN write-back is absent; new/unloaded namespaces still caused gate failures; the root agent and automatic landing were not installed at the opening probe. Ordinary root test requests still choose compiled execution. The claim should be “a partially working self-improvement loop, with demonstrated small fixes,” not “the loop is autonomous.”

Square pegs to remove:

- Treating compiled success as interchangeable with SCI success while fixture/initialization semantics differ.
- Calling a path-only Git commit the equivalent of branch evidence.
- Treating every failed proof run as a good work assignment.
- Forcing all historical test tricks through SCI instead of deleting tests of retired machinery or declaring true platform effects.
- Adding a retry to a landing whose durable intermediate effects and retained inputs have not been defined.
- Treating a stored performance number as proof of whole-operation cost when its counter excludes the workers.

## Correctness of the handoffs

### Target changes after prepare

At the cutoff, `prepare-merge!⟧ captures head H, applies candidate declarations to a branch S of H, runs the gate, and returns `:seon.source/expected-digests⟧ from `held-digests H delta⟧. At accept it rereads the target head. `program-written-since?⟧ is applied to S and the gate run's basis. The writer check compares only delta on the target.

Minimal counterexample to prove next:

1. f calls g; the agent changes only f.
2. Prepare f against target g version 1 and record green.
3. Another accepted change replaces g with version 2, leaving f's target digest unchanged.
4. Accept the first proposal. Its changed-identity fence still holds; S is unchanged; the tested composition was f-new/g-old, whereas the target becomes f-new/g-new.

This is a semantic conflict without a same-identity conflict. The existing tests cover changed carried declarations and writes to the tested branch, not this case. A new target test added after prepare is the same missing-input class. Do not fix it with a global evaluator lock: put the decision at the writer and reprepare only when the relevant program authority moved.

The merge spec already calls for a target-tip guard. The smaller first implementation can conservatively invalidate on any target program change since prepare while permitting messages/cost facts. A precise later implementation can fence the test selection and transitive program/input evidence. A full transaction-basis fence is safe but needlessly rejects unrelated nonprogram traffic.

### Files and Git

The existing source proof is valuable. `seon.cluster.source/proven-file⟧ uses source spans, verifies bases, constructs the candidate, reanalyzes it and compares declarations. This is considerably stronger than text patching by hope.

Its guarantee stops too early. `write-back-commits⟧ checks disk digests before reducing over installations. `install!⟧ atomically replaces each individual file. Two independent landings can both read the old bytes of one shared file and later replace each other's disjoint declarations. If the second installation of a multi-file landing fails, the first is already installed; the error records changed paths but does not roll back the operation. These are visible sequences, not an allegation that today's files were lost.

`commit!⟧ checks other staged paths, runs Git, and compares changed path sets. It never compares `:seon.source/candidate-digest⟧. A later edit of an allowed path is committed under the earlier acceptance's evidence. The implementation also does not verify that the returned head has exactly the captured head as its sole parent. Its uncontended fixture checks that outcome, but the production sequence does not enforce it.

The ROOT-AGENT claim that file conflicts disappear when Claude lanes disappear is false. Seon agents can change different definitions in the same physical file; merging their database entities is not mutual exclusion over disk. The planned root tick and task-ending calls also need a common checkout owner.

Use Git's tree/object and compare-and-swap ref mechanisms where they simplify the exact commit, or retain the present commit command under one explicit checkout owner. Either choice must prove byte equality and parent identity; selecting paths is insufficient. Do not invent a second program registry.

There is already relevant local machinery: `bin/lane⟧ holds `.git/lane-commit.lock⟧ across its final index/commit/verification sequence, documenting the earlier crossed-commit incident `649829332⟧. The new in-JVM `commit!⟧ does not participate in it. Reuse one actual exclusion mechanism while both entrances exist, rather than adding a lock that coordinates only one of them; extend the ownership interval to cover the files whose bytes are being proven.

The recovery contract also needs correction. `write-back!⟧ loads accepted and preceding **commit ids** with `datahike.api/commit-as-db⟧. Moving merge-base calculation to temporal t did not make these old commit objects immortal. A retry after the retention window can lose its inputs. Retain the exact landing inputs until completion, or derive the required historical values through supported retained lineage. One merge task can record durable acceptance, file digests and Git completion; a crash should resume from those facts, not rerun all effects blindly. “First refusal, merge undecided and nothing written” cannot describe a failure that happens after acceptance or the first file move.

### Schema changes

There is no evidence here of silently corrupted schema merges: the current implementation refuses them early. That refusal is safe and very limiting. The explicit family allowlist in `seon.program/changed-identities⟧ contradicts the broader data-driven family classification it already uses. Remove the restriction only with the existing complete stored-data validation and ordered norm semantics, including a target change while validation runs. The next representative chain should add a schema and consumer; another string helper will not exercise this gap.

## One runner, multiple evaluators, honest proof

The isolation intent of `e361536a9⟧ is sound: an ordinary branch agent must not require new code into the shared host JVM. One runner can still own selection, admission, bounds, results, recording and reuse, with the evaluation stage explicitly supplied. That is the one-REPL-path model.

The census in [interpreted-tier.md](interpreted-tier.md) was partial: 846 executed, 290 red, 205 red only under interpretation, 85 also compiled-red. The 600-second run did not classify all 2,677 tests. Its classes included host Var redefinition, missing script namespaces, initialization order, output binding, protocols and proxy support. Later `a582e40e3⟧, `9a23b8ff2⟧, `a740b1e26⟧ and `ee01a68ad⟧ addressed several classes. **205 is not a current red count.**

The outstanding fixture task is more consequential than a missing class: `clojure.test/use-fixtures⟧ wrote fixture metadata on a host namespace while SCI test execution read the SCI namespace's missing metadata. A body can run under the wrong setup and still pass. The agent-world proof then means something different. Top-level initialization must be part of per-context execution semantics; SCI fork does not deep-copy arbitrary mutable objects.

Evidence reuse deserves a specific cross-mode test. `seon.test/select⟧ checks completion, input/reach digests, observed changes and loaded-program evidence; its visible predicate has no evaluator or fixture-initialization identity. `seon.test/run⟧ subsequently selects interpreted files based on root identity. I did not reproduce wrong reuse. The required regression is a test whose two evaluator contexts have deliberately different setup, run against identical declaration/input digests, and a check that one cannot reuse the other's green without an explicit equivalence guarantee.

The assignment and completion predicates must use positive observations:

- `seon.task/assignment-read⟧ refuses proofless tasks and captures the revision. That is good.
- `assign!⟧ runs the proofs and refuses all-green work. That is also good.
- Infrastructure refusal or incomplete proof execution should not become an ordinary ready assignment merely because “all green” is false. Preserve the refusal and repair its owner.
- `seon.context/task-done?⟧ and `seon.turn/task-end⟧ still stop on proof-only green at the cutoff. The filed reaching-red task has exact evidence: r2-cost-diff, merge task 3ea28f83185c, refused gate run 5649676309ec.
- `a5e39470d⟧ correctly fixed the .cljc fallback that had let `d3de85d72⟧ and `a4b7e6261⟧ run a check naming no tests. The earlier branch/gate evidence is separate evidence; it does not make a zero-test fallback meaningful.

## Cost, storage and operational truth

### Cost follows too much unchanged input

The [test-run investigation](astra-test-run-cost.md) falsified the easy fixture-cache explanation: `with-database⟧ was called zero times in its reproduction. It measured 4,633 host-Var copies, repeated overlay installation and 483 logical pulls during namespace resolution. The body was tiny. The right producer fixes reuse acquired program state and namespace derivations; another fixture database cache would be machinery around the wrong cause.

`92a572404⟧ is useful: its Datahike revision reasoning and reuse of the program-only base remove repeated work. Its named regression deliberately measures the second warm caller-thread run under 100 MB. The title/task must not be treated as proof that total interpreted execution now meets that budget. Worker resolution/body allocation is outside that counter. The earlier investigation states this limitation clearly; the completion claim should preserve it.

Merge measurements in today's notes are still far larger than their small deltas: 1,406 ms and 1,445 MB for a one-function prepare in the test-cost study; chain/round notes also record multi-second preparations and gigabyte allocation. These are historical measurements at their stated archives, not fresh estimates of cutoff code. The latest plan addition records roughly 26 s publication/check. Boot-source evidence separates unchanged publication, a one-file change and dependency work; it points to whole-schema derivation, not a reason to build a second publication path.

This audit adds a live root-context case. Nine `seon.context/ended⟧ values:

| Observation | Operation / request time | Allocation scope |
|---|---|---|
| First observed, `result/e399e2b733b95⟧ | evaluation 1,553 ms; request 1,739.2 ms | request 1,941.4 MB; entrance 1,997.1 MB; JVM window 3,268.7 MB includes unrelated work |
| Measured repeat, `result/e5c444ccecb0e⟧ | call 311.43 ms; request 466.8 ms | call 285,510,952 bytes, calling thread |
| Archive-stamped repeat, `result/ed066a432d13b⟧ | call 184.33 ms; request 307.7 ms | call 217,607,008 bytes, calling thread; archive 13ca24ccd |

The first two straddle an externally changing runtime and are not a controlled optimization comparison. The warm profile named `seon.db/vector-query-call⟧ (117 calls, 69 ms self), `seon.db/declaration-content-key⟧ (9, 31 ms), `seon.schema/range-differences⟧ (16, 28 ms), `seon.db/branch-database⟧ (18, 25 ms), plus retained-population and contract-reference work. Self/call counters are JVM-window observations and may include concurrent activity. This is evidence for the already filed `seon.context/one-agents-current-state-costs-hundreds-of-megabytes⟧, not a new performance task class.

Do not optimize away meaningful evidence. Reuse immutable derivations under their actual input identity; reuse the held branch value within one summary; query only the needed one-hop evidence. Malli already caches compiled validators. Datahike already exposes attribute revision changes. SCI already forks contexts. The job is to compose those seams without sharing mutable initialization.

### Store and branch lifetime

Opening observations: 36 roster branches (32 named agent branches plus cluster/checkpoint/source/store branches), 81 agent entities, 33 agents whose recorded branch is in the roster including root. Named holders included old chain agents, research agents and seven sci-tests variants. A roster entry is not proof of a leak: some remain intentionally available for reflection or unresolved work.

Operator file count changed from **115,282 to 120,190** over the observation interval. A later `du -sk data/store⟧ returned **741,912 KiB**, about **724.5 MiB allocated disk blocks**. There is no whole-day before/after byte pair, and concurrent publication/check work prevents attribution to model branches. Do not multiply branch count by store size: Datahike branches share immutable indexes.

Both status observations returned a typed absence of a completed maintenance collection receipt for this managed root. That makes reclaimed-byte effectiveness unknown for this run, even though the plan documents yesterday's successful collection. First obtain a receipt and baseline, then measure branch retirement and collection independently. No cleanup was performed by this audit.

There is a lifetime conflict in the new end render: it reads the agent's branch *now*, although it describes a completed historical event. When `branch-database⟧ is absent, `attempts⟧ is nil; `count⟧ and the token reductions answer zero. That is false precision. Live summaries still found three accepted agents' branch data even though those branches were absent from the roster; storage/cache availability before collection is not a retention promise. Test after actual collection, not immediately after unlinking.

### Faults and inbox

Initial MCP health was **faulted**: 9 open faults, 52 errored receipts, 3 failed tests and 13 agent-error signatures. The initial CLI's sampled health was unknown while its command banner said “SEON status OK”; that banner is command success, not positive cluster health. Later CLI health was faulted with 10 open faults, 53 errored receipts and 1 failed test. These observations are at different bases/processes and must not be subtracted as a fix rate.

The root inbox contained 27 messages, 9 carrying task ends. Fourteen lacked received-tx; that is **not** a claim that fourteen were unread by context, which uses its own shown/handled evidence. The six merge tasks split three accepted/three undecided in the live census. Runtime summaries for r2/r3 show the actual work: for example r2-problems-prose capped at 15 calls/143,123 prompt tokens; r2-cost-diff said done at 13 calls/143,458 prompt tokens despite the reaching-red failure.

Existing tasks already cover repeated unrelated faults, stale fault resolution, hosted branch retirement and finished-agent accumulation. The remedy is current producer/proof evidence and once-per-occurrence delivery, not clearing the indicators until the dashboard looks healthy. One of this reviewer's exploratory forms mistakenly resolved a nonexistent namespace and produced an ordinary agent error; the report does not count that mistake as a product defect.

## Ten consequential landings reviewed

“Correct” rates the stated change at its actual scope, not every property of the subsystem. These verdicts use cutoff source, the commit diff, named stored evidence in the landing and the read-only probes above. I did not rerun those tests against default.

| Commit | Claim checked / verdict | Evidence and limit |
|---|---|---|
| **aa7ffb17f** | Fork identity/target, inherited base and safe branch-name reuse — **correct** | `branch-base-t⟧ consults the matching fork id; nested forks inherit the target base; merge base uses the branch's own history. It repairs the incomplete lineage in `d9eb15c05⟧. The retained-t approach eliminates the collected-parent failure. Separate write-back/unloaded-namespace reds were declared, not hidden. Old accepted commit ids used by write-back remain a different lifetime problem. |
| **12473ca94** | Byte-bounded Datahike node cache active — **correct** | Store construction passes the byte budget rather than Seon's old custom retention. Live config had `:store-cache-bytes 512000000⟧ and history enabled. The landing reports warm node loads 70→0; plan records the large acquisition win. I verified installed configuration, not a new controlled node-load run. |
| **c5ca9fd93** | Writer/commit costs actually reach the reply — **correct** | `seon.db/writer-cost⟧ is merged into the reply counts; this closes the missing wiring after `b8e9dfbb7⟧. The request-cost proof raises accounted window share to 0.9. Live replies expose explicit request/entrance/shared/window scopes. Window share is an accounting check, not permission to attribute all JVM work to one agent. |
| **b37a3ff72** | Like-for-like cold/warm and interpreted/compiled cost comparison — **partial** | The scope-separated sample shape and matching-half comparison are in the owners, and the cited false x300/x104/x4 comparisons have targeted proofs. The later filed unchanged-interpreted-definition defect and r2-cost-diff show presentation still claims differences for unchanged work. This is an improvement, not a complete cost oracle. |
| **9cd874d94** | Committed archive is read-only — **partial** | `seal-archive!⟧ clears regular-file write bits, and the live source-file probe confirms this. Parent directory remains writable; replacement is not prevented and sealing failure is unchecked. It supports the specific accidental relative-spit claim, not the broader “cannot overwrite its own program” statement. |
| **b2f1b8b29** | Any nongreen member of the prepared run refuses acceptance — **correct** | `red-test-verdicts⟧ examines all run members, including covered-by evidence; `red-test-refusal⟧ distinguishes red-on-target. The source test asserts unchanged target head on refusal. The real r2-cost-diff refusal demonstrates the gate doing useful work. This does not solve finding 1's moving-target dependency gap. |
| **e361536a9** | Non-root requires/tests avoid shared JVM loading; root remains compiled — **partial** | The guard and interpreted resolution route exist; the commit names 11 green targeted tests/50 assertions. It fixes the isolation cause for unloaded namespaces. The later census and fixture defect refute a broader claim of compiled-equivalent execution. Subsequent order/protocol/output/script fixes are necessary follow-through. |
| **a9feb9ba4** | One end message, green end names its merge request — **partial** | Nine real inbox entries and derived summaries confirm delivery/proposal behavior. The fixture test proves one normal end and no repeat on ordinary wake. `end-task!⟧'s send-then-close sequence does not prove once-only delivery under crash, refusal or overlapping calls. |
| **7ce008211** | Assignment runs proofs in the agent's world — **partial** | `proof-run⟧ acquires the assigned branch and calls the ordinary runner. The landing names two red-before/green-after tests and live 769 ms/503 MB evidence. It deliberately accepts failed proof execution with an error attachment, so the stronger “only proven red work is assigned” requirement remains unmet. |
| **0e502a59e** | One Git commit of the written paths — **partial** | A disposable-Git test has nine assertions for path ownership and the happy-path parent. Other staged paths refuse. The implementation never checks accepted file digests and can commit intervening content under stale evidence. Stage 1 was explicitly not landed because its gate failed. This is a useful primitive, not a proven landing. |

Other consequential work was examined rather than silently excluded: `92a572404⟧'s meaningful but narrower cost improvement; `a5e39470d⟧'s zero-test fallback fix; the source-order, SCI protocol/output fixes; and `ee01a68ad⟧'s script roots with proper named contracts. `977384d08⟧ limits current-pin policy to living documentation and uses the checkout for repository evidence, a sensible correction to treating dated research as current authority. No selected commit merits “wrong” for its entire narrow purpose; several broader success interpretations do.

## Better order for root

Keep the owner rulings: Seon agents should run the loop; root should not require a human permission round for ordinary work; the database remains authoritative; disk is lossless write-back; measurements remain always available. Reorder the implementation so those choices become true.

1. **Close the current landing slice.** Fix the prepared-target fence; make file/Git installation exact and recoverable; finish the unloaded-namespace gate. Prove same-file concurrent changes, a target callee changed during prepare, partial file failure, Git failure and retry after the retention interval. These belong to existing owners and existing merge tasks.
2. **Finish proof semantics before broader dispatch.** Assignment must distinguish observed failure from unavailable execution; task end must include reaching reds; fixture initialization must run in the proper SCI context. Keep result recording shared, evaluator inputs explicit. Rerun the interpreted census classes touched by today's fixes; do not quote the old 205 as the new baseline.
3. **Remove the measured repeated work.** Reuse acquired program-only state and immutable derivations; preserve per-context initialization. Tighten the whole-run cost proof. Bring end-summary cost into this work, because a model root will pay it repeatedly.
4. **Run one data-first vertical task.** New named schema plus producer/consumer, task and proof, merge, write-back, Git and subsequent adoption. This is a stronger goal test than another cosmetic source sweep.
5. **Then add ROOT-AGENT's unattended launch/tick/restart stages.** Use existing Flow/schedule owners. The tick should derive pending work and durable partial landings, not add status mirrors. A restart must not discard output/evidence needed to diagnose failure; the card's detached process with discarded output needs an observed terminal outcome via the operator's existing status facts.
6. **Triage a small ready frontier, not a large prose backlog.** Give the next few tasks current red proofs and typed references. Only expand parallel rounds when the previous changes landed, evidence is retained, and costs are known. Preserve the daily usage report; unrestricted spend is not an instruction to spend on work whose prerequisite gate is broken.

Drop or revise these proposed rules now:

- Delete “write-back conflicts exist only while Claude lanes edit files.” Keep one checkout owner after those lanes disappear.
- Replace “a failure leaves nothing written” with accurate per-effect durable outcomes.
- Do not let the root-decision task “accept” red-on-target through a gate that always refuses it. Root must resolve the test/change or the owner must specify a distinct, reviewed policy. Do not quietly bypass the all-green law.
- Do not add a permanent second test runner or separate per-lane cache to escape SCI problems.
- Do not implement the entire root control surface before the source landing primitive is proven.
- Do not fold the full 600-task backlog into an automated schedule. Most entries are not yet executable assignments.

### Dependency seams supporting the smaller design

Pinned revisions at the reviewed tree:

| Library | Revision | Seam, guarantee and use |
|---|---|---|
| Datahike | cb3bce514507ecc96d36b6957ad10f6e892f51cf | `datahike.versioning/merge!⟧ accepts caller-supplied merge transaction data; the writer's expected-basis mechanism guards authority. It does not prove semantic compatibility for Seon. `datahike.db/advance-cache-context⟧ changes attribute revisions for touched data; reuse immutable derivations by those inputs. `datahike.gc/reachable-in-branch⟧ preserves heads/temporal roots, not every old commit forever. |
| SCI | f455b3b4a6a4fe92cd2adde2e19821ab1fe5bece | `sci.core/fork⟧ forks context environment state. Reuse analyzed/program-only structure; initialize mutable objects per receiving context. Fork is not arbitrary object deep-copy. |
| Malli | dd13ff81139159778edb0a67b491eccc0baa991d | `malli.core/validator⟧ caches a validator on compiled cached schemas; `malli.core/-instrument⟧ owns input/output/guard checks. Recompile when declared schema inputs change, not per unrelated fact. |
| Konserve | e4334756413917305968333bf305e658c177765d | Retained immutable bytes and collection belong to this store through Datahike. Reuse existing blobs/commit references for landing evidence; add no filesystem evidence store. |

These are seams read in the pinned dependency sources and Seon's callers, not claims that an unmodified upstream library already implements Seon's entire landing.

## Process assessment

Root did several things well: the plan records failed rounds and costs, independent research falsified comfortable explanations, the node-cache fix uses the dependency, ownership prevented overlapping edits, the stricter merge gate stopped a real bad landing, and zero-test fallback was fixed once observed.

The operational problem is **too much progress credited at intermediate steps**:

- ROOT-AGENT's Git primitive landed while the one-call landing's gate was still broken. Label it an available primitive, not a completed stage of autonomy.
- The original test-cost investigation stopped at held files; its useful diagnosis waited for owner integration. A performance agent whose assignment forbids the only relevant files is a research handoff, not an implementation lane. Price and name it accordingly; root should give its measured patch to the existing owner or transfer the whole slice.
- At the opening ledger, six other claims existed: assign-proofs (22 paths), astra-one-system (1), astra-review-merge-base (1), merge-gate-unloaded (9, later 10), root-report (1), script-roots (9, later 4). This audit makes seven. Some are old research claims; I cannot infer active concurrency or elapsed stall time from that ledger. The correction is claim lifecycle and bounded ownership, not an invented lane-count cap.
- The plan opening still contains earlier “no real model green”/next-chain statements while later sections record landings and later rounds. An agent reading only the opening can take the wrong next action. Keep one current dependency list; dated round accounts remain evidence.
- Repeated rediscovery has concrete examples: source-order/friction already recorded before the later chains; .cljc fallback affected two landings before being fixed; round 2's source-text census could not observe branch edits; round 3 hit shared-file interference. Each should change an entrance rule or the existing proof, not require the next model to rediscover it.
- The REPL skill's proof-only acceptance guidance was stale relative to `b2f1b8b29⟧. A living skill that contradicts the gate imposes needless debugging calls. Update such claims with their owner change; the audit does not edit them.

Top-level tmp inventory was **379 entries: 263 directories and 116 files**. Names include root-land-stage1, merge-path, test-feedback-perf, astra-perf-followup, multiple chain/loop/test investigations and loose patches. This is evidence of accumulated work artifacts, not proof every directory is abandoned. The shared tree also had an untracked path containing a newline and several foreign edits. Do not sweep by age or by name.

Root should require each closed lane to leave one committed evidence link and one explicit disposition for every owned scratch root: reusable check promoted, evidence preserved, or disposable root removed after actual-holder inspection. Keep ongoing foreign roots untouched. A tmp count is a hygiene signal; the more serious defect is a task referring to evidence that will not survive cleanup.

## Reproducible read evidence and landing

All forms below use `eval_clj⟧ with `{:agent "root" :cluster "default" :mode "jvm"}⟧. Values are summarized without dropping a failure relevant to the verdict. Recorded result handles identify the original envelopes; the forms and key values here survive their runtime handles.

### Installed world and branch census

```clojure
(let [c (seon.cluster.boot/connection "default")
      d (seon.db/db c)
      branches (datahike.api/branches c)
      agents (seon.db/q
              '[:find ?id ?b :where [?a :seon.agent/id ?id]
                                    [?a :seon.agent/branch ?b]] d)]
  {:basis (seon.db/basis-t d)
   :archive (System/getProperty "user.dir")
   :branches branches
   :held-agent-branches (vec (sort-by first (filter #(branches (second %)) agents)))
   :node-cache-config (select-keys (:config d)
                                  [:store-cache-bytes :store-cache-size :keep-history?])})
```

Components of this form were observed in `result/e3d7f40ca430e⟧ and `result/ea4e85e7a816d⟧: basis 536871533, archive fd5ce083e in the former; 36 branches; the latter's config was 512000000/1000/true. Their requests cost respectively 99.1 ms/58.5 MB and 100.0 ms/59.2 MB. Safe namespace resolution found `land!⟧ and `commit!⟧ absent in the opening live archive and no loaded root tick; that is live installation evidence, not a claim those functions were all absent from Git.

The corrected task census used `:seon.task/tasks⟧ to find tagged proofs: **674 tasks, 80 with proof tags, no tag naming a nonexistent task**; kinds 606 function, 57 namespace, 5 schema, 6 merge. `result/ee226f6912562⟧: evaluation 19 ms, request 95.1 ms/15.9 MB. An earlier attempt queried the wrong attribute `:seon/tasks⟧ and was refused; its derived counts were discarded. The count means “has a tagged proof,” not “currently has a valid red proof.”

The successful census form was:

```clojure
(let [d (seon.db/db (seon.cluster.boot/connection "default"))
      tasks (set (seon.db/q
                   '[:find [?s ...] :where [_ :seon.task/sym ?s]] d))
      tagged (set (seon.db/q
                    '[:find [?s ...] :where [?t :seon.test/sym _]
                                           [?t :seon.task/tasks ?s]] d))]
  {:tasks (count tasks)
   :tasks-with-tagged-proofs (count (clojure.set/intersection tasks tagged))
   :tagged-without-task (vec (sort (clojure.set/difference tagged tasks)))
   :task-kind-counts
   (seon.db/q '[:find ?kind (count ?e) :where [?e :seon.task/kind ?kind]] d)
   :merge-outcomes
   (seon.db/q '[:find ?out (count ?e) :where [?e :seon.task/kind :merge]
                                           [?e :seon.task/outcome ?out]] d)
   :root-message-kinds
   (frequencies
     (map #(select-keys % [:seon.task/end :seon.task/merge])
       (seon.db/q
         '[:find [(pull ?m [:seon.task/end :seon.task/merge]) ...]
           :where [?a :seon.agent/id "root"] [?m :seon.message/to ?a]] d)))})
```

### Root's actual end summaries

```clojure
(let [d (seon.db/db (seon.cluster.boot/connection "default"))
      inbox (seon.cluster.message/inbox d "root")]
  (mapv
    (fn [entry]
      (let [ended (seon.context/ended d entry)]
        {:from (:my.message/from entry)
         :end (:seon.task/end entry)
         :merge (:seon.task/merge entry)
         :summary
         (select-keys ended
           [:seon.task/sym :seon.task/calls :seon.task/prompt-tokens
            :seon.task/completion-tokens :seon.task/proof-counts
            :seon.error/type :seon.error/message])}))
    (filter :seon.task/end inbox)))
```

Exact submitted form; `result/e399e2b733b95⟧ returned nine entries:

| Agent | End | Calls | Prompt / completion tokens | Proof counts |
|---|---|---:|---:|---|
| chain5-search-1 | done | 4 | 13,696 / 328 | green 1 |
| r1-search-limit | done | 2 | 5,182 / 661 | green 1 |
| r1-path-form | done | 5 | 18,249 / 854 | green 1 |
| r2-ref-section | capped | 15 | 92,072 / 1,293 | red 1 |
| r2-problems-prose | capped | 15 | 143,123 / 1,453 | red 1 |
| r2-cost-diff | done | 13 | 143,458 / 6,380 | green 1 |
| r3-cost-diff | capped | 15 | 111,266 / 1,283 | red 3, green 1 |
| r3-problems-prose | done | 10 | 62,733 / 3,069 | green 1 |
| r3-ref-section | done | 12 | 120,092 / 3,829 | green 2 |

These are rendered branch observations, not an assertion that all done tasks were accepted or persisted to Git.

### Archive-stamped cost form

```clojure
(let [d (seon.db/db (seon.cluster.boot/connection "default"))
      items (vec (filter :seon.task/end (seon.cluster.message/inbox d "root")))
      m (seon.profile/measure (fn [] (mapv #(seon.context/ended d %) items)))]
  {:archive (System/getProperty "user.dir")
   :basis (seon.db/basis-t d)
   :end-messages (count items)
   :measurement
   (select-keys m [:seon.profile/ms :seon.profile/alloc-bytes
                   :seon.profile/alloc-scope :seon.profile/calls
                   :seon.profile/reads])})
```

Exact submitted form; `result/ed066a432d13b⟧: archive 13ca24ccd, basis 536871545, 9 messages, 184.33 ms, 217607008 allocated bytes, thread scope, 19117 calls, 157 reads. The whole request was 307.7 ms/224.1 MB; its JVM window was 341.5 MB and explicitly included unrelated work.

The preceding measured form selected `:seon.profile/ranked-by-self⟧ as well, giving the owner counts in the cost section. Neither form authorizes a claim about worker-inclusive test allocation.

### Verification and foreign boundary

Only this Markdown report is in the lane's write scope. No source, task, plan, skill or other lane's file was edited. No subagent was launched and no other lane was messaged. The report is evidence, not a registered regression or implementation.

Required global command executed: `clj-kondo --lint src script test --cache false⟧, **8,044 ms, 0 errors, 1,485 warnings**, exit 2 because warnings remain. This is not “zero findings.” Existing source/tests include warnings and known reds; the ten reviewed commits' historical checks are not fresh passes. The report changes no executable input and has no reaching test Var. The installed `bin/lane⟧ documentation-only path explicitly performs no publication/check JVM for docs without `--test⟧; the user's requested report commit uses that path.

Foreign boundary at review: source/context/test/turn/database and merge files, schema/task resources and script documentation checks had other owners' in-flight edits. Their state was not loaded or repaired. Findings use committed source at the stated cutoff, except explicit observations of the live archive and ledger. Root's centralized integration and fresh interpreted census remain outstanding; unavailable evidence is not a pass.
