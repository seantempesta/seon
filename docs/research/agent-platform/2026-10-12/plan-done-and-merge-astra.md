---
title: Done from proofs; root merges immutable branches
type: plan
status: proposed; phases A–R unimplemented
agent: plan-astra
date: 2026-10-12
---

# Done from proofs; root merges immutable branches

Today `seon.context/step-status` derives completion, but `seon.turn/end-task!` stores a second answer and asks for a merge; root separately squashes facts and accepts code. Keep the proof reader, the root Flow proc and Datahike's writer. Replace the stored success and chained effects with queries over proof evidence, merge requests, commits and actual proc exits.

The smallest viable design keeps **two git merge shapes, one merge request**: continuous squash merges of trusted facts, and a reviewed merge of program entities. Forcing both into one shape either admits unreviewed code or retains every running agent's history on main. Root's review still applies (dig R-10); automatic completion does not imply automatic approval of code. Root's ordinary `db<->flow` executes approved work without another model call.

This plan uses the supplied [dig](dig-done-and-merge.md), not the other planner's work. Its R-1–R-22 apply in their stated precedence. The shelved `docs/seon/specs/task-steps.md` does not authorize resurrecting manual completion. The older `docs/seon/specs/merge.md` remains the merge authority except where later rulings replace it.

## 1. Data and names

All names below marked **new** are proposals, not installed schemas. Register them at their producer, compile with `malli.core/validator`, and publish with every consumer. Entity schemas declare partitions and attributes; root uses **main's** schema and ownership declarations when interpreting an untrusted branch. A branch cannot grant itself a merge rule by changing its schema. Open maps remain open; relations and required members are validated as whole values.

One proposed Seon word: **assignment**, the existing meaning of `:seon.task/assignment`, for one task given to one agent at one fork. It replaces “attempt”; libraries have no name for this task relationship. “Done”, “given back” and “proof” retain their requested task meanings. No other new concept word is needed.

| Producer → consumers | Stored data and identity | Derived, never stored |
|---|---|---|
| `seon.task/assign!` → context, runner, root proc | Reuse `:seon.task/assignment`, `:seon.task/agent`, `:seon.task/branch`, `:seon.task/revision`. Identity remains `seon.id/id [task-id number]`, allocated by the main writer. Rename the `:seon.task.attempt/*` family to **new** `:seon.task.assignment/{number,fork,reason,target,relaunch,relaunch-fork}` with the same member schemas; rename the entity schema `:seon.task/assignment-row` to **new** `:seon.task/assignment-entity`. The fork is required on every new assignment, including a first task. | The active task and which assignment may run. No numbered counter alongside the assignment facts. |
| Task producer → proof reader | Reuse task identities, `:seon.task/needs`, `:seon.task/children`, `:seon/tasks`, and test symbols/digests. Replace `:seon.task/step` by **new** `:seon.task/schema`, a registered schema key naming **new** `:seon.task/implement`, `:seon.task/prove` or `:seon.task/question`. These compose the task schema and its required data. Retire the old subject `:seon.task/kind` separately with all consumers; do not reuse it with a changed meaning. | `seon.task/obligations` becomes `seon.task/proofs`: current task proofs plus successor proofs, read at explicit database values. “Product” becomes “successor proofs”; “step” becomes “task”. |
| Test runner → task proof reader, root merge | Reuse `:seon.test.run/*`, `:seon.test.member/*`, definition and reach digests, input evidence, `:seon.task/proved`, `:seon.task/red-first`. Run facts say what ran, against which immutable program and inputs; they do not assert done. | `:seon.task/status :done`, broken tests, missing evidence, and merge eligibility. Delete stored mirrors `:seon.agent/broken` and `:seon.agent/new-red` after their last reader converts. |
| Agent's task writer → root | Given back is the presence of `:seon.task.assignment/reason`, optional required-peer `:seon.task.assignment/target`, and **new** `:seon.task.assignment/counterexample` referencing existing stored-value/blob schemas. These are asserted by `my.task/send-back!` on its branch. A missing target means root must choose. | Given back is not success. Delete `:seon.task.attempt/end`, `:seon.task/end` and the authored success variant. Cap/execution failure derives from existing call, error and execution facts. |
| Question task producer; `my.agent/answer!` → proofs, root | **New** `:seon.task/answer-schema` uses `:seon.schema/registry-key`; **new** `:seon.task/answer` is a peer ref to the entity declared by that schema. The schema owns answer identity, members and persistence; use ordinary Datahike data, not an arbitrary `:map` attribute. The answer writer validates the named schema before its single branch transaction. | Answer presence alone never completes a task. Its proofs read the answer. |
| Assignment's branch initialization → root | One **new** `:seon.merge/request` unique identity, `seon.id/id [assignment-id]`; one entity, also root's merge task. Required `:seon.task/of-assignment`, source/target branch names, immutable original merge base. Reuse `:seon.store/branch`, `:seon.source/commit-id`, `:seon.program/identity`, `:seon.task/proved`, `:seon.task/run-id`, `:seon.task/program`. New stored keys at `seon.task`: `:seon.merge/{source-branch,target-branch,merge-base,merge-branch,source-commit,facts-commit,program-commit}`. Commit keys are UUID values, not entity refs. | The request exists throughout the assignment; it is not a completion verb. Contents are database diffs, never an authored list of entities to merge. `source-commit` is root's historical selection of a proven source value, not a mirror of a moving head. |
| `seon.cluster.squash/squash-merge!` → root diff, collector | `facts-commit` on **main's request**, initially the fork, advances in the transaction that merges that partition. `program-commit` separately names the last source program accepted by root. They are two distinct bases for two different operations. Do not advance the common program merge base when only facts merged. | Nothing to merge means an empty diff at that partition's basis. No `:seon.squash/attempt` transaction marker. |
| Root merge → root tools, collector | **New** `:seon.merge/unmerged` component set. Each component carries `:seon.program/identity`, optional attribute, base/ours/theirs **commit ids**, and source definition digests where applicable. Values are queried from retained commits, with absent data represented by the existing `:seon.program/absent` in the view. Reuse `:seon.squash/conflict` for the value-only algorithm. New entity/view schemas `:seon.merge/conflict` and `:seon.merge/request-entity` are produced by the merge owner. | “Conflicted” means unmerged components exist; not a stored status. Resolving a component writes its chosen value and retracts the component in the same merge-branch transaction. |
| Root acceptance/write-back → root, boot, collector | Root's accepted transaction refers to request, source commit, tested merge commit, review and prepared git commit. **New** `:seon.merge/accepted-tx` is a required-peer transaction ref once accepted; `:seon.merge/git-commit` reuses `:seon.git/commit`. The transaction's actual Datahike commit comes from tx-report/lineage, never a guessed self-referential UUID. `:seon.task/reason` closes without merging. | Merged means the accepted source is in main **and** the exact git commit is published; source-only tasks need no git change. `:seon.task/outcome` remains a rendered compatibility value until consumers convert, never a second authoritative flag. |

The merge request's main-only members are root-owned facts. Source request creation cannot supply their authority. Ownership follows the assignment/task/ref relationship declared in the schema at main, not a hand list in the merge implementation. Identity-less components merge through their owning entity, never as independent candidates. Unique identities remap refs once; schema-declared components define the whole value replaced.

Root review is data on the request: **new** `:seon.merge/review` component with source commit, target program/input digests and selected identities (reuse existing identity schemas). Root may select an explicit subset; accepting it closes that source assignment, so discarded identities do not silently become accepted. A later change is a new assignment/request.

## 2. Exactly when work is done

Let F be the assignment's immutable fork, H its current branch value, P the proofs selected by the task declaration at F, and R the tests selected by current dependencies of the complete F→H change. Selection must be complete and recorded even when empty. The proof reader, renamed `seon.context/task-status`, consumes actual runner evidence, not today's reconstructed boolean fork map.

An Implement task is done precisely when:

1. P is nonempty; at least one proof has a completed, terminated **failing assertion or declared unimplemented result at F**, using the same proof definition required by the assignment. Already-green companion proofs are allowed. Unknown, runner failure and timeout do not establish the initial failure.
2. Every proof in P has current green evidence at H: positive assertion count, zero failures/errors, completion and actual termination, matching definition/reach digests and input evidence (`seon.test/green-members`, `seon.test/select`). A deleted, retagged or weakened proof does not erase the assignment's original proof set. Revising that contract is task refinement returned to its producer, not self-certification.
3. Every required reaching test has a current result; no pending test is treated as green. A red already present at F is reported but does not make this assignment's work fail. A red that was green at F, a red on an unobserved F input, or an unexpected newly introduced red prevents done. Read evidence and the same evaluator determine these comparisons. A change reached by no proof remains unproven, per AGENTS.md.
4. The task declaration/answer inputs judged are those H holds. A given-back decision or cancellation ends work by its own facts, not by a fabricated successful result.

This is one predicate used by assignment and completion, not a second merge-time test for an agent omission. Root may create the assignment before baseline execution; its own branch's first runner invocation measures F before any model call. If all proofs are already green, no implementation launches and no successful assignment is claimed: root sees the task already satisfied at that value (R-8). If proofs are missing or cannot run, root gets the actual data, not work masquerading as an implementation. A performance/refactor task needs a failing behavior/cost assertion; an already-green suite alone proves no change.

**Prove** follows R-7: its own proofs, if any, are green; its successor proof set is nonempty, newly introduced since F, and red only by assertion failure or `:seon.error/unimplemented`; no unrelated test was broken. The zero-own-proof form remains valid when a declared successor needs it. This is the specific Prove schema, not an exception in Implement. Its successor forks the exact proven commit, where those tests are already red. An unfinished Prove program never merges to main just because Prove is done; its request supplies facts and its retained commit supplies the successor. Root ultimately reviews the cumulative terminal program.

**Question** uses the Implement proof rule over the absence/incorrect answer at F and the schema-valid answer at H. Its facts merge through the same request; no program delta means no git commit. “Nothing to change” is an answer with its declared reason and proof, not a success flag.

After an eval request records, the existing serial agent proc runs P then R on its branch. It derives done and opens no further model call when done. Tests left pending by the configured test bound remain due work in that same proc, including after `my.turn/wait`; a later eval request is not required to finish them. No additional test proc or scheduler. Test/result writes notify root via the branch listener; root independently reads the same predicate at a held H, requests orderly stop and releases the graph **after actual exit**. The immutable H that root observed is retained and recorded as `source-commit`; historical completion at H survives later main changes. A source change before exit makes root select the later head and evaluate it again. An exited assignment does not reopen; a new assignment carries later work.

Cost: O(|P| + changed dependency edges + selected tests), plus tests whose content/input evidence changed. Reuse F evidence and current evidence by content through `seon.cache`; unrelated main writes do not rerun source tests. A sibling merge does not change what was true at H. It changes the merged value root must prove, including newly reached tests. Thus local done and a red merge branch can coexist honestly.

## 3. Root's merge and its bases

### Continuous facts: a squash transaction at the writer

Root listens before reading and primes `db<->flow` on resume. A wake carries no authoritative payload. The proc reads source heads; the merge transaction function receives the held source **value**, not precomputed tx-data. Inside `:db.fn/call`, it reads main's current request, `facts-commit`, schema and ownership, obtains that retained base, and computes `seon.cluster.squash/squash-merge` against its own mid-transaction database. No model/test/file IO runs in the writer.

Merge the complete trusted fact partition **atomically per source request**. If any member requires manual resolution, record all its conflict references on the request and leave that partition's base and data unchanged. Root resolves the entire partition on the merge branch. This deliberately gives up independent progress of other trusted facts from that same branch during a conflict; other agents continue. It avoids per-attribute bases, losing excluded changes after advancing a global base, and repeated increments from partially merged transactions. Unowned edits and program changes are outside this partition and remain in the reviewed diff from their original base.

On a clean merge, apply the fact delta and advance `facts-commit` to H in **one transaction**, including a delta that only retracts data. A second application of H is the identity operation. An empty source diff may advance the examined basis, but must not fabricate another count or message; an identical H requires no write. The writer is the authority even if two root callers submitted the same H. No caller's captured main value is used as ours. Root resolutions also advance this exact basis atomically; otherwise the next squash would recreate their conflicts.

Keep the existing `:add` rule only for a count whose producer declares source-local contributions. Occurrence identity becomes `seon.id/id [signature producer-digest originating-assignment slot]`, preserving bounded slots and counts at the existing error owner. For an error outside any assignment, the existing process/evaluation identity supplies that origin; absence never groups unrelated sources. An inherited occurrence retains its original identity; a new occurrence on a successor uses that successor's assignment. Root's aggregate is a query summing those contributions, not another mutable count and not “number of assignments”. The advancing base still matters: source count increments after H merge once, and a join cannot recount inherited occurrences. Delete `seon.cluster.squash/add-count` when the occurrence consumer converts, because unique contributions then merge by their value and identity.

### Program: one reviewed Datahike merge

`my.root/merge!` invokes the root proc's existing merge owner; no caller holds a second main connection with an independent sequence of effects. It creates/uses the request's retained merge branch off main, applies three-way program and reviewed fact changes inside that branch's writer, and keeps conflicts as data. Root resolves with ordinary REPL edits. The true program merge base is the common accepted main ancestor, **not** the successor's proof fork F: predecessor program changes are still pending until main accepts them. Carry that ancestry through every successor and join.

Run norms in declared order on the merge branch, then P, the tests reaching the entire final change, and root's selected tests. All selected tests must be current and green; an existing red on main asks root for repair, never an exemption. Branch test claims supplied by an untrusted agent are observations to inspect; root's own runner establishes the accepted evidence. Root's review names the exact source content. Changed content requires a new review observation, not another request identity.

For acceptance, use `seon.db/transact!` with `:parents` (the installed route to `datahike.writer/merge-db!`). The transaction function recomputes the three-way on the **writer's own main value**, resolves the bases from that value, and requires the tested merged program and test-input read evidence to describe the resulting value. This is the writer's definition of accepting a proven value; delete the caller's precomputed replacement and `declarations-held-tx` comparison. A moved dependency read means a different merged value that has no proof yet: retain it on the merge branch and derive its needed tests. No retry loop and no stale result carried forward. A fact write outside the read evidence need not rerun tests.

The acceptance transaction installs program/norm data, advances `program-commit`, and records the accepted transaction/request/source and prepared git object id together. Parents name ours, source and the tested merge branch. Datahike computes no semantic merge itself; `merge-db!` records parents for supplied data. Multiple parent commits in one call implement an octopus; a sequence of pairwise accepted commits is not equivalent to an atomic join.

### Files and git: do not claim a distributed transaction

Datahike, a filesystem and git refs do not have a shared atomic commit. Therefore **accepted in Datahike**, **git published**, and **loaded** must remain distinguishable facts. Calling all three “merged” immediately after Datahike accepts is the current crash bug.

Write-back first produces immutable file bytes from the tested merge value and its git parent, splices exact spans, and reindexes them with the current writer's equivalence proof. Build a git tree and commit with a private index and fixed recorded author/time/message inputs; retain it under a request-specific git ref **before** Datahike acceptance. Never use the shared checkout or shared index as merge input. Root's source owner publishes the git branch with `git update-ref <ref> <new> <old>`; repeating the same desired commit observes it already present. The request is merged/closed only when that desired commit is on the published branch. A crash before Datahike acceptance leaves a reviewable git object/ref; after acceptance it leaves a durable desired git commit, whose publication is ordinary due work at root's next prime. There is no separate “resume failed merge” path.

Root owns that publication ref; direct outside git commits are imported as the new main input through the same owner. If an outside writer moves the ref, git's compare-and-swap preserves it: root keeps both commits and derives an ordinary merge for review, rather than overwriting or blindly repeating the ref update. Generated files materialize from the accepted git tree in the existing committed archive; the shared developer checkout is a separate ordinary git update, never overwritten over user edits. No git worktree is required. Reload follows the published artifact and reports its actual loaded identity; it cannot turn an unpublished commit into a success.

Git already supplies [immutable commit construction](https://git-scm.com/docs/git-commit-tree) and [ref compare-and-swap](https://git-scm.com/docs/git-update-ref). This proposal changes Seon's artifact ownership; it does not claim these operations make Datahike and git atomic.

### Fast-forward and joins

Git fast-forward moves a ref when ours is an ancestor of theirs; a squash has no source parent ([git merge](https://git-scm.com/docs/git-merge)). **Do not fast-forward main to an agent's raw database**: it contains runtime facts and excludes main-only facts. “Main has not moved” permits omitting the three-way comparison for the selected program, not importing the entire agent value.

A real Datahike fast-forward can publish an already prepared **main-shaped merge branch** when main is its retained parent and the complete accepted database is that branch's value. The pinned `force-branch!` explicitly requires exclusive access, rewrites parent metadata, and leaves connected readers stale. It is not this operation. Add fast-forward to Datahike's existing serialized writer, with expected current commit, connection update, listener notification and reachability publication in that same operation; do not emulate it in Seon. Until that dependency phase is proven, use the ordinary parented merge. This is an optimization with no semantic gap in the ordinary path.

For a join, root holds **all** predecessor proven commits plus their main merge ancestry. Derive one n-way merge using Datahike lineage and `seon.program/three-way`; shared ancestry contributes once, disjoint changes compose, and differing changes remain unmerged. Multiple incomparable common ancestors become a recursively merged base; a conflict in that base remains explicit for root, never first-sorted selection. Do not sort and choose one predecessor; sorting merely gives deterministic presentation. Produce one octopus merge commit only after resolution, then store it as the successor fork. Root has merged each predecessor's trusted facts before launching the successor, so inherited contributions are already represented on main and the successor's fact base can be F. Main's coordination facts initialize the new assignment without importing main's newer program. This initialization must remain: the assignment did not exist in its predecessor's commit. Replace `attempt-rows-tx`'s task-specific import with schema-derived owning-value conversion through the existing schema/pull owner; deleting all initialization would contradict F.

### Retention and execution

Use the owner-chosen Datahike `:datahike.gc/roots` addition, absent today. Its input is a set of commit ids; the collector walks each as a root including its indexes even outside the time window. Derive roots from open requests' bases, conflicts, selected source/merge commits, and tasks whose successors may still fork those commits, including held successors. Capture the owning database value under the collector's existing reachability publication mechanism. Publishing a reference to an old commit and deleting its branch must compose with that mechanism, not race a separately sampled root list. Do not use `reachable-extension` to duplicate Datahike's index walk in Seon.

Held successors retain commit values, not running graphs, connections or branch names. Root deletes a finished source branch after its request closes and its proc's actual exit; unresolved errors keep the request open for root. A stopped task retains its branch and assignment for resumption. Cancel means root's execution intent, interruption, actual exit, then close with reason and delete; it is not an agent success/error. Facts already merged are not rolled back. An unknown host-call exit keeps its resources and produces the bound's error; it never justifies a second graph for the same assignment.

## 4. Every dig case

| # | Design and observable consequence |
|---|---|
| 1 | A fact merge does not mark stop/delete done. Each is derived independently from current request, roster and actual exit. Crash after any one leaves the others due. |
| 2 | Fact data and `facts-commit` move atomically at the writer. Repeating H adds zero contributions; no partial base advances. |
| 3 | The three-way receives writer ours; the reproduced later root edit becomes a conflict, not replacement. |
| 4 | Unmerged identity/attribute and three retained commits live on the request/merge branch; root can read all values after source branch deletion becomes permissible. |
| 5 | Successor uses retained commit id directly with `commit-as-db`/`branch!`; branch name existence is irrelevant. Missing retained data is a storage defect, never a fork from current main. |
| 6 | Derive start only from a committed assignment. Root no longer emits dependent `:attempt` and `:start-flow` actions together and catches the first to run the second. One entrant catch records failure; other assignments' independent work still derives. |
| 7 | Use the existing Flow execution inputs; delete the single-use detached wrapper. No inline substitute or absent-launcher success. |
| 8 | One root `db<->flow` start derivation; schedules also enter it. Cluster and task execution facts determine starts, never a hard-coded running value or held-task branch. |
| 9 | Stop drains; cancel interrupts and waits for actual exit. Root records its own cancel/exit observation, never an agent-authored successful result on main. Pause is observed with Flow's ping, not inferred from issuing pause. |
| 10 | Pinned `with-store-eids` applies once and `next-eid` allocates via `updateAndGet`. Transaction functions remain pure: tempid resolution is still not an exactly-once effect contract. |
| 11 | Complete owning values supply component identity through their owner. Delete the separate identity-less cluster-entity check in the same slice; test a child change through its owner. |
| 12 | Keep necessary new-assignment initialization at F, but derive refs/components from schemas. Do not claim the old hand list still exists or delete data the new branch needs. |
| 13 | External drivers consume recorded completion/termination evidence. A timeout/unknown result is not ready and starts nothing. The separate driver owner must prove its own path; this plan specifies the shared terminal data, not its implementation. |
| 14 | The assignment's original P and F evidence are inputs to the one done rule. Already-green gives no successful work; pending/error is not an initial failing proof. |
| 15 | Fixed test inputs and seeds; bound failures stay red for unchanged content, with actual exit retained. No run-until-green. Contradictory results for identical declared inputs are a runner/test defect delivered to root. No finite execution can prove an arbitrary flaky test will never fail. |
| 16 | Branch done remains about H. Root tests the actual merged dependencies; stale reach/input evidence cannot establish green. A sibling regression stays on the merge branch for root, without revoking historical completion. |
| 17 | One octopus join includes every predecessor; conflicting joins are root work and launch no successor until its fork value exists. Shared ancestry contributes once. |
| 18 | Before acceptance, cancel closes without a code merge. After acceptance, cancellation cannot undo the committed value: git publication remains root's due work. Reversal is a new reviewed change, not cleanup. |
| 19 | Root edits are part of writer ours. Root program edits use a merge branch and the same publication owner, including root's own work. |
| 20 | Same accepted source has no remaining program delta; return the existing acceptance instead of today's empty-replacement error. Distinct source changes compare with that partition's advanced basis. |
| 21 | Explicit commit roots retain required bases beyond the window; branch deletion removes a name, not the task's needed snapshot. GC tests must include old commits and collection concurrent with reference publication. |
| 22 | Held successor pins its required commit until resumed/cancelled or its dependency changes. It pins no predecessor graph/branch. Ignoring held successors would destroy its promised fork. |
| 23 | Occurrence contribution identity includes its originating assignment. Sum unique contributions, preserving repeated errors within one assignment; inherited contributions keep identity across joins. |
| 24 | Main's registry defines merge rules. Schema changes are program for review; manual conflicts are data on the merge branch. A branch cannot promote its own edits into trusted facts. |
| 25 | Same program identity with differing digests stays unmerged. Root's ordinary edit resolves it and subsequent tests prove the resolved value. |
| 26 | Root uses the same REPL/proof functions; only its held branch enables cross-agent effects. No request to merge main into itself. Root's program work uses its merge branch. |
| 27 | Two partition-specific merge bases and two git shapes; one request and one root authority. Runtime evaluations/live objects never become program. |
| 28 | Prove completion retains a source for successors. Its own program is pending until a terminal cumulative merge passes all tests; facts can merge now. |
| 29 | Implement/question with no P is not done. Prove uses its explicit successor-proof schema. A completed selection with zero tests is distinguishable from no run and is not positive proof. |
| 30 | Root child-task identity is `seon.id/id [request-id producer-symbol subject-identity failure-class]`; repeat observations update the same task. Different requests do not collapse into one entity/attribute conflict task. |
| 31 | Agent persists outgoing message under its existing message identity on its branch. Root consumes it once via the request and offers once through Flow. See the precise crash semantics below; unavailable recipient is explicit error data returned to sender by root. |
| 32 | A given-back decision names its target and assignment, never rewrites an earlier completed assignment. Root derives a new assignment at the selected fork; obsolete open requests close with reason. Accepted changes remain historical and require a new change to reverse. |
| 33 | F is held and read as an immutable value; task schema/proof selection at F never substitutes today's main. A later task refinement is separate data, not a silent new baseline. |
| 34 | Live graph registry has one entry per assignment identity. Root owns create/exit/removal, and only an absent graph with due work starts. Restart primes from facts; an unresolved in-process exit still owns its entry. |

Additional cases that affect the design:

- **Crash and a message offer cannot be atomic.** Preserve R-4's at-most-one offer and accepted in-flight loss. Root first consumes the outgoing message identity in a main transaction (new optional transaction ref `:seon.message/offered-tx`, meaning authorization for this one offer, not proof of delivery). It then offers once. Recipient storage uses the message's existing identity. A crash between these operations leaves delivery **unknown**, not “delivered”; do not offer again. Observed unavailable recipient/false offer produces one error by message identity; a later root context shows it without relying on another deliverable message. This is the explicit sacrifice, not exactly-once messaging.
- **Crash after git object, after acceptance, after ref update, after close.** Object with no acceptance is unaccepted review data; accepted object with old ref is due publication; ref already at/descending from the accepted object proves publication; closed request with live graph/name derives stop/delete. Retained request git refs prevent git GC from losing prepublication objects. Each normal reader derives its own fact; no single completion marker stands for four effects.
- **A source changes during root review.** Source/merge values are immutable. Root may accept only the content it reviewed. Before closure, it reads the final exited source head; a later source value remains pending in the same request. No conflict resolution is overwritten by rebuilding a merge branch; merge new inputs into it.
- **Task/proof mutation or forged root metadata.** Assignment binds task and proof definitions. Root's runner and main registry own acceptance; source claims cannot author root review, trusted ownership, loaded identity or termination. This is authority at the producer/writer, not a later allowlist.
- **Schema narrowing and dangling refs.** Norms and program declarations form one transaction on the merge branch and at main's writer. Validate final owning values and repaired references together. A failing norm installs nothing and keeps the merge branch; never run a side effect inside a transaction function.
- **An agent never finishes.** Budget exhaustion remains a resumable assignment with its branch, counts and root task. Stop/timeout is not success; uninterruptible host execution retains ownership until actual exit. Completion guarantees are conditional on terminating proofs and functioning storage, not a promise that arbitrary code terminates.

## 5. Ten decisions

| Dig question | Recommendation; guarantee | Cost and sacrifice |
|---|---|---|
| 1 Join | One parented octopus over all retained predecessor commits. Follows R-6. | O(distinct changed identities + predecessor count); retains parent history within the configured window. Conflicts require root. |
| 2 Basis home | Main's single request owns `facts-commit` and `program-commit`; each advances with its actual merge. | Two UUIDs because two partitions genuinely have different histories. Trusted partition conflicts stop that branch's fact merge as a whole. |
| 3 Already green | One F-aware proof rule, also used before model launch. Require an observed failing proof, not all proofs failing. | One baseline run per changed proof/input. No credit for work already satisfied. |
| 4 Sibling changes | Leave source completion historical; prove the actual merge value at root. | Root may run changed reaching tests; a locally done task can await repair of an integration regression. |
| 5 Held successors | Retain snapshots for all live dependency promises; delete branch names independently. | Held tasks can retain index nodes indefinitely until explicitly cancelled; they consume no graph/connection. |
| 6 Cancel | Root intent → interrupt → actual exit → close/delete, no error task solely for cancellation. | Already merged facts stay. Real failure/unknown exit still produces a root task. |
| 7 Words | Assignment replaces attempt; task/schema replaces step; proof/result/exit replaces end; successor proofs replaces product; task proofs replaces obligations; manual conflict replaces rule `:root`; pending merge is not git's unmerged. | Convert all callers and stored schema consumers in each rename slice. Do not rename `:seon.task/kind` into a different meaning. |
| 8 Auto-merge | Enable continuous owned-fact squash. Keep code review required; root's approval makes execution automatic in the same proc. | Human/model review latency remains. No invented criteria that overrule R-10. |
| 9 Identity | One request per assignment, rooted before work; heads/reviews are its versioned facts. | A closed assignment's new work creates a new assignment. Drops a second merge task and per-commit request churn. |
| 10 GC | Implement the already chosen fork root seam before deleting referenced branches. | A Datahike fork change and adversarial collection proof. Increasing the window cannot guarantee an arbitrarily held successor. |

## 6. Deletion and interfaces

Reuse `seon.program/three-way`, `seon.cluster.squash/squash-merge`, `seon.test/select`, `seon.test/green-members`, `seon.db/with`, `seon.db/transact!`, `seon.cluster.agent/db<->flow`, the existing SCI forks and Flow lifecycle. They already own the hard concepts.

- Delete `seon.turn/end-task!`, `end-task-call`, `end-merge-request`, `land-end!`, `land-ended!`, `root-execution`, `landing-tx` and the wrapper `my.program/request-merge!`. The proc derives completion; assignment initialization supplies the request; root merges. Convert their last callers in the same commit.
- Delete `seon.cluster.agent/unlanded-ends`, `land-ends!`, `record-error-end!`, `settle-kept-branches!`, `successor-waits?`, `detached!`; replace with `db<->flow`'s independent queries over requests, execution and retention. Fold `agents-to-start` and the assignment starter into one start owner. `ended-problem!` becomes the existing identity-based root child-task writer.
- Delete `seon.cluster.squash/copy!`, `conflict-task!`, then `add-count`; `squash-merge!` enters the writer directly and returns a tx-report plus its request's conflicts. Keep its pure merge derivation and identity remapping.
- Fold `seon.cluster.source/land!`, `land-held!`, `prepare-merge!`, `accept-merge!` into the existing source owner's `merge!` operation with retained database values. Remove `declarations-held-tx`, `commit-locked`, `call-with-commit-lock` and shared-working-tree commit assumptions as their replacement publishes immutable git objects. `write-back!` remains the source-to-files owner; `commit!` uses git plumbing and ref compare-and-swap.
- `seon.cluster.registry/advance-base!` no longer runs after the acceptance transaction to establish correctness: main request bases are already atomic. Delete its base-ref mutation callers after explicit GC roots cover them. `retire-branch!` becomes `delete-branch!` with every caller converted; no alias left behind.
- `seon.context/step-{observation,status,verdict,section}` becomes `task-{observation,status,proofs,section}`; `seon.turn/record-broken!` disappears into the evidence query. `seon.task/attempt-rows-tx` is replaced by schema-derived assignment initialization, **not removed without replacement**.

Expected budget, not a measured patch: delete roughly 650–900 source lines across these effect chains, add/replace 450–650 source lines including root artifact derivation and query functions (net −450 to 0); schemas net +80–150; Datahike fork +60–120; tests net +200–350 after converting existing cases. No promise of a net deletion substitutes for reviewing the actual small bodies. No source/test/schema change is made by this planning lane.

## 7. Phases, proofs and costs

All phases A–R are pending. Each is a roughly fifteen-minute **implementation slice after its red proof and interface exist**, not a claim that crash/GC design can be invented and proven in fifteen minutes. Root files the red proof first, reproduces it, then assigns one slice. New deftest names below are proposed; the existing `seon.context-test/each-step-kind-is-done-by-its-one-rule` is extended rather than duplicated. A contradictory interface returns to design. No phase deletes a path before its last caller converts; schema publication/reload and consumers commit together. HEAD boots at every boundary. Root owns the integration sequence; no parallel file ownership is implied.

Each proof measures its owning call with `seon.profile/measure`, records ms, allocations and call/read counts, and uses the runner's configured bound. These are **targets**, not unperformed measurements. The live measured baselines are in §8. Pure task status should remain O(number of selected tests), under 10 ms on the small case; one bounded writer/derive operation under 100 ms; ordinary end-to-end eval under 1 s; test bodies keep the installed 5 s bound. A cost over 10 s requires owner authorization, not a larger silent timeout.

| Phase | Loadable change and proposed red proof (assert on data) | Cost measurement |
|---|---|---|
| A | Extend `seon.context-test/each-step-kind-is-done-by-its-one-rule`: green/absent/unknown F cannot prove Implement; return `:working`. Add explicit baseline evidence to observation with consumers; no execution cut yet. | Measure P=1 and P=100; linear calls, <10 ms small case. |
| B | `seon.context-test/a-task-uses-its-assigned-proofs`: deleting/retagging a proof leaves it required. Wire real F evidence and remove broken/new-red mirrors with last readers. | Two indexed evidence reads plus selected tests; <100 ms derivation. |
| C | `seon.task-test/an-already-satisfied-task-starts-no-model-call`: first branch proof run yields satisfied task, zero model calls and no work success. Missing/errored P cases in same entrance test. | Existing eval baseline 86–246 ms; target <1 s excluding measured test bodies. |
| D | `seon.context-test/a-question-is-done-by-its-answer-proof`: invalid answer leaves stored answer absent; valid answer makes proof green. Publish answer schema/ref writer and `my.agent/answer!` together. | <100 ms small write; proof within 5 s. |
| E | `seon.task-test/one-assignment-has-one-merge-request`: two source commits give one request; no manual request call. Convert task/request schema and all request readers/writers; `end-task!` temporarily reads the initialized request instead of invoking the wrapper. Remove separate merge task and wrapper here. | O(1) identity lookup/write; <100 ms. |
| F | `seon.cluster.squash-test/a-repeated-merge-adds-nothing`: merge H twice, occurrence count unchanged on second; source increment adds once. Move three-way/base update into writer, keeping existing root caller until phase L. | Same finite delta measured before/after; <100 ms. |
| G | `seon.cluster.squash-test/a-root-edit-is-a-conflict-at-the-writer`: captured-old/later-root inputs yield conflict refs and preserve ours; mixed conflict/nonconflict case advances no fact basis. Converts conflict task writer and removes `copy!`. | Current value-only case 17.09 ms including three checked `with` calls; target <100 ms writer. |
| H | `seon.error-test/inherited-occurrences-are-counted-once`: join/shared ancestor and two originating assignments sum exact distinct contributions; repeated error in one assignment still increments. Convert occurrence identity/aggregate and delete `add-count`. | O(changed contributions); <100 ms finite case. |
| I | **Dependency** `datahike.test.gc-test/explicit-commit-roots-survive-the-window`: old rooted commit and indexes readable after GC; unreferenced commit reclaimed. Add the root-input seam inside collector's existing publication mechanism. | Profile root marking vs distinct nodes; no Seon whole-store traversal. |
| J | `seon.cluster.registry-test/a-held-successor-needs-a-commit-not-a-branch`: delete predecessor name, collect beyond window, fork held successor at exact F. Wire root derivation, convert branch/base deletion callers. | O(live referenced commits + existing GC work); derive roots <100 ms. |
| K | `seon.cluster.agent-test/a-join-holds-every-predecessor`: two disjoint edits visible; conflicting case has all parent ids and launches no successor. Replace first-sorted fork with one octopus and schema-derived initialization. | O(sum of distinct deltas); <100 ms for two tiny parents, profile tests separately. |
| L | `seon.cluster.agent-test/proofs-stop-work-without-a-success-write`: eval → proofs → done → actual exit, zero manual request/end writes. Replace end chain, marker query and three start paths; delete their last callers together. | Current eval envelope baseline <250 ms; target <1 s, test work separate. |
| M | `seon.cluster.agent-test/stop-cancel-and-pause-follow-actual-exit`: injected blocked host call retains graph/branch until exit; stop drains, cancel closes with reason, pause ping is observed. Convert control owner and remove dependent catch-and-continue. | Measure control derivation <100 ms; wait bounded by declared exit event, never a sleep. |
| N | `seon.cluster.message-test/one-message-authorizes-one-offer`: crash cases before/after consume/offer give ≤1 offer, unknown or explicit undeliverable data, sender writes no main. Replace sender-side cross-agent effect. | O(1) identity write/offer; <100 ms each. |
| O | `seon.cluster.source-test/the-merged-program-is-the-tested-program`: root/sibling edit between capture and accept changes merged evidence; main keeps edit and request/merge branch remains open. Convert writer acceptance and cumulative program base; delete pre-read replacement/digest fence. | O(changed entities + changed test inputs); derive <100 ms, tests separately measured. |
| P | `seon.cluster.source-test/write-back-builds-the-tested-git-tree`: generated tree reindexes to accepted declaration digests, shared checkout edit unchanged. Convert write-back inputs to immutable git parent/tree and retain request ref. | O(changed file bytes); <1 s tiny file case, allocations reported. |
| Q | `seon.cluster.source-test/a-merge-closes-only-after-git-publication`: injected crash after each external boundary leaves acceptance/query/ref correct; repeat creates no different commit; moved outside ref remains reachable. Replace commit lock/shared index with git plumbing, combine close consumers, delete old source merge chain. | Measure object build and ref update separately, <1 s small case; no claiming DB/file atomicity. |
| R | **Dependency + adapter** `datahike.test.versioning-test/fast-forward-uses-the-connected-writer` then `seon.cluster.source-test/fast-forward-publishes-only-a-main-shaped-commit`: concurrent writer value preserved, connected reads/listeners see target, raw agent runtime facts absent. Optional optimization; ordinary merge remains complete without it. | One ref/head publication, no rewritten entity set; compare against ordinary merge, retain only a measured win. |

Renames occur with the owning phases, including docstrings, quoted symbols, schemas and tagged proofs; the new vocabulary enters `docs/seon/architecture/vocabulary.md` first in that slice. An implementation phase's committed message reports actual source/test lines and measured parent/change results. The final central proof is a real Prove → two Implement predecessors → join → terminal task → reviewed merge → git publication → branch deletion/collection, with a provider fixture, through the same agent entrances. Root also verifies boot/reload of the published archive and browser paint separately.

## 8. Dated evidence and limits

Read AGENTS.md and the six skills (including REPL), the supplied dig, owning specs, schemas and current callers. Verified dependency pins: Datahike `42309184021bd24d0f9c0fd17f514c320ab037fd`, Malli `dd13ff81139159778edb0a67b491eccc0baa991d`, core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051`. Default runs committed archive `015673f475eb78eeaf4b5a9231c187399a4e4bfe`, pid 98270, start `2026-10-12T02:02:53.684Z`; no reset/reload performed.

`bin/seon status` and MCP `runtime_status` both reported degraded: 15 untested-hot warnings, 1 agent-error signature, 13 errored evaluations under the installed legacy label, 1 file without namespace and 1 unowned namespace; readiness 884 ms; heap live 847,704,200 of 4,294,967,296 bytes. These are existing observations, not this plan's passes. Skill claims of assignment rejection and the deleted system-turn path are not relied on; the actual `seon.task/assign!` still says already-green closes work. Shared skill edits belong to another lane and were not changed here.

All forms below used MCP `eval_clj`, `agent="plan-astra"`, `cluster="default"`, `mode="sci"`; own branch `:agent-fe5fcd6c9b6d`. No prospective main write was committed. First form `(seon.context/current-state)` returned `{:seon.context/turns {:my.agent/turns-left 100}}`: evaluated 11 ms/16 MB; whole request 246.1 ms. The sparse context reflects this planner's unassigned outside-agent context.

**E1: Already-green completion, live pure owner.** Exact form:

```clojure
(select-keys
 (seon.profile/measure
  (fn []
    (mapv (fn [fork]
            (seon.context/step-status
             {:seon.task/step :implement
              :seon.task/proofs #{'example/proof} :seon.task/product #{}
              :seon.context/head {'example/proof {:seon.context/proof-status :green}}
              :seon.context/fork-point fork}))
          [{'example/proof false} {'example/proof true} {}])))
 [:seon.profile/value :seon.profile/ms :seon.profile/allocated-bytes
  :seon.profile/hot :seon.profile/reads])
```

Result: all three `{:seon.task/status :done :seon.context/broken #{} :seon.context/new-red #{}}`; measured 1.32 ms, 0 reads; whole reply 98.0 ms, evaluation 2.3 MB. The first probe selected the wrong allocation key, so measured allocation is **unavailable**, not zero. Existing source test lacks the green-at-F case.

**E2: Lost root edit and the smaller algorithm, checked immutable values.** Exact form:

```clojure
(select-keys
 (seon.profile/measure
  (fn []
    (let [b (seon.db/db) e [:seon.task/id "63eac0265199"]
          t (seon.db/with b [[:db/add e :seon.task/intent "source change"]])
          o (seon.db/with b [[:db/add e :seon.task/intent "root change"]])
          p (seon.schema/handed-projection)
          early (seon.cluster.squash/squash-merge t b b p nil)
          late (seon.cluster.squash/squash-merge t b o p nil)
          overwritten (seon.db/with o (:tx-data early))]
      {:early (select-keys early [:conflicts]) :late (:conflicts late)
       :after (seon.db/pull overwritten [:seon.task/intent] e)
       :errors (keep :seon.error/type [t o overwritten])})))
 [:seon.profile/value :seon.profile/ms :seon.profile/alloc-bytes
  :seon.profile/reads :seon.profile/ranked-by-self])
```

Result: early conflicts `[]`; after intent `"source change"`; late has one conflict, entity 52503, attribute `:seon.task/intent`, ours `"root change"`, theirs `"source change"`, base exactly the task intent pulled from b; errors `()`. The long existing base intent is recoverable through the exact lookup/form rather than copied here. Measured 17.09 ms, 10,490,584 allocated bytes, 1 read; owner self costs `seon.db/entities-changed-after` 5 ms (2 calls), `seon.db/with` 2 ms (3), whole-value validation 2 ms (3). Whole request 113.2 ms. Reply's large-value finding: 1,034 estimated tokens, caused by displaying that existing intent. No persisted task or main change was made.

**E3: Source completion versus a different merged dependency, and installed schemas.** Exact form:

```clojure
(select-keys
 (seon.profile/measure
  (fn []
    (let [db (seon.db/db) p (seon.schema/handed-projection)
          observation
          {:seon.task/step :implement :seon.task/proofs #{'example/proof}
           :seon.task/product #{}
           :seon.context/head
           {'example/proof {:seon.context/proof-status :green}
            'example/reaching {:seon.context/proof-status :green}}
           :seon.context/fork-point {'example/proof true 'example/reaching false}}]
      {:valid? (seon.schema/valid-candidate-value? p :seon.context/step-observation observation)
       :branch (seon.context/step-status observation)
       :merged (seon.context/step-status
                (assoc-in observation [:seon.context/head 'example/reaching :seon.context/proof-status] :red))
       :installed
       (mapv (fn [k] (seon.db/pull db [:seon.schema/key :seon.schema/form] [:seon.schema/key k]))
             [:seon.task/status :seon.task/merge-request :seon.task.attempt/merged])})))
 [:seon.profile/value :seon.profile/ms :seon.profile/alloc-bytes :seon.profile/reads])
```

Result: valid `true`; branch `:done`, merged `:working` with broken `#{example/reaching}`. Installed status says derived/never stored; request identity is `[assignment commit]`; proposed merged attribute absent. Measured 0.36 ms, 152,408 bytes, 3 reads; whole request 98.3 ms. This proves the predicate contrast, **not** a two-agent integration run. The latter is phase O's red proof.

An earlier inspection selected nonexistent `:seon.schema/definition`; it returned three read errors rather than crashing (whole request 109.6 ms), and a 6,564-token value finding. Correct member is `:seon.schema/form`, used in E3. A query for existing occurrence ids/counts on this branch returned none; repeat-count behavior was source-verified against `copy!`/`add-count` and the dig's reproduction, not reproduced here with fabricated entities. No crash, GC, git-publication or full runner regression was executed by this doc-only lane.

Dependency findings checked in source: `datahike.writing/transact!` owns expected-basis comparison; `merge-writer!` adds parents after ordinary transaction derivation; `datahike.versioning/force-branch!` explicitly lacks independent-writer CAS; `datahike.gc/reachable-in-branch` retains head indexes but traverses parents only in-window; `gc-storage!` has reachable-extension but no roots option; `datahike.db.transaction/next-eid` and `datahike.writing/with-store-eids` implement the allocator fix. `clojure.core.async.flow/stop` is not proc exit; implementation closes channels after sending stop. Malli's `validator` caches on the compiled schema. These are pinned source guarantees, not claimed upstream additions. Seon's caller gaps are phases F, I, M, O and R.

Verification: `clj-kondo --lint src script test --cache false --parallel` completed in 5,738 ms: **0 errors, 1,573 warnings**. Warnings are shared-tree findings; no source was edited. Documentation does not enter test selection, so this plan uses the explicitly requested doc-only `bin/lane commit` path without `--test`. Foreign boundary: shared skill edits, vocabulary work and other untracked files were present; none is part of this lane. The dig itself was untracked when read and is intentionally not included in this commit. This plan is a proposed design with reproducible evidence, not an implementation or a declaration that all edge-case proofs already pass.
