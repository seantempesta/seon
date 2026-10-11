---
title: "Plan (Opus): what an agent's world holds and where its writes go"
type: research
status: design round 2, Opus planner; independent of plan-agent-world-sol.md (unread); design only, no code
basis: HEAD f1b42b836 read by symbol; dig-agent-world.md, hybrid-start-a-flow.md, runtime-follows-db.md §3b; owner
  direction relayed by root 2026-10-11 ("agents fully running in these flows, not calling platform functions";
  "unless it's a root agent"); JVM probes on default (disposable branch, created and deleted, main untouched)
---

# What an agent's world holds and where its writes go

## 1. The design in one paragraph

**An agent's world holds one connection, its checked-out branch, and every write it makes lands there. The one
difference between root and any other agent is which branch that is: root's world holds main.** No function an
agent calls reaches main: `seon.task/main`, `main-connection`, the `:seon.agent/branch` lookups in
`seon.cluster.message/send!` and `seon.turn`, and every agent-side `seon.db/branch-connection` are deleted. Facts that
belong to the cluster (an attempt's end, task refinements and child tasks, merge requests, messages, recorded errors)
are written by the agent as ordinary data on its own branch, inside its own Flow, through the same writer as any other
form. Getting them to main is **git-like movement of data, done by the system**: entity schemas already declare
`:seon.program/partition` (`:seon.program` merges through root's merge; `:seon.data` stays where written); this plan
adds a third value, **`:seon.cluster`**, for data whose record is the cluster's. One cluster proc,
`seon.cluster.pick/pick-step`, beside `db<->flow` in the cluster graph, **cherry-picks** `:seon.cluster` entities from
each open agent branch onto main by identity (never by entity id, never with lineage), recording the source branch,
commit and `t` as transaction metadata, which is also its watermark; a value changed on both sides since that
watermark is a conflict and becomes a problem task, as in git. From main, an entity whose declared listen edge
(`:seon.wake/listen`, e.g. `:seon.message/to`) names an agent on another branch is cherry-picked onto that agent's
branch, where its existing route wakes it. For root, main is both source and target: the pick is the identity, and
root's writes are on main at once. `start-flow!` builds the world with the branch connection exactly as the hybrid
says; `db<->flow` reacts to picked facts on main (a picked `:done` end requests the merge; a `:given-back` end deletes
the branch) instead of the agent's turn doing those effects. Tests inject a different main as data: the pure
`seon.cluster.pick/cherry-pick` takes database values, and a world is just a map whose connection a fixture chooses.

## 2. Probes (default, JVM mode, 2026-10-11; disposable branch `:carry-probe-opus-1`, deleted after)

| Probe | Result |
|---|---|
| root's world in JVM mode (`seon.batch/*world*`) | custody `:cluster-default`, i.e. main: root already differs only by its connection |
| `seon.batch/world-members` | 11 members by schema name; no member for main; `supply-from-world` reads only `seon.batch/*world*` (and `db/*conn*` for `:seon.db/db`) |
| `d/branch!` from main / `seon.cluster.store/open-branch!` | 53 ms / 5.0 ms (the second custody a two-connection world would hold: about 5 ms once, zero after) |
| write a message on the branch (raw `d/transact`) | 17 ms; its entity id on the branch **60192**, the same message picked onto main under `d/with` **60204**: ids diverge after a fork, so a pick must go by identity attribute, refs as lookup refs (`[:seon.agent/id "root"]`) |
| derive the pick (report datoms → identity-keyed maps, refs → lookup refs; unoptimised, cold) | 3.2 ms; `d/with` on main 2.3 ms; main then pulls `{:seon.message/to {:seon.agent/id "root"}}` |
| identity per candidate kind | `:seon.message/id`, `:seon.task/id`, `:seon.task/assignment`, `:seon.task/merge-request`, `:seon.agent/id` are `:seon.db/identity` with `:seon.program/row-schema`: the link the pick needs already exists |
| `datahike.versioning/merge!` | takes parents and caller-built tx-data; a parent makes the agent's head an ancestor of main, so the next program merge base (Datahike lineage, hybrid H8) would treat the agent's unmerged program as merged. **A pick is a plain `transact!` with provenance, never `merge!`** |

## 3. Each fact kind: where it lives, who writes it, by which function

| Fact | Partition | Lives | Written by (world member) | Function | Moves |
|---|---|---|---|---|---|
| agent row (id, checked-out branch, attempt) | `:seon.data` | main; forked into the branch | `db<->flow` in root's world, `:seon.db/connection` = main | `seon.task/attempt-tx` (S3) | never picked; the branch inherits it at fork |
| agent's runtime (answered t, current namespace, turns, evaluations, calls) | `:seon.data` | its branch | the agent, `:seon.db/connection` | the recording transaction (`seon.turn/evaluate-sources`) | never |
| attempt end (`:seon.task.attempt/end`, `/reason`) on the assignment | `:seon.cluster` | branch → main | the agent | `seon.turn/end-task-call` on custody | picked; the pick adds `:seon.task.attempt/commit` = the source commit from its provenance |
| end summary (outcome facts) | `:seon.cluster` | branch → main | the agent | `seon.context/end-summary-tx` on custody | picked |
| refinement of its own task (intent, must-read, evidence, stage decision) | `:seon.cluster` (`:seon.task/task`) | branch → main | the agent | `seon.task/written` on custody (`my.task/*`) | picked, three-way per attribute |
| child tasks, `needs` | `:seon.cluster` | branch → main | the agent | `seon.task/adopt-tasks-tx` on custody | picked (new identities: no conflict possible) |
| merge request (a task) | `:seon.cluster` (`:seon.task/merge-request-row`) | branch → main | the agent, or `db<->flow` on a picked `:done` | `seon.task/request-merge!` | picked; root works it |
| message | `:seon.cluster` | sender's branch → main → recipient's branch | the sender | `seon.cluster.message/send!` on custody | picked up, then down along `:seon.message/to` (its `:seon.wake/listen` edge) |
| message handled / acknowledged | `:seon.data` | recipient's branch | the recipient | `my.message/*` | never |
| recorded error (one per signature, at the task's owner) | `:seon.cluster` (`:seon.error.occurrence`) | branch → main | the entrant boundary in the agent's world | the boundary's recorder | picked; repeats count on main |
| test runs, reports, failures | `:seon.program` (results keyed by exercised digests) / `:seon.data` | the branch | the agent's proofs | `seon.test.runner` | only through root's merge |
| program (functions, tests, schemas, namespaces) | `:seon.program` | the branch | the agent | `defn` through the one path | only through root's merge |
| intents (`:seon.task/execution`), config, cluster execution | `:seon.data` | main | root / owner | `my.task/*`, `seon.config` | never down; `db<->flow` turns intents into Flow verbs |
| pick provenance and watermark | tx metadata | main (and a recipient branch for down-picks) | `pick-step` in the cluster's world | `seon.cluster.pick/pick!` | is the record |

**When.** Every agent branch connection opened by `start-flow!` registers the existing route (`seon.cluster.wake/route!`)
with one more target: `pick-step`'s `(sliding-buffer 1)` channel, offered when a report asserts a `:seon.cluster`
attribute (a wake says only look; about 0.2 ms per report today). The step derives everything from facts: the
watermark is the newest main transaction whose metadata names that branch; the pick reads the branch `since` that
`t` (one branch's own lineage, where `t` is ordered). A burst coalesces into one pick; an eval request writing no
`:seon.cluster` fact costs nothing beyond the listener. A crash between the agent's commit and the pick loses nothing:
the next prime re-derives from the watermark, and identity upserts make a repeat a no-op.

**Cost.** One main transaction per picked burst (raw 13 ms, `seon.db/transact!` 15–25 ms) plus a 1–3 ms derivation;
a message A → B costs about 40–60 ms end to end (two picks) against 15–25 ms for today's direct write. An agent's
recording transaction is unchanged; root pays nothing.

**Conflicts.** Base = the branch's value at the watermark (`as-of` its `t`), ours = main now, theirs = the branch now,
per `[identity attribute]`: equal → nothing; only theirs moved → write; only ours moved → keep; both moved and differ →
leave main's value, pick the rest, and write a problem task by identity `[:pick-conflict entity attribute]` carrying
the three values (`seon.program/three-way`'s shape over data). Appended kinds (ends, messages, children, errors) have
no base and cannot conflict.

**Two-custody world, compared honestly.** A world holding `:seon.db/connection` (branch) and a second member for main
costs ~5 ms once and has zero latency. But the agent's cluster facts then live outside its branch: restoring or
replaying the agent from a commit cannot restore them, a crashed or cancelled attempt leaves half its effects on main,
two writers race on task fields with no merge base, tests must inject two connections, and every agent function must
choose a custody per write. The pick costs one main transaction per burst and ~30 ms of message latency, and in return
an agent is a function of its branch: the hybrid's "start a Flow from a point in time" covers everything it did.
**Primary: the pick.** The two-custody world is the fallback only if measured pick latency breaks a conversation
proof (none known).

## 4. Reused, changed, deleted (by symbol)

**Reused.** `seon.turn.world/world` (unchanged shape: one `:seon.db/connection`); `seon.call-preparation/supply`,
`supply-from-world`; `seon.batch/world-members` (no new member); `seon.cluster.wake/route!` and its listened edges;
`seon.program/derived-program-attributes` (pattern for the partition read); `seon.program/three-way` (conflict shape);
`seon.cluster.store/open-branch!`; `datahike.api/transact` with `:tx-meta`; `seon.flow/start-flow!` (hybrid H7);
`seon.flow/db<->flow` and `plan` (R1).

**Changed.** Entity schemas: `:seon.program/partition :seon.cluster` on `seon.message/message`, `seon.task/task`,
`assignment-row`, `merge-request-row`, `seon.error.occurrence`; publication refuses a `:seon.cluster` entity schema
without an identity attribute (the pick needs one: a schema rule, not a runtime check). `seon.program` gains
`cluster-attributes` (sibling of `program-attributes`, same cache pattern). `seon.cluster.message/send!`: transacts
on its connection only. `seon.turn/end-task!`: writes `end-task-call` and `end-summary-tx` on custody and nothing
else; its follow-ups (`program-request-merge!`, `land-end!`, `task/delete-branch!`) move to `db<->flow` actions on a
picked end. `seon.task/written`, `assign!`, `adopt-tasks-tx`: transact on the given connection. `seon.context/held-task`,
`ended`, `branch-cost`, `end-summary-tx`, `current-state`: read the world's database only (the task is on the branch
because the fork point is the attempt's own main commit). `seon.turn/admission-closed?`: the branch's end on the
assignment, no main read. `seon.cluster.wake/route!`: the pick target. `seon.mcp` ad-hoc agents: an attempt written by
`db<->flow`, not `seon.cluster.host/home-call`. Vocabulary: record **cherry-pick** (git's word: apply an entity's
changes from one branch to another by identity, without lineage) and partition `:seon.cluster`.

**Deleted.** `seon.task/main`, `seon.task/main-connection`; the recipient-branch lookup in `seon.cluster.message/send!`
and its `:seon.message/unknown-recipient` refusal; `seon.turn/closed-on?`'s main half and every
`(task/main database)` in `seon.turn` (`admission-closed?`, `task-end`, `end-message`, `next-agent-work`,
`task-proofs`); the four `task/main` reads in `seon.context`; `:seon.agent/admission-closed-tx`
writes (the end on the assignment is the fact); `seon.cluster.host/home-call` (folded into `start-flow!`, hybrid H7);
agent-side `seon.db/branch-connection` calls (it remains for the cluster's own procs, which hold main and the roster).

## 5. Phases (about fifteen minutes each, one owner, red first)

**W0 — the partition value.** `:seon.cluster` on the five entity schemas; `seon.program/cluster-attributes`;
publication refusal for an identity-less `:seon.cluster` schema; vocabulary rows. Red proof
`seon.program-test/cluster-attributes-are-the-declared-cluster-entities`: on the packaged projection,
`cluster-attributes` ⊇ `#{:seon.message/to :seon.task.attempt/end :seon.task/intent}` and is disjoint from
`program-attributes`; a fixture schema declaring `:seon.cluster` with no identity refuses publication by name. Files:
`resources/seon/schemas/{seon.message,seon.task,seon.error.occurrence}.edn`, `src/seon/program.cljc`,
`docs/seon/architecture/vocabulary.md`, `test/seon/program_test.clj`. Platform slice (`--test`).

**W1 — the pure pick.** `seon.cluster.pick/cherry-pick`: `[source-db since-t target-db projection]` →
`{:tx-data … :conflicts …}`. Red proofs (table, values only, `d/with` for the target):
`seon.cluster.pick-test/a-message-written-on-a-branch-picks-onto-main-by-identity` (branch and main ids differ; main
pulls `{:seon.message/to {:seon.agent/id "root"}}`); `…/a-value-changed-on-both-sides-is-a-conflict-not-a-write`;
`…/program-and-runtime-facts-never-pick` (a `defn` and an evaluation on the branch yield empty tx-data);
`…/a-pick-of-an-unchanged-branch-is-empty` (idempotence). Files: `src/seon/cluster/pick.clj`,
`test/seon/cluster/pick_test.clj`.

**W2 — the pick proc.** `pick-step` in the cluster graph, woken by `route!`; watermark from main's tx metadata;
up-pick then down-pick along listened edges. Red proofs `seon.cluster.pick-test/a-message-from-an-agent-reaches-root-and-wakes-it`
(after one step main holds it once and root's mailbox was offered; under 60 ms warm);
`…/a-message-between-two-agents-lands-on-the-recipients-branch`; `…/a-restart-between-commit-and-pick-loses-nothing`
(step run against a fresh watermark read: one message, watermark advanced). Files: `src/seon/cluster/pick.clj`,
`src/seon/cluster/wake.clj`, `src/seon/cluster.clj` (graph), tests.

**W3 — messages on custody.** Delete the lookup in `send!`. Red proof
`seon.cluster.message-test/send-writes-only-the-senders-branch`: the sender's branch holds the message, main and the
recipient's branch do not until `pick-step` runs, `seon.db/branch-connection` is called zero times
(`seon.profile/called`). Files: `src/seon/cluster/message.clj`, `test/seon/cluster/message_test.clj`.

**W4 — ends on custody; effects follow on main.** `end-task!` writes on custody only; `db<->flow` gains actions for a
picked end (`:done` → request merge, `:given-back` → delete branch). Red proofs
`seon.turn-test/an-ended-attempt-writes-its-end-on-its-own-branch` (main unchanged before the pick; the assignment's
`:seon.task.attempt/commit` on main equals the source commit after it) and
`seon.flow-test/a-picked-done-end-plans-the-merge-request` (a row of `seon.flow/plan`'s table). Files:
`src/seon/turn.clj`, `src/seon/flow.clj`, tests. Opus lane (one path).

**W5 — tasks and context on custody.** Delete `seon.task/main`, `main-connection`; `written` and the context readers
use the world's connection. Red proof `seon.task-test/a-refinement-on-a-branch-reaches-main-only-by-pick` (refine on a
branch: main's intent unchanged; after the step equal; a concurrent root edit of the same attribute yields one
problem task). Files: `src/seon/task.clj`, `src/seon/context.clj`, `src/seon/turn.clj` (remaining reads), tests.

**W6 — root differs only by its world.** Red proof
`seon.cluster.pick-test/root-and-an-agent-differ-only-by-their-worlds-connection`: the same `my.task/refine!` form
through the one path in a world whose connection is a fixture main and in one whose connection is a fixture branch;
the first writes main directly; the second writes the branch, and after one step main's task equals the first's.
The fixture main is a disposable branch of the run (tests inject main as data). Files: tests only, plus whatever the
red names. Also deletes `seon.cluster.host/home-call`'s agent creation from `seon.mcp` once H7 lands.

**Order.** W0 → W1 → W2 → (W3, W4, W5 in parallel by file) → W6. W4 needs R1's `db<->flow`; until it lands, the
end's follow-ups run from `pick-step`'s action list as a one-line call, deleted when `plan` owns them.

## 6. Risks

1. **Main's writer load.** Every agent's picked burst is a main transaction. With many agents ending or messaging at
   once the writer serialises them (15–25 ms each); bursts coalesce per branch, so the bound is one pick per open
   branch per wake. Measure at 20 agents before W3.
2. **Latency of conversation.** Two picks per message (~40–60 ms). A proof that times a reply round trip may need its
   bound re-derived; measured, not assumed.
3. **A second writer on a recipient's branch.** Down-picks transact on the recipient's checked-out branch beside its
   agent; Datahike's writer serialises them, and with H2 a message write moves no program revision, so no SCI cache
   misses. A turn pinned to a database value sees the message on its next wake, as today.
4. **Watermark by `t`.** Valid only within one branch's lineage. Branch names are per attempt (`:attempt-<k>`, R1), so
   a re-created branch never inherits a stale watermark; a restart reopens the same lineage (`created? false`).
5. **Refs to entities without identity** (components) pick nested with their owner; a ref to a non-component,
   identity-less entity cannot move and is refused at publication (W0), never at pick time.

## 7. Where I am unsure

1. **The agent row's two halves.** Identity and attempt are main's; answered `t`, current namespace and disposition
   are the branch's own bookkeeping and never move. Does root need any of the latter on main (status, web), or does it
   read the branch on purpose when it wants them? I assume the latter.
2. **Refining a running child from its parent.** A parent's edit on main is not on the child's branch. Down-pick the
   held task (its listen edge is the assignment) or let the next attempt see it? I lean to down-pick, same rule.
3. **Fork point of a task with several needs.** The branch must hold its task, so it forks from the attempt's own main
   commit; the needs' programs reach it only after root merged them. If a need's unmerged program must be visible,
   the fork point becomes a merge of need branches: a separate question for R1's basis.
4. **`pick-step` inside `db<->flow` or beside it.** Beside it (one role: moving data, not starting Flows); `db<->flow`
   reads the picked facts. If root prefers one proc, `plan` gains a `:pick` action with the same pure function.
5. **Recorded errors.** "At the task home" becomes "picked onto main"; counting repeats by signature on main needs the
   pick to add to a count, not overwrite it (a `:db.fn/call` in the pick for that one kind, or counts derived from
   occurrences).
