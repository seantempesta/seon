---
title: "Hybrid: an attempt's owned facts are pushed to main as they change"
type: research
status: hybrid of plan-push-opus.md (2bc33af8e) and plan-push-sol.md (d2cc4705f) over verify-astra.md (246dd0bd9) and astra-overnight-review.md, under hybrid-agent-world.md and AGENTS.md "Agents are compartments" (3830c532d); owner decisions of 2026-10-12 applied (push as facts change; root listens to the branch and executes every effect on main; messages pushed as records; `seon.task/ready`, `seon.cluster.agent/unmerged`; a step forks its predecessor's recorded end commit; no guards or fences; the push selects what the ATTEMPT OWNS); design only, phases filed as tasks
basis: HEAD 4fcbe5b9a read by symbol (`seon.cluster.squash/squash-merge`, `copy!`, `add-count`, `collision-remap`; `seon.cluster.agent/db<->flow`, `land-ends!`, `unlanded-ends`, `record-error-end!`, `settle-kept-branches!`, `detached!`, `mailbox-step`, `plan`; `seon.program/changed-identities`, `cluster-attributes`; `seon.db/entities-changed-after`, `changed-attributes`; `seon.task/frontier-rules`; `seon.cluster.registry/collect-and-inventory!`, `retention-cutoff`, `base-branch`; `seon.cluster.wake/route!`), `reference-code/datahike` `datahike.gc/reachable-in-branch`, `datahike.db/SinceDB`; Opus's and Sol's default measurements (below) reused, no new probe
---

# Pushing an attempt's owned facts to main

## 1. The design in one paragraph

**Owner principle (2026-10-12): the whole design is root's view of the data the agent transacts.** An agent only
transacts facts on its own branch (refinements, children, its end, outgoing messages, its merge request); it never
triggers an effect on main or on another agent. Root's `db<->flow` listens to those datoms and executes, from main,
everything that affects main or another agent (§4). Agent-local effects (model calls, its own REPL evaluation, its
tests in a fork of its environment) stay the agent's. **A push is the squash merge run inside main's writer, over the entities the attempt owns, against the last commit
main says it received from that branch.** Each attempt row on main carries **`:seon.task.attempt/pushed`**, the
branch commit the last push took: git's remote-tracking ref, written in the same transaction as the facts it covers.
One pusher exists, **`db<->flow`** (root's proc; root is the only agent that acts cluster-wide), woken by the
branch's route on every transaction that asserts or retracts a `:seon.cluster` attribute (a refinement, a child, the
end, an occurrence, a delivered message); the agent's world never names main. Its pass derives
**`seon.cluster.agent/unmerged`**: the attempts whose branch head differs from `pushed`, and for each transacts on
main `[[:db.fn/call seon.cluster.squash/push-tx attempt head]]`. The function receives the writer's own db (ours),
reads `pushed` from it (else the attempt's recorded fork commit, F), opens that commit as the base
(`d/commit-as-db`), runs `squash-merge` with theirs = the held immutable head, **keeps only the coordinates whose
entity the attempt owns (§3)**, and returns one transaction: the owned facts, `[:db/add attempt :pushed head]`,
and one **finding** entity per `:root` conflict and per changed coordinate the attempt does not own. Program
entities never move by push: only root's merge request. This removes verify-astra #1–#4 at their source (the base
advances atomically, so a repeat adds nothing; the three-way runs on the writer's db, so a concurrent root edit is a
conflict, not an overwrite; conflicts are data with all three values and both commits; no marker, the end is just a
pushed fact), and a crash anywhere loses nothing: unpushed work is derived (head ≠ `pushed`) and the pass primes at
`::flow/resume`.

## 2. Point by point

| Point | Opus | Sol | Resolution |
|---|---|---|---|
| Pusher, wake, writer function, moving base, conflicts as data, derived lifecycle | yes | yes | **Agreed** (both plans, independently). |
| Where the base is recorded | `:seon.task.attempt/pushed` on the attempt row, written by `push-tx` | the newest main transaction carrying `:seon.squash/source-branch`/`source-commit` tx-meta, ordered by main's tx order | **Opus.** One indexed fact per attempt, read by `pull` in the writer and by `unmerged`; Sol's query over tx-meta is a scan of main's transactions per branch per pass. The tx-meta provenance stays as the record of each push (branch, source commit, attempt). |
| `changed-identities` scans each changed attribute's whole history (9,451 datoms today, 0.8 ms warm / 3.3 cold) | risk, follow-up via since index | work proportional to pending commits, unmeasured | **Resolved, kept as is, with a cost row.** `seon.db/changed-attributes` first drops every tracked attribute whose Datahike revision is equal at base and head, so an interval of evaluations alone scans nothing (Opus: ≈4 ms including the base read). The scan is per *changed* attribute over that attribute's history, which for tracked attributes grows with tasks, attempts and occurrences, never with evaluations. `d/since` does not help: `datahike.db/SinceDB` filters the same temporal index by tx (`since-pred`), the same walk. The bound is a proof row (U1): push-tx under 8 ms warm for a ten-evaluation branch with one refinement, and `seon.profile/measure` records the scanned-datom count; when a tracked attribute's history passes ~100k datoms the follow-up is a tx-ordered temporal index in the fork, filed then, not now. |
| No-history attributes (`count`, `last-at`) | base = `commit-as-db` of the pushed commit, retained | same; "a UUID alone retains nothing" | **Agreed:** the retained base commit holds the no-history value at that commit, so the `:add` delta is exact (Sol's table: base 1, source 2, main 2 → 3; repeat → 3). `last-at` gets a new rule value **`:max`** in `:seon.cluster/conflict` (`#{:root :theirs :ours :add :max}`): ours = max(ours, theirs); every concurrent repeat would otherwise be a `:root` conflict. |
| Retention of pushed bases past `:seon.config.db/snapshot-window-ms` | derive at collection from `pushed`, keep through F's tag; nothing written per push | native retained roots derived from provenance; close the fork gap (extension returns keys, cannot mark a snapshot) | **Fork seam, one option.** F's "tag" is keeping the predecessor's branch (`:seon.task.attempt/fork`, `successor-waits?`), which does not retain an *ancestor* of a live head once newer commits leave the window. `datahike.gc/gc-storage!` gains **`:datahike.gc/roots`**, commit ids walked exactly like heads (`reachable-in-branch` already walks commit keys; the head lookup is its only branch-specific line). `collect-and-inventory!` passes main's `pushed` values and `fork` commits of roster branches. Zero cost per push; no `force-branch!` (18 ms) per push and no second walker. Platform slice with a fork bump. |
| Batching boundaries (Datahike batches transactions; a report's db-after is the batch commit) | coalescing is squash semantics | "the principal remaining design question": intermediate states lost | **Decided: squash semantics.** A push compares the pushed base with the branch head at pass time, never a report; N commits become one main transaction and a changed-then-restored value yields nothing while the base still advances (Sol's point 6). Intermediate states are by definition unobserved, as in `git merge --squash`; nothing is lost that the design promises. |
| Messages | sender `offer!`s to the recipient's inbox (P3, c1e0c97c9); the recipient's write is pushed as a record | same, "archival fact, never a second inbox delivery" | **Owner: the sender only transacts.** `send!` writes the message on the sender's branch (`keep?` goes: a sent message is always the sender's fact). Root's pass sees it (a tracked datom), pushes it to main and **delivers it**: `offer!` of the value to the recipient's inbox channel in the roster; the recipient's mailbox writes it on its branch (P7 stays) adding **`:seon.message/received-at`**, which its next push carries to main. Delivery is derived, not remembered: a message on main with no `received-at` is offered again at every pass while its recipient has an inbox (idempotent by id), so in-flight loss is re-derived instead of accepted; a recipient that does not exist is an `:undeliverable` finding on the sender's attempt. The sender's `:seon.message/undeliverable` answer goes. |
| A pushed message on main starting an agent | risk 4: `agents-to-start` and the to-ref belt | — | **Tied to the one starter (verify-astra #8).** main's route offers `:seon.message/to` wakes to the recipient's mailbox (a coalesced "look", harmless) or, with no routing entry, to `db<->flow`, whose `agents-to-start` asks `turn/more-agent-work?` on main: a record would start a stopped agent. U5 lands after `agents-to-start` and the held exemption are deleted (every graph starts from `plan` over an attempt of a `:running` task); its proof is red until then. |
| Ownership of what is pushed | every tracked coordinate changed on the branch | same, within the cluster projection | **Owner (2026-10-12): the attempt's ownership, as data (§3).** Neither plan had it. |
| Deleted | §5 list | same, plus identity-only admission | **Union**, §5. |

## 3. Ownership: what an attempt may change on main

The push is the one way an agent modifies main, so its scope is a fact on each entity, read inside `push-tx` on
theirs (the branch head), never a guard around the agent and never "whatever changed on the branch":

| Entity | Owning fact | Set where |
|---|---|---|
| its task and the task's descendants | `(self-or-under task ?t)` over `:seon.task/children` (`seon.task/ready-rules`), task = the attempt's `:seon.task/attempt-of` | the task tree, at creation (`my.task/create!` under a parent) |
| a runtime task it created outside its tree (a problem it filed) | `:seon.task/created-by` = the attempt's agent (exists, `seon.task/create!`) | creation |
| its attempt row (end, reason, target, end commit) and merge request | `:seon.task/agent` = its agent; `:seon.task/of-assignment` = the attempt | the attempt write (`write-attempt!`), `land-ended!` |
| a message it sent (the fact) or received (its `received-at`) | `:seon.message/from` or `:seon.message/to` = its agent; identity `:seon.message/id`, so the two parties' pushes converge on one main entity by the collision remap | `send-call` on the sender's branch; the recipient's mailbox write |
| an error occurrence it hit (`count` `:add`, `last-at` `:max`) | **`:seon.error.occurrence/attempts`** (new, cardinality-many ref) names the attempt; `seon.fault/record!` adds the recording world's attempt on every hit | the entrant boundary |

`push-tx` groups the changed coordinates by entity, reads each entity's owning fact on theirs (one pull per changed
entity, proportional to the change), and splits: owned → the squash's tx-data; not owned → a **finding**. A finding
is the same entity as a conflict: **`:seon.squash/finding`** {`attempt` ref, `entity`, `attribute`, `kind`
`#{:conflict :unowned :undeliverable}`, `base` `ours` `theirs` as the value printer's shown text, `base-commit`,
`source-commit`}, identity `seon.id/id [:push-finding attempt entity attribute source-commit]`, written on main in
the push transaction, so the base advances past it (an observed decision, not fresh work every pass). Unresolved
findings keep the branch (§4) and hang off the open merge request when one exists, else off root's problem task
whose proof reads that none is open. Occurrence references stay values (`:seon.error.occurrence/evaluation` is a
`:seon.cluster.eval/id` by `:seon.db/reference-to`), so a pushed occurrence carries no dead ref.

## 4. Fact on the branch → root's action from main; lifecycle; cost

Every row is a listened `:seon.cluster` datom on the agent's branch (the route wakes `db<->flow`) and what root's
pass derives and executes from main, idempotently, on every pass that still finds the state:

| The agent transacts (its branch) | Root's `db<->flow` executes (main) |
|---|---|
| task refinement, children, `needs` | push: owned by the tree, three-way per `[e a]` against `pushed` |
| its end (`end-task!`: end, reason, target, commit) | push the end; then derived: `:stop` the graph, open the merge request (`:done`) or root's problem task (error), `:delete-branch` when nothing holds it (below) |
| its merge request row | push; root works it on main |
| an outgoing message (`send-call`) | push the record; `offer!` it to the recipient's inbox until `received-at` is on main; a missing recipient is an `:undeliverable` finding |
| a received message's `received-at` (mailbox write) | push; delivery is complete |
| an error occurrence (entrant boundary) | push: `count` `:add` = theirs − base, `last-at` `:max`, collision remapped to main's eid **whether or not base holds it** (today's `when-not base` misses pushed entities) |
| a change to an entity it does not own | nothing lands; an `:unowned` finding on the attempt |
| evaluations, calls, turns, answered `t`, program rows | nothing (program: root's merge request) |
| a successor's need satisfied (a predecessor's end on main) | `plan` writes the attempt, forks the recorded end commit (F) and starts the graph: the one starter |

`pushed`, the provenance tx-meta and findings are main's own facts, written inside `push-tx`.

**Ordering and idempotence.** One proc, branches in sorted order per pass, each branch in its commit order (each
push names its head; the next base is that head). `pushed = head` yields `[]` and the caller skips the transaction
(no empty provenance). **Lifecycle, derived by `plan`** (replaces `land-ends!`'s marker, catches and `detached!`):
`:stop` for a started graph whose attempt has an end on main; `:delete-branch` for a roster branch whose attempt has
an end, `pushed` = head, no open merge request, no unresolved finding, no successor waiting on its end commit
(`successor-waits?`) and `keep-ended-branches?` false. A `:done` end opens the merge request as today; an error end
gets root's problem task by identity with nothing to copy by hand. Each step is idempotent, so an interruption after
any boundary finishes on the next pass. Nested executions: a child's push targets the custody that forked it (its
parent's branch) through that custody's `db<->flow`; the parent's push carries the child's tree as descendants.

**Cost (Opus on default, JVM eval, disposable branch; Sol's `seon.db/with` values agree).** Base open 3.4 ms;
`changed-identities` 0.8 ms warm / 3.3 cold; squash 0.15 ms; the same as `[:db.fn/call …]` under `seon.db/with`
1.4 ms; one main transaction 19 ms. **A push is ≈5 ms in the writer plus one main transaction**, one per coalesced
tracked change per branch; evaluations alone never push; an interval with no tracked change costs ≈4 ms and writes
nothing. Sol's reproduction (occurrence 1→4→5 on repetition, zero datoms with source-as-base) is U1's red row.

## 5. Deleted, renamed

**Deleted:** `seon.cluster.squash/copy!`, `conflict-task!`, `squash-merge!` and `selection` with
`selected-coordinates` (root pushes by calling `push!` at its REPL); `add-count` (the sum is computed on ours inside
`push-tx`); `collision-remap`'s `(when-not (seq (d/datoms base …)))`; `seon.cluster.agent/unlanded-ends`,
`branch-end`, `record-error-end!`, `ended-problem!`'s "copy it by hand", `land-ends!`, `settle-kept-branches!`'s
marker query, `detached!`, the `land-ends!`/`unlanded-ends` try/catch in `db<->flow`; the `:seon.squash/attempt`
tx-meta and schema entry; `:seon.squash/copy`, `/datoms`, `/conflicts` (replaced by `push!`'s answer and
`:seon.squash/finding`); the identity-only partition admission (landed, 4fcbe5b9a). **Renamed in one commit:**
`seon.task/frontier` → `seon.task/ready`, `frontier-rules` → `ready-rules`; `unlanded-ends` → `unmerged` ("head ≠
pushed"). **Vocabulary rows:** push, pushed commit, finding, ready, unmerged (revised).

## 6. Phases (about fifteen minutes each; red first; filed as tasks)

| # | Slice (owned files) | Red proof, cost bound |
|---|---|---|
| U0 | Schema and words: `:seon.task.attempt/pushed`; `:seon.squash/finding` with identity; `:seon.error.occurrence/attempts`; `:max` in `:seon.cluster/conflict`, `last-at` declares it; `seon.message/message` → `:seon.cluster`; delete `:seon.squash/attempt`, `/copy`; vocabulary rows. Files: `resources/seon/schemas/{seon.task.attempt,seon.squash,seon.message,seon.error.occurrence,seon.program}.edn`, `docs/seon/architecture/vocabulary.md`. Platform `--test` | `seon.program-test/cluster-attributes-are-the-declared-cluster-entities` gains rows: `:seon.message/id`, `:seon.task.attempt/pushed`, `:seon.error.occurrence/attempts` in the set; `last-at` → `:max` |
| U1 | `push-tx` pure in the writer, ownership split, `push!` (`src/seon/cluster/squash.clj`, `test/seon/cluster/squash_test.clj`) | `…/a-repeated-push-adds-nothing` (collision 1→1 on a second push; conflict row 6 stays 6; tx-data `[]`) · `…/a-push-of-an-advanced-branch-adds-only-its-new-delta` (base 1, source 2, main 2 → 3) · `…/a-root-edit-after-capture-is-a-conflict-not-overwritten` (`seon.db/with` on a main edited after the head was captured: root's value stays, one `:conflict` finding with three values and both commits) · `…/a-pushed-collision-stays-remapped` · `…/an-edit-to-an-unowned-task-is-a-finding-not-a-push` (the agent refines another task's intent: main's value unchanged, one `:unowned` finding names attempt, entity, attribute; its own refinement still lands) · `…/last-at-takes-the-later-of-both-sides` · cost: push-tx ≤ 8 ms warm, ten-evaluation branch, through `seon.profile/measure`, scanned datoms recorded |
| U2 | `db<->flow` pushes: `unmerged`, the wake on tracked assertions and retractions (`seon.cluster.wake/route!`), `push!` per unmerged branch (`src/seon/cluster/agent.clj`, `src/seon/cluster/wake.clj`, `test/seon/cluster/db_flow_test.clj`) | `…/a-running-agents-refinement-reaches-main-at-the-next-pass` (main holds the intent while the graph runs; `pushed` = head) · `…/an-evaluation-without-tracked-facts-writes-nothing-on-main` (main basis unchanged) · `…/an-unpushed-branch-after-a-crash-is-pushed-once-by-the-prime` · cost: recording → main visibility ≤ 100 ms; p99 writer latency with ten pushing branches recorded |
| U3 | Derived lifecycle; delete the marker, copies, catches, `detached!` (`src/seon/cluster/agent.clj` `plan`, `apply-plan!`) | `seon.cluster.db-flow-test/an-interrupted-landing-finishes-on-the-next-pass` (throw after the push, before stop: the next pass stops the graph and deletes the branch) · `…/a-conflicted-end-keeps-its-values-until-root-decides` (branch kept while a finding is open, deleted after it resolves; the finding is readable after deletion and collection) |
| U4 | Retention roots: fork `:datahike.gc/roots` + `collect-and-inventory!` derives them from `pushed` and `fork` (`reference-code/datahike`, `src/seon/cluster/registry.clj`, `test/seon/cluster/registry_test.clj`); platform `--test` | `…/a-pushed-commit-older-than-the-window-survives-collection` (`collect!` with a 0 ms window after untracked commits: the next push's base opens; the predecessor's fork commit too) · the fork's `:clj-pss` suite green |
| U5 | **P3 converted:** `send!` writes the message on the sender's branch and answers its id (`keep?`, the sender's `offer!` and `:seon.message/undeliverable` go); `db<->flow` offers each message on main lacking `received-at` to its recipient's inbox; `mailbox-step` writes `received-at`; `:seon.message/received-at` and the `:undeliverable` finding kind (`src/seon/cluster/message.clj`, `src/my/message.clj`, `resources/seon/schemas/{seon.message,my.message}.edn`, `src/seon/cluster/agent.clj` mailbox and pass, `test/seon/cluster/message_test.clj`); after the one-starter cut | `seon.cluster.message-test/a-send-is-one-fact-on-the-senders-branch` (no offer, no other branch written; `seon.profile/calls-of` of `clojure.core.async/offer!` zero in `send!`; under 25 ms) · `…/root-delivers-a-sent-message-and-records-its-receipt` (after two passes the recipient's branch holds it, main holds it once by id with `received-at`; a third pass offers nothing) · `…/a-message-to-a-stopped-agent-waits-as-data-and-starts-no-one` (no `received-at`, no graph starts; delivered when the recipient's attempt runs) · `…/a-message-to-no-agent-is-an-undeliverable-finding` |
| U6 | Rename `frontier` → `ready`, `frontier-rules` → `ready-rules`, every caller, test and docstring (`src/seon/task.clj`, `src/seon/cluster/agent.clj`, tests, vocabulary) | the ready tests stay green; `seon.task/frontier` resolves nowhere |
| U7 | `seon.fault/record!` adds `:seon.error.occurrence/attempts` from the recording world; P5's proof reworded to the next push (`src/seon/fault.clj`, `test/seon/fault_test.clj`) | `seon.fault-test/a-fault-on-a-branch-is-recorded-once-there-and-counted-on-main-at-the-next-push` (one branch transaction; main count 1 after the push; a second attempt's same signature → 2; an occurrence naming no attempt of this branch is not pushed) |

**Order:** U0 → U1 → (U2, U4, U7 by file) → U3 → U6 → U5 (after #8's one-starter cut; until then P3's sender offer stays). U1 and U3 are an Opus lane
(the one path); U0, U2, U4, U6, U7 Sonnet on this spec; U4's fork bump is a platform slice. `src/seon/cluster/agent.clj`,
`src/seon/task.clj`, `test/seon/cluster/db_flow_test.clj`, `src/seon/cluster/message.clj` and the attempt schema are held
by lane `p4-branch-writes`: their task and schema edits are the committed patch `hybrid-push-p4.patch`, and U2, U3, U5 and
U6 wait for its release or go to it as patches.

## 7. Owner questions

1. **`:datahike.gc/roots` in the fork** (U4) rather than a `force-branch!` ref per push (18 ms each): confirm the fork change.
2. **Findings' home:** off the open merge request, else off a `:running` root problem task whose proof reads that none is open (recommended), or always a problem task?
3. **Re-derived delivery:** root re-offers a message at every pass until `received-at` reaches main, so a crash between the offer and the recipient's write delivers late rather than never; a recipient that never runs again leaves the message on main as data. Confirm this replaces hybrid-agent-world's "in-flight loss is accepted".
4. **An unowned edit is a finding and never lands**, even when root would have accepted it; root re-applies it by hand from the finding's shown values. Confirm no "root approves the finding → push it" path (that would be a second writer path).
