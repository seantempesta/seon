---
title: "Hybrid: merge requests and auto-merge — an attempt's owned facts reach main through its merge request as they change"
type: research
status: hybrid of plan-push-opus.md (2bc33af8e) and plan-push-sol.md (d2cc4705f) over verify-astra.md (246dd0bd9) and astra-overnight-review.md, under hybrid-agent-world.md and AGENTS.md "Agents are compartments"; revised in place to the owner's decisions of 2026-10-12 (git's untrusted-contributor model: agents never push, everything reaches main through the attempt's one merge request; root's db<->flow auto-merges the rule-trusted owned parts as they change; code and unowned edits wait for root's review; `:datahike.gc/roots` in the fork; in-flight message loss accepted, one offer; root work items are child tasks of the root task handling the attempt, identity by key data); the file name is historical: the words are merge request, auto-merge, merge base; design only, phases filed as tasks
basis: HEAD 4dbd4aaf2 read by symbol (`seon.cluster.squash/squash-merge`, `copy!`, `add-count`, `collision-remap`; `seon.cluster.agent/db<->flow`, `land-ends!`, `unlanded-ends`, `record-error-end!`, `settle-kept-branches!`, `detached!`, `mailbox-step`, `plan`; `seon.task/request-merge!`, `merge-request-row`, `frontier-rules`; `seon.program/changed-identities`, `cluster-attributes`; `seon.db/entities-changed-after`, `changed-attributes`; `seon.cluster.registry/collect-and-inventory!`, `retention-cutoff`; `seon.cluster.wake/route!`), `reference-code/datahike` `datahike.gc/reachable-in-branch`, `datahike.db/SinceDB`; Opus's and Sol's default measurements reused (§4)
---

# Merge requests and auto-merge

## 1. The design in one paragraph

**Owner model (2026-10-12), git's untrusted contributor: an agent never pushes.** Its branch is its fork, and
everything it wants on main reaches main through **its one merge request**, the same one that carries its code. An
agent only transacts facts on its own branch (refinements, children, its end, outgoing messages, its merge request's
intent and description); it never performs an effect on main or another agent. The merge request's **contents are
derived**: the branch diff since its **merge base**, the last merged source commit recorded on main as
**`:seon.task.attempt/merged`** (git's remote-tracking ref, written in the same transaction as the facts it covers).
Root's **`db<->flow`**, woken by the branch's listen route on every tracked transaction, **auto-merges the
rule-trusted parts as they change**: the facts of entities the attempt owns (§3) under their declared
`:seon.cluster/conflict` rule, by one `[[:db.fn/call seon.cluster.squash/auto-merge-tx attempt head]]` on main. The
function receives the writer's own db (ours), reads `merged` (else the attempt's recorded fork commit, F), opens it
with `d/commit-as-db` as the base, runs `squash-merge` with theirs = the held immutable head, and returns one
transaction: the owned, unconflicted facts and `[:db/add attempt :seon.task.attempt/merged head-commit]`. Code,
anything the attempt does not own and a conflict on a trusted part are not auto-merged: they **wait in the same merge
request for root's review**, shown by its derived diff (base, ours, theirs with both commits), with no separate
record. This removes verify-astra #1–#4 at their source (the base advances atomically, so a repeat adds nothing; the
three-way runs on the writer's db, so a concurrent root edit waits as a conflict instead of being overwritten; no
marker, the end is just a merged fact), and a crash loses nothing: unmerged work is derived (head ≠ `merged`) and
the pass primes at `::flow/resume`.

## 2. Point by point

| Point | Opus | Sol | Resolution |
|---|---|---|---|
| One pusher proc, wake by route, writer function, moving base, derived lifecycle | yes | yes | **Agreed, renamed:** root auto-merges a merge request; nothing is pushed. |
| Where the base is recorded | `:seon.task.attempt/pushed` on the attempt row | the newest main transaction carrying source tx-meta, by main's tx order | **Opus, as `:seon.task.attempt/merged`.** One indexed fact per attempt, read by `pull` in the writer and by `unmerged`; the tx-meta provenance stays as each auto-merge's record. |
| `changed-identities` scans each changed attribute's whole history (9,451 datoms, 0.8 ms warm / 3.3 cold) | risk; since index later | proportional to pending commits, unmeasured | **Kept, with a cost row.** `seon.db/changed-attributes` drops every tracked attribute whose Datahike revision is equal at base and head, so an interval of evaluations scans nothing (≈4 ms with the base read). The scan is per *changed* attribute over that attribute's history, which grows with tasks, attempts and occurrences, never evaluations. `d/since` is the same walk (`datahike.db/SinceDB` filters the temporal index by `since-pred`). Bound: U1's row, scanned datoms recorded; a tx-ordered temporal index in the fork is filed only when a tracked attribute's history passes ~100k datoms. |
| No-history attributes (`count`, `last-at`) | base = retained `commit-as-db` of the merged commit | same | **Agreed:** the retained base commit holds the no-history value, so `:add` is exact (base 1, source 2, main 2 → 3; repeat → 3). `last-at` gets rule **`:max`** (`:seon.cluster/conflict` ∈ `#{:root :theirs :ours :add :max}`). |
| Retention of merge bases past `:seon.config.db/snapshot-window-ms` | derive at collection, nothing per push | close the fork gap: the extension returns keys, cannot mark a snapshot | **Owner: option A, the fork.** `datahike.gc/gc-storage!` gains **`:datahike.gc/roots`**, commit ids walked exactly like heads (`reachable-in-branch` already walks commit keys; the head lookup is its only branch-specific line). `collect-and-inventory!` passes main's `merged` and `fork` commits of roster branches. Zero cost per merge; no `force-branch!` (18 ms) and no second walker. |
| Batching (a report's db-after is the batch commit) | squash semantics | "the principal remaining question": intermediate states | **Squash semantics.** The auto-merge compares the base with the head at pass time; N commits become one main transaction and a changed-then-restored value yields nothing while the base advances. Intermediate states are unobserved by definition. |
| Messages | sender `offer!`s (P3) and the recipient's write is copied | archival record, no second delivery | **Owner: the sender only transacts; one offer; in-flight loss accepted.** `send!` writes the message on the sender's branch and answers its id (`keep?` goes). Root's pass sees the new message fact, `offer!`s it **once** to the recipient's inbox in the roster (the recipient's mailbox writes it on its branch, P7) and auto-merges the record onto main (owned by `:seon.message/from`; the recipient's copy converges by `:seon.message/id`). No re-offer loop: a crash between offer and write loses it and the sender resends by the same id. An offer that cannot be made (no inbox, closed, full) is answered as data: root asserts `:seon.message/undeliverable` (recipient, intent, cause) on the message entity on main, which the sender's merge request shows on its ours side and its context renders; root never writes the sender's branch (compartment law). |
| A merged message on main starting an agent | risk 4 | — | **Tied to the one starter (verify-astra #8, hybrid-step-semantics).** Until `agents-to-start` and the held exemption are deleted, a record on main would start a stopped recipient; U5 lands after that cut. |
| Ownership of what auto-merges | every tracked coordinate changed | same | **Owner: ownership as data (§3).** |
| Root's work items | one problem task per conflict by identity | runnable merge decision | **Owner: child tasks of the root task handling the attempt** (its merge request, else root's problem task for an error end), identity `seon.id/id` over their key data, so a duplicate cannot exist. |

## 3. Ownership: the rule-trusted parts of a merge request

The owning fact is read inside `auto-merge-tx` on theirs (the branch head), one pull per changed entity, never a
guard around the agent and never "whatever changed on the branch":

| Entity | Owning fact | Set where |
|---|---|---|
| its task and the task's descendants | `(self-or-under task ?t)` over `:seon.task/children` (`seon.task/ready-rules`), task = the attempt's `:seon.task/attempt-of` | the task tree, at creation |
| a runtime task it created outside its tree | `:seon.task/created-by` = its agent (exists, `seon.task/create!`) | creation |
| its attempt row (end, reason, target, end commit); its merge request's intent and description | `:seon.task/agent` = its agent; `:seon.task/of-assignment` = the attempt | `write-attempt!`; `request-merge!` |
| a message it sent | `:seon.message/from` = its agent; identity `:seon.message/id` | `send-call` on its branch |
| an error occurrence it hit (`count` `:add`, `last-at` `:max`) | **`:seon.error.occurrence/attempts`** (new, cardinality-many ref); `seon.fault/record!` adds the recording world's attempt on every hit | the entrant boundary |

Owned and unconflicted → auto-merged. Everything else (program rows, an edit to an unowned entity, a `:root`
conflict on an owned one) stays in the merge request's derived diff for root; a later head may resolve it (the
agent restores the value) or not. `:seon.error.occurrence/evaluation` is a `:seon.cluster.eval/id` value
(`:seon.db/reference-to`), so a merged occurrence carries no dead ref.

## 4. Fact on the branch → root's action from main; lifecycle; cost

| The agent transacts (its branch) | Root's `db<->flow` executes (main) |
|---|---|
| task refinement, children, `needs` | auto-merge: owned by the tree, three-way per `[e a]` against `merged` |
| its end (`end-task!`: end, reason, target, commit) | auto-merge the end; then derived: `:stop` the graph; the merge request now carries its final diff for root (`:done`) or root's problem task (error end); `:delete-branch` when nothing holds it (below) |
| its merge request's intent/description | auto-merge (owned); the contents are derived, never written |
| an outgoing message (`send-call`) | one `offer!` to the recipient's inbox; auto-merge the record; undeliverable → data on the message on main |
| an error occurrence | auto-merge: `count` `:add` = theirs − base, `last-at` `:max`, collision remapped to main's eid **whether or not base holds it** (today's `when-not base` misses merged entities) |
| a change to an entity it does not own; program rows; a `:root` conflict | nothing lands; waits in the merge request's diff for root |
| evaluations, calls, turns, answered `t` | nothing |
| a predecessor's end on main | `plan` writes the successor's attempt, forks the recorded end commit (F), starts the graph: the one starter |

**Ordering and idempotence.** One proc, branches in sorted order per pass, each in its commit order (each
auto-merge names its head; the next base is that head). `merged = head` yields `[]` and the caller skips the
transaction. **Lifecycle, derived by `plan`** (replaces `land-ends!`'s marker, catches and `detached!`): `:stop` for a
started graph whose attempt has an end on main; `:delete-branch` for a roster branch whose attempt has an end,
`merged` = head, its merge request closed (merged or closed by root), no successor waiting on its end commit
(`successor-waits?`) and `keep-ended-branches?` false. The merge request is opened with the attempt (one per
attempt; `seon.task/request-merge!`'s per-commit recurrence key becomes the attempt), so it exists from the first
auto-merge and root's items are its children. Each step is idempotent. **Cost** (Opus on default; Sol's values
agree): base open 3.4 ms; `changed-identities` 0.8 ms warm / 3.3 cold; squash 0.15 ms; as `[:db.fn/call …]` 1.4 ms;
one main transaction 19 ms. **An auto-merge is ≈5 ms in the writer plus one main transaction**, one per coalesced
tracked change per branch; evaluations never cost one.

## 5. Deleted, renamed

**Deleted:** `seon.cluster.squash/copy!`, `conflict-task!`, `squash-merge!` and `selection` with
`selected-coordinates`; `add-count`; `collision-remap`'s `(when-not (seq (d/datoms base …)))`;
`seon.cluster.agent/unlanded-ends`, `branch-end`, `record-error-end!`, `ended-problem!`'s "copy it by hand",
`land-ends!`, `settle-kept-branches!`'s marker query, `detached!`, the `land-ends!` try/catch in `db<->flow`; the
`:seon.squash/attempt` tx-meta and schema entry; `:seon.squash/copy`, `/datoms`, `/conflicts`, `/conflict`
(the merge request's derived diff shows conflicts); `:my.message/keep?`, the sender's `offer!` and its
`:seon.message/undeliverable` answer. **Renamed in one commit:** `seon.task/frontier` → `seon.task/ready`,
`frontier-rules` → `ready-rules`; `unlanded-ends` → `unmerged` ("head ≠ merged"). **Vocabulary:** auto-merge (new
row), merge request (contents derived; one per attempt), merge base (the last merged source commit, `merged`),
ready, unmerged (revised).

## 6. Phases (about fifteen minutes each; red first; filed as tasks)

| # | Slice (owned files) | Red proof, cost bound |
|---|---|---|
| U0 | Schema and words: `:seon.task.attempt/merged`; `:seon.error.occurrence/attempts`; `:max` in `:seon.cluster/conflict`, `last-at` declares it; `seon.message/message` → `:seon.cluster`, `:seon.message/undeliverable` as an attribute; delete `:seon.squash/attempt`, `/copy`, `/conflict`; vocabulary rows (`resources/seon/schemas/{seon.task.attempt,seon.squash,seon.message,seon.error.occurrence,seon.program}.edn`, `docs/seon/architecture/vocabulary.md`); platform `--test` | `seon.program-test/cluster-attributes-are-the-declared-cluster-entities`: `:seon.message/id`, `:seon.task.attempt/merged`, `:seon.error.occurrence/attempts` in the set; `last-at` → `:max` |
| U1 | `auto-merge-tx` pure in the writer, ownership split (`src/seon/cluster/squash.clj`, `test/seon/cluster/squash_test.clj`) | `…/a-repeated-auto-merge-adds-nothing` (collision 1→1; conflict row 6 stays 6; tx-data `[]`) · `…/an-advanced-branch-merges-only-its-new-delta` (base 1, source 2, main 2 → 3) · `…/a-root-edit-after-capture-waits-in-the-merge-request` (`seon.db/with` on a main edited after the head was captured: root's value stays; the derived diff shows base, ours, theirs with both commits) · `…/a-merged-collision-stays-remapped` · `…/an-edit-to-an-unowned-task-waits-for-root` (main unchanged; the diff lists it; the agent's own refinement still lands) · `…/last-at-takes-the-later-of-both-sides` · cost ≤ 8 ms warm, ten-evaluation branch, `seon.profile/measure`, scanned datoms recorded |
| U2 | `db<->flow` auto-merges: `unmerged`, the wake on tracked assertions and retractions, one merge request per attempt opened with it (`src/seon/cluster/agent.clj`, `src/seon/cluster/wake.clj`, `src/seon/task.clj` `request-merge!`, `test/seon/cluster/db_flow_test.clj`) | `…/a-running-agents-refinement-reaches-main-at-the-next-pass` (main holds the intent while the graph runs; `merged` = head; the merge request exists) · `…/an-evaluation-without-tracked-facts-writes-nothing-on-main` · `…/an-unmerged-branch-after-a-crash-is-merged-once-by-the-prime` · recording → main ≤ 100 ms; p99 writer latency with ten branches recorded |
| U3 | Derived lifecycle; delete marker, copies, catches, `detached!` (`src/seon/cluster/agent.clj` `plan`, `apply-plan!`) | `seon.cluster.db-flow-test/an-interrupted-landing-finishes-on-the-next-pass` · `…/a-conflicted-end-keeps-its-branch-until-root-decides` (branch kept while the merge request is open with a conflict; deleted after root closes it; the diff values read from the closed request after deletion and collection) |
| U4 | Retention roots: fork `:datahike.gc/roots`; `collect-and-inventory!` derives them from `merged` and `fork` (`reference-code/datahike`, `src/seon/cluster/registry.clj`, `test/seon/cluster/registry_test.clj`); platform `--test` | `…/a-merged-base-older-than-the-window-survives-collection` (0 ms window after untracked commits: the base and the predecessor's fork commit open; the next delta is exact) · fork `:clj-pss` green |
| U5 | **P3 converted:** `send!` writes the message on the sender's branch; `db<->flow` offers each new message once and merges its record; undeliverable as data on main's message entity (`src/seon/cluster/message.clj`, `src/my/message.clj`, `resources/seon/schemas/{seon.message,my.message}.edn`, `src/seon/cluster/agent.clj`, `test/seon/cluster/message_test.clj`); after the one-starter cut | `…/a-send-is-one-fact-on-the-senders-branch` (no offer, no other branch written; `seon.profile/calls-of` `clojure.core.async/offer!` zero in `send!`; under 25 ms) · `…/root-offers-a-sent-message-once-and-merges-its-record` (one offer; the recipient's branch holds it; main holds it once by id; a second pass offers nothing) · `…/an-undeliverable-message-is-answered-as-data-and-starts-no-one` |
| U6 | Rename `frontier` → `ready`, `frontier-rules` → `ready-rules` (`src/seon/task.clj`, callers, tests, vocabulary) | the ready tests stay green; `seon.task/frontier` resolves nowhere |
| U7 | `seon.fault/record!` adds `:seon.error.occurrence/attempts` (`src/seon/fault.clj`, `test/seon/fault_test.clj`) | `seon.fault-test/a-fault-on-a-branch-is-recorded-once-there-and-counted-on-main-at-the-next-auto-merge` (count 1 after the merge; a second attempt → 2; a repeat adds nothing; an occurrence naming no attempt of this branch waits) |

**Order:** U0 → U1 → (U2, U4, U7 by file) → U3 → U6 → U5 (after the one-starter cut). U1 and U3 Opus (the one
path); the rest Sonnet; U0 and U4 platform slices. The attempt schema, `src/seon/task.clj`, `src/seon/cluster/agent.clj`,
`test/seon/cluster/db_flow_test.clj` and `src/seon/cluster/message.clj` were held by lane `p4-branch-writes` (released
since): their task and schema edits are the committed patch `hybrid-push-p4.patch`, applied by U0/U2/U6.

## 7. Decided (owner, 2026-10-12)

Agents never push; the merge request is the one channel and its contents are derived. `:datahike.gc/roots` in the
fork (option A). Messages: one offer, in-flight loss accepted, undeliverable answered as data. Root's work items are
children of the root task handling the attempt, identity by key data. Open: none.
