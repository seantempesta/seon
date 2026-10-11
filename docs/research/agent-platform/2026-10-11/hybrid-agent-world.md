---
title: "Hybrid: what an agent's world holds and where its writes go"
type: research
status: hybrid of plan-agent-world-opus.md (610add01d) and plan-agent-world-sol.md (619800483) over dig-agent-world.md (f1b42b836), revised to the owner's direction of 2026-10-11 (independent agents, one copy-back at the end, branch deleted); phases filed as tasks; design only
basis: HEAD 610add01d read by symbol (`seon.cluster.message/send!`, `seon.task/main`, `seon.turn/end-task-call`, `seon.context/held-task`, `seon.cluster.wake/route!`, `seon.db/transact-call`, `seon.cluster.source/merge-base`, `seon.schema`'s partition check, `seon.fault/record!`, `seon.task/delete-branch!`, `seon.cluster.registry/collect!`, `retention-cutoff`, `seon.maintenance/collect!`); `reference-code/datahike` `gc.cljc`, `versioning.cljc`; Opus's default probes (disposable branch, deleted) reused as evidence; `runtime_status` on default 2026-10-11 (`:faulted`, pre-existing: 2 open faults, 2 failed tests, cache 495 of 512 MB)
---

# What an agent's world holds and where its writes go

## 1. The design in one paragraph

**An agent is independent: its world holds one connection, its own branch, forked from main's immutable value at
launch; everything it does (evaluations, task refinements, child tasks, test evidence, recorded errors, its end) is
data on that branch, written by its own forms through the same writer inside its own Flow; it never calls a platform
function that reaches main or another branch.** Root's world is the same value over main, the only difference. Nothing
moves while an agent runs. **At its end, one point, the system copies back exactly the declared facts**: entity
schemas declare a third partition value, **`:seon.cluster`**, and `db<->flow` (holding main and the roster, woken by
the end datom through the existing route) runs the pure **`seon.cluster.pick/cherry-pick`** over the branch since its
fork point and transacts the result on main as **one plain identity-matched transaction** with provenance in tx
metadata (branch, end commit) — matched by identity attribute, never entity id (Opus measured ids diverging after a
fork: 60192 vs 60204), never `merge-db!`/`:parents` (§2). Program changes reach main only through the merge request
root works. Then the agent's Flow is stopped and its branch deleted (`d/delete-branch!`), so `gc-storage!` sweeps its
unique commits, index nodes and blobs at the next collection: **the agent's whole run costs main one transaction and
leaves the store nothing.** Mid-run messages between agents are deleted (§4): an agent that needs something ends with a
reason; root's answer is a refined task and a new attempt; intents reach a running agent as Flow verbs, not data.

## 2. Point by point: agree, differ, choice

| Point | Opus | Sol | Choice and reason |
|---|---|---|---|
| One custody per world, root = same world over main; no main member; `task/main`, `main-connection`, `send!`'s lookup, agent-side `branch-connection` deleted | yes | yes | **agreed**; verified `seon.batch/world-members` has no main member; `send!` queries `:seon.agent/branch` then `db/branch-connection` |
| **When data moves** | continuously: a pick proc wakes on every cluster datom, up then down | continuously, coalesced per commit | **neither: once, at the end** (owner). The agent is a function of its branch; main is written once per attempt; no pick proc, no watermark, no down-pick while running, no second writer on a running agent's branch; a crash before the copy leaves the end on the branch, re-derived at the next prime. The pure function is Opus's; the proc and the watermark disappear |
| Rule on entity schemas | third partition value `:seon.cluster` | `:seon.data/movement`, `:seon.data/merge` properties | **Opus**: `:seon.program/partition` exists and `seon.schema`'s check already refuses an identity-bearing entity without `#{:seon.program :seon.data}`; one more value is accretion; with no down-pick no recipient edge is needed; merge semantics need no property (appended kinds have no base; keyed values three-way) |
| Transport primitive | plain `transact!` + tx-meta provenance; never `merge!` | `transact!` with `:parents` (Datahike merge commit) | **Opus**, two verified grounds: `datahike.gc/reachable-in-branch` follows `datahike/parents` of in-range commits, so a parented copy would pin the deleted branch's lineage to main and reclaim nothing (fatal to the owner's goal); `compare-branch`'s base is `branch-base-t` today and the attempt's fork point after H8, so lineage adds nothing the program merge uses |
| Fork point | the attempt's own main commit (unsure for successors) | historical fork + import of the live assignment | **main's head after the attempt write** (R1's `attempt-tx`, hybrid H7): the branch inherits its task, attempt and agent row and needs no import; a successor forks main too, since its predecessor's branch is deleted and its program reaches main only by root's merge. Sol's import is not needed |
| Conflicts | three-way per `[identity attribute]`, keep main, problem task by identity | same, plus three cautions | **both**; base = main's value at the fork point (`as-of` the fork commit on the branch's own lineage); Sol's cautions (basis advance ≠ acceptance; retractions participate; no negative-datom replay) are proof rows |
| Messages | up to main, down along `:seon.message/to` | same, wake after durable target write | **deleted mid-run** (§4); a message written on a branch is a `:seon.cluster` fact copied at the end (a note to root), nothing more |
| Agent row (Q3) | id, branch, attempt main's; runtime bookkeeping on the branch | roster authoritative on main | **agreed**: `:seon.data` on main, inherited at fork; `answered-t`, current namespace, disposition stay on the branch and die with it; status reads a running branch on purpose; `:seon.agent/admission-closed-tx` writes go |
| Recorded errors | `:seon.error.occurrence` is `:seon.cluster`; counts add | problem occurrence → main | **both**: `record!` writes once, on the world's connection (its "writes twice" edge case goes); the copy adds `count` through one `:db.fn/call` |
| Test members | unsure | `d/with` inside one run attempt; `settlement-connection`'s escape goes | **Sol** (round-1 Q1): members write nothing durable; the run's evidence is `:seon.program` on the run branch; the run's end is copied |
| Two-custody fallback | only if latency breaks a proof | contradicts the owner | **not built**; with no mid-run movement there is no latency to fall back from |

## 3. Each fact kind: partition, home, writer, what the end copies

| Fact | Partition | Written on | By | At the end |
|---|---|---|---|---|
| agent row, attempt row (`:seon.task/assignment`, `/attempt-of`), task, intents, config, roster | `:seon.data` (attempt: `:seon.cluster`) | main, `db<->flow` in root's world (`attempt-tx`); inherited by the fork | root's world | the attempt's end (`:seon.task.attempt/end`, `/reason`, `/target`, `/commit`) copied |
| evaluations, calls, turns, answered `t`, current namespace, live-result handles | `:seon.data` | the branch | the recording transaction | deleted with the branch (history answers while the window lasts) |
| task refinement (intent, must-read, evidence, stage decision); child tasks, `needs` | `:seon.cluster` (`seon.task/task`) | the branch (`seon.task/written` on custody; `my.task/*`) | the agent | copied, three-way per attribute against the fork point; new children have no base |
| merge request (`seon.task/merge-request-row`) | `:seon.cluster` | main, by `db<->flow` on a copied `:done` end | root's world | is the copy's consequence |
| message (a note to root) | `:seon.cluster` (`seon.message/message`) | the branch (`send!` on custody) | the agent | copied; root reads it with the end |
| recorded error (`seon.error.occurrence`) | `:seon.cluster` | the branch of the world it happened in | the entrant boundary | copied; `count` adds |
| program (functions, tests, schemas, namespaces), test evidence keyed by digests | `:seon.program` | the branch | `defn`, `seon.test/run` | root's merge (`accept-merge!`) from the end commit, before the branch is deleted |
| copy provenance (`:seon.pick/source-branch`, `/source-commit`) | tx metadata (declared like `:seon.db/user`) | main | the end copy | is the record |

## 4. The mechanism, the messages decision, cost and storage

**`seon.cluster.pick/cherry-pick`** `[source-db base-db target-db projection] → {:tx-data … :conflicts …}`, pure:
reads `seon.program/cluster-attributes` (sibling of `program-attributes`, same `projection-cache-value` pattern), the
source's datoms since the fork for those attributes (`d/since` on the branch; the fork point `t` is main's, repeated in
the branch's own lineage), groups by entity, translates to identities (`seon.db/identity-attributes`; components nested
with their owner; refs as lookup refs), classifies per `[identity attribute]` against base (branch `as-of` the fork) and
ours (main now): unchanged → nothing; only theirs → assert or retract; only ours → keep; both and different → keep ours
plus a conflict row `{entity attribute base ours theirs}`; `count` on an occurrence is `[:db.fn/call …]`.
**The end.** `seon.turn/end-task-call` writes the end on the branch in the agent's last transaction. `:seon.task.attempt/end`
is a listened datom; the branch's route (every hosted connection is routed today) offers `db<->flow` a wake; its
pass finds attempts whose branch holds an end main lacks and, per attempt: `cherry-pick` → one `seon.db/transact!` on
main with `:tx-meta` provenance → one problem task per conflict by identity `seon.id/id [:pick-conflict entity attribute]`
(a repeat writes nothing) → `:done` plans the merge request (root works it on main; its diff reads the end commit) →
`flow/stop`, `join-graph!`, release the SCI context, `d/delete-branch!` (through `seon.task/delete-branch!`'s detached io
work, which already takes the roster permit) once the merge request is closed or the end is not `:done`. Idempotent by
identity: a crash after the copy and before the deletion re-runs an empty copy; a crash before it re-derives from the
branch. Root's end is the identity (its branch is main).

**Mid-run messages: deleted.** Today's channels are `my.message/send` (any agent → any agent, written on the recipient's
branch) and the end message to root. Under runtime-follows-db a nominal end wakes nobody; problems are tasks; a question
is a task with an answer schema; intents are Flow verbs the reconciler applies to the live graph (pause, stop, cancel)
without moving data. So: an agent that needs root ends (`:given-back` with a reason, or a question end); root's reply is
a refinement plus a new attempt (a new branch from main, which then holds the reply); an agent that wants to leave a note
writes a message on its branch, copied at its end. Two agents never talk mid-run: siblings run in parallel and meet
through `needs` and their ends. No addition is needed. Outside agents (`eval_clj`) are root on main and keep everything.
The one thing lost is a running child seeing a parent's refinement (hybrid-start-a-flow's Q2): it sees it at its next
attempt, by design.

**Cost, against the plans.** End copy: one derivation (Opus measured 3.2 ms cold, `d/with` 2.3 ms) and one main
transaction (raw 13 ms, `seon.db/transact!` 15–25 ms) per attempt, proportional to the attempt's changed cluster datoms,
plus `delete-branch!`; per eval request nothing (no pick wake, no listener work beyond today's matcher lookup). Opus's
continuous pick: the same per burst (one main transaction per branch per wake, two for a message), a proc, a watermark
and a second writer on recipient branches. Sol's: three commits per message plus a durable movement basis. The end copy
is strictly less machinery and the fewest main writes; its price is no mid-run conversation, which §4 shows nothing needs.

**Storage reclaimed per agent, and how.** `d/delete-branch!` removes the branch from the store's known branches; the
next `seon.cluster.registry/collect!` (`gc-storage!`, mark from every roster branch head through commits newer than
`retention-cutoff` under `:seon.config.db/snapshot-window-ms`, sweep before the safe point) finds nothing reaching the
deleted branch's commits, so everything unique to it is swept: every commit record its transactions wrote, the index
root and path nodes those transactions created (shared nodes stay with main), its history indexes, and the konserve
blobs only its facts named (`seon.maintenance/collect!` verifies retained branches and blobs together). What survives is
exactly the one copied transaction on main and what root merged. The per-agent number is a red cost row (P2): the
store's key count and bytes after `collect!` with and without a deleted ten-evaluation attempt differ by the copied
transaction only. Today nothing is reclaimed while branches outlive their agents.

**Composition.** `start-flow!` (H7): `fork!` from the attempt's main commit, world, one SCI fork, graph; no import.
`db<->flow` (R1): the end copy is one more action of `plan`. Test members: `d/with` on the run branch; `cherry-pick` is
tested on values with `d/with` targets. Q3: the agent row is main's; the branch inherits it and nothing writes it back.

## 5. Reused, changed, deleted (by symbol)

**Reused.** `seon.turn.world/world` (one connection); `seon.call-preparation/supply`, `supply-from-world`;
`seon.batch/world-members` (no new member); `seon.cluster.wake/route!`; `seon.program/program-attributes`'s pattern,
`three-way`'s classification; `seon.db/transact!` (`:tx-meta`), `identity-attributes`, `with`, `since`, `as-of`;
`seon.schema`'s partition check; `seon.id/id`; `seon.cluster.registry/fork!`, `collect!`; `seon.task/delete-branch!`;
`datahike.api/delete-branch!`, `gc-storage!`; `seon.flow/start-flow!` (H7); R1's `db<->flow` and `plan`.

**Changed.** Entity schemas `seon.message/message`, `seon.task/task`, `assignment-row`, `seon.error.occurrence/occurrence`:
`:seon.program/partition :seon.cluster`; the check accepts `:seon.cluster` and requires an identity attribute for it (a
declaration rule, not a runtime check). `seon.program/cluster-attributes` new. `send!`: its connection only, no
recipient. `seon.turn/end-task!`: `end-task-call` and `end-summary-tx` on custody and nothing else. `seon.task/written`,
`assign!`, `adopt-tasks-tx`: the given connection. `seon.context/held-task`, `ended`, `end-summary-tx`,
`current-state`: the world's database only. `seon.turn/admission-closed?`: the end on the attempt. `seon.fault/record!`:
one write. `seon.test/run`: members under `d/with`. `seon.flow/plan`: the end action (copy, merge request, stop, delete).
Vocabulary rows first: **cherry-pick**, **`:seon.cluster`**, **end copy**.

**Deleted.** `seon.task/main`, `main-connection`, `written`'s indirection; `send!`'s recipient lookup,
`:seon.message/unknown-recipient` and cross-agent delivery (`my.message/send` to an agent); `seon.turn/closed-on?`'s
main half and every `(task/main database)` in `task-end`, `end-message`, `end-merge-request`, `next-agent-work`,
`task-proofs`; the four in `seon.context`; `:seon.agent/admission-closed-tx` writes; `record!`'s cluster copy;
`seon.turn.world/settlement-connection`'s requester escape; agent-side `seon.db/branch-connection`; the end's follow-ups
in the agent's own turn (`program-request-merge!`, `land-end!`, the agent calling `task/delete-branch!`). Not built:
Opus's `pick-step`, watermark and down-pick; Sol's `move-data!`, `movement-tx`, `:seon.data/movement`, `:seon.data/merge`.

## 6. Phases (about fifteen minutes each, one owner, red first; filed as tasks)

| # | Slice (owned files) | Red proof and assertion |
|---|---|---|
| P0 | **the partition value.** `:seon.cluster` on the four schemas, `seon.program/cluster-attributes`, the check extended, vocabulary rows (`resources/seon/schemas/{seon.message,seon.task,seon.error.occurrence}.edn`, `src/seon/program.cljc`, `src/seon/schema.clj`, `docs/seon/architecture/vocabulary.md`, `test/seon/program_test.clj`); platform slice, `--test` | `seon.program-test/cluster-attributes-are-the-declared-cluster-entities`: on the packaged projection `cluster-attributes` ⊇ `#{:seon.message/to :seon.task.attempt/end :seon.task/intent :seon.error.occurrence/count}` and is disjoint from `program-attributes`; a fixture `:seon.cluster` schema with no identity refuses with `:seon.schema/member :seon.db/identity`; derivation under 5 ms warm |
| P1 | **the pure copy.** `seon.cluster.pick/cherry-pick` (`src/seon/cluster/pick.clj`, `test/seon/cluster/pick_test.clj`) | table on values, `d/with` targets: `seon.cluster.pick-test/an-attempts-cluster-facts-copy-onto-main-by-identity` (ids differ; main pulls the end, the refined intent and a child task by identity); `…/a-value-changed-on-both-sides-is-a-conflict-not-a-write` (main keeps ours; one conflict row with three values; the entity's other attribute still copies); `…/program-and-runtime-facts-never-copy` (a `defn`, an evaluation and a turn yield empty tx-data); `…/a-copy-of-an-unchanged-branch-is-empty`; `…/a-retraction-on-the-branch-retracts-on-an-unchanged-main-only`; cost row: a ten-evaluation attempt under 10 ms / 5 MB through `seon.profile/measure` |
| P2 | **the end copies, then the branch dies.** `db<->flow`'s end action: copy, provenance, conflicts as problem tasks, merge request on `:done`, stop, delete (`src/seon/flow.clj`, `src/seon/turn.clj` `end-task!` trimmed, `test/seon/flow_test.clj`); Opus lane (one path) | `seon.flow-test/an-ended-attempt-reaches-main-in-one-transaction-and-its-branch-is-deleted` (before the pass main lacks the end; after it one main transaction carries end, refinements, note and provenance; the roster no longer lists the branch; root's `current-state` shows the end); `…/a-crash-between-the-end-and-the-copy-copies-once` (a second pass transacts nothing); `seon.cluster.registry-test/a-deleted-attempts-storage-is-reclaimed-by-the-next-collection` (store key count and bytes after `collect!` equal the baseline plus the one copied transaction; cites `gc.cljc` reachable-in-branch) |
| P3 | **messages on custody, notes only.** `send!` writes its connection; cross-agent delivery deleted (`src/seon/cluster/message.clj`, `src/my/message.clj`, `test/seon/cluster/message_test.clj`) | `seon.cluster.message-test/send-writes-only-the-senders-branch` (the message is on the branch, absent from main until the end copy; `seon.profile/calls-of` of `seon.db/branch-connection` zero; under 25 ms); a `to` naming another agent answers the refusal that names ends and tasks |
| P4 | **tasks and context on custody.** Delete `seon.task/main`, `main-connection`; `written`, `assign!`, `adopt-tasks-tx`, the context readers use the world's connection (`src/seon/task.clj`, `src/seon/context.clj`, `src/seon/turn.clj` remaining reads, `test/seon/task_test.clj`) | `seon.task-test/a-refinement-on-a-branch-reaches-main-only-at-the-end` (main's intent unchanged while the attempt runs, equal after the end copy; a root edit of the same attribute meanwhile → main keeps root's, exactly one problem task); `seon.profile/calls-of` of `seon.task/main` zero (deleted) |
| P5 | **recorded errors once.** Occurrence `:seon.cluster`; `record!` one write; `count` adds (`src/seon/fault.clj`, `src/seon/cluster/pick.clj` the one `:db.fn/call`, `test/seon/fault_test.clj`) | `seon.fault-test/a-fault-on-a-branch-is-recorded-once-there-and-counted-on-main-at-the-end` (one transaction on the branch; after the copy main holds the signature with count 1; a second attempt's same signature → 2, never an overwrite) |
| P6 | **root differs only by its world; members `d/with`.** Delete `settlement-connection`'s escape; `seon.test/run` members under `d/with` (`src/seon/turn/world.clj`, `src/seon/test.clj`, `test/seon/test_test.clj`, `test/seon/cluster/pick_test.clj`) | `seon.cluster.pick-test/root-and-an-agent-differ-only-by-their-worlds-connection` (the same `my.task/refine!` form through the one path: over a fixture main it writes main; over a fixture branch it writes the branch and equals main's after the end copy); `seon.test-test/members-return-reports-without-writing-another-world` (fixture writes stay in each `d/with`; the run branch's and default's heads unchanged) |

**Order.** P0 → P1 → (P3, P4, P5 in parallel by file) → P2 (after R1's `db<->flow` and S3's end on the attempt; until
R1 lands, `end-task!`'s existing follow-up calls the copy once) → P6. Lanes: P0 platform (`--test`); P1, P3–P6 Sonnet on
clear specs; P2 Opus (the one path). Measure P2's end on default with an attempt of ten evaluations before P3 lands.

## 7. Risks

1. A branch deleted before root merged its program loses the program: deletion waits for the merge request to close
   (`:done`) or happens at once (other ends); the merge task holds the end commit until then (retention window applies:
   a merge request older than `snapshot-window-ms` must keep its branch, which `retention-cutoff` already guarantees for
   roster branches).
2. A long attempt's conflict surface grows with its length (main moves meanwhile): three-way per attribute names it;
   the policy is Q4.
3. A running child cannot see a parent's refinement until its next attempt: by design; TASK-CHAIN stages are attempts.
4. A ref from a cluster entity to an identity-less, non-component entity cannot copy: refused at publication (P0).

## 8. Owner questions

- **Q1 No mid-run messages.** Confirm: `my.message/send` to another agent is deleted; an agent reaches root only by
  ending; root reaches a running agent only through intents (Flow verbs). Recommended: yes; the smallest addition, if a
  case appears, is a question end whose answer is the next attempt's task.
- **Q2 When the branch is deleted.** At once for every end but `:done`; for `:done`, when root closes the merge request
  (recommended), or at once with the merge reading a retained end commit (simpler, but a commit not on any branch is swept
  after the window, so the merge must land inside it).
- **Q3 The agent record.** Id and branch on main, inherited at fork; `answered-t`, current namespace, disposition live and
  die with the branch; status reads a running branch on purpose. Does root need any of them on main? Recommended: no.
- **Q4 Conflict policy.** Keep main's value and write a problem task (recommended, git's behaviour) or let the holder's
  edits win on its own task's fields?
- **Q5 History of ended agents.** Everything but the copied transaction is gone after the window. Is the end reason plus
  the merged program enough evidence, or should the end copy also carry a bounded summary (turns, calls, cost) as
  `:seon.cluster` facts on the attempt? Recommended: the attempt's cost facts (calls, ms, MB), nothing else.
