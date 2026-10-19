---
title: "Context: root db<->flow, agent start, branch retirement"
type: research
status: complete; research only, wrote no source, test or database state
basis: "read-only at HEAD fdc34a9ba (2026-10-13)"
---

# Context: root's db<->flow, agent start, branch retirement

Read-only research at HEAD fdc34a9ba (2026-10-13). Code is cited by fully qualified symbol. Items marked
**(hypothesis)** or **(inferred)** were not reproduced.

## (A) READ THIS, in order

1. `CLAUDE.md` "Agents transact facts; root reacts" and "Merge is a root task" (`/Users/sean/src/seon/CLAUDE.md`).
2. `seon.cluster.agent/db<->flow` (proc arities; the start wake and prime), then `seon.cluster.agent/run-pass!`.
3. `seon.cluster.agent/plan` and `seon.cluster.agent/plan-facts` (the open attempts, startable leaves, fork points).
4. `seon.cluster.agent/next-actions`, `seon.cluster.agent/assignment-actions`, `seon.cluster.agent/lifecycle-facts`,
   `seon.cluster.agent/taskless-actions`.
5. `seon.cluster.agent/apply-plan!` and `seon.cluster.agent/apply-lifecycle!` (the effect executor).
6. `seon.cluster.agent/write-attempt!`, `seon.cluster.agent/fork-attempt!`, `seon.cluster.agent/seed-attempt!`,
   `seon.cluster.agent/start-attempt!`, `seon.cluster.agent/start-branch-flow!`.
7. `seon.task/ready`, `seon.task/ready-rules`, `seon.task/execution`, `seon.task/end-kind`, `seon.task/attempt-tx`,
   `seon.task/relaunched`, `seon.task/main-head`, `seon.task/assign!`.
8. `seon.cluster.registry/fork!`, `seon.cluster.registry/retire-branch!`, `seon.cluster.registry/base-commit-id`,
   `seon.cluster.registry/branch-commit-id`, `seon.cluster.registry/branches`.
9. `seon.task/delete-branch!`, `seon.task/close!`, `my.agent/retire-branch!`, `my.agent/retire-own-branch!`.
10. `seon.cluster.agent/acquire-context!`, `seon.cluster.agent/release-context!`, `seon.cluster.agent/stop-flow!`.
11. `seon.cluster.wake/route!`, `seon.cluster.wake/unlisten!`, `seon.cluster.wake/starting-attributes`.
12. `seon.cluster.host` (`223` lines; `assemble!` per the runtime-follows-db design) and `seon.cluster.boot` (boot calls
    `seon.cluster.agent/db<->flow` synchronously; boot retires the cluster branch at `seon.cluster.boot` restart).
13. Test: `seon.cluster.db-flow-test/every-agent-start-has-a-task` (test/seon/cluster/db_flow_test.clj).
14. Docs: `docs/research/agent-platform/2026-10-11/runtime-follows-db.md` §1 "The one path", §5 "Problems are tasks",
    §6 "One scheduling system"; `docs/research/agent-platform/2026-10-12/hybrid-done-and-merge.md` §1 "The design in one
    page" and §6 phases; `docs/seon/schedule/cards/TASK-CHAIN.md` phase-status line naming 5b.
15. Commits (in this order): 241b28434 (R1 vocabulary: armer becomes db<->flow, start-flow!/stop-flow!), b7cc27520
    (attempts fork predecessor end commit; :stop finishes the pass), 00cc97460 (F33 problem task on merge refusal),
    3d6418255 (H5 relaunch from end commit), d7ccec1b6 (F22 satisfied prove closes without merge), 4cfa03eab (relaunch
    forks its predecessor's end commit from that commit's own branch), 6e8b157ed (F64/F65 relaunch on not-analyzable),
    595f8f7de (`seon.cluster.agent/assignment-actions` one end-to-action owner), 6548fa208 (satisfied deleted),
    be25d0dfb (F51 satisfied end deletes branch), 99aa4953f (M13 taskless end deletes branch), 969555d6d and bae97ee71
    (H4/M10: every assignment forks main; `fork-attempt!` loses predecessor clause), cde025f68 (held task opens no model
    turn), fa0b0b6cb (M8c gate).
16. Git: `git log --oneline -40 -- src/seon/cluster/agent.clj src/seon/cluster/registry.clj` (latest `595f8f7de`).

## (B) db<->flow anatomy

**Listens to.** `db<->flow` (`seon.cluster.agent/db<->flow`) has one in-port, `:seon.flow/plan`, fed by the cluster's
plan channel `:seon.cluster.wake/channel`. Its wake route is `seon.cluster.wake/route!` registered in the proc's
`[args]` arity on main's connection. The route offers wakes from `seon.cluster.wake/starting-attributes` (a committed
`:seon.agent/id` creation is a start wake) and from listened attributes. Each agent branch also gets its own route,
`seon.cluster.agent/branch-route-key` (`:seon.agent.route/...`), which `seon.cluster.agent/start-branch-flow!` registers
with `:seon.cluster.wake/channels` pointing at the cluster plan channel, so a branch's own writes wake root's pass.

**Woken by.** (1) a branch's listened datom (the route); (2) the prime `:seon.flow/plan-prime`, offered on
`::flow/resume` (so a missed wake never decides whether an agent has a graph); (3) boot calls the transform
synchronously; (4) a `:seon.agent/quiesce` message unroutes and acknowledges. Coalescing on a sliding-1 in-port.

**Each pass** (`seon.cluster.agent/run-pass!`):
1. `lifecycle-facts` then `next-actions` (ended assignments and taskless agents), applied with `apply-plan!`; re-derived
   after a `:stop` and after a `:merge` so the plan reads the post-effect state.
2. `plan-facts` (`seon.task/ready` split by `seon.cluster.agent/basis-valid?`, open attempts, attempts made, relaunch
   fork commits, creation order) and `registry-facts` (started and paused graphs), then `seon.cluster.agent/plan`,
   applied with `apply-plan!`. Plan ops: `:problem` (invalid leaves), `:pause`/`:resume`/`:stop`/`:cancel` (open
   attempts follow intent; `:held` and a paused cluster read `:paused`), `:attempt` and `:start-flow` (fresh leaves up to
   `:seon.config.agent/max-running`).
3. Start agents whose branch is this process's own branch (`agents-to-start`): unstarted agents with an unmerged
   `:seon.agent/branch` equal to the own branch or unset, that have work (`seon.turn/more-agent-work?`) or a schedule owner.

**Effect ops and their executors** (`seon.cluster.agent/apply-plan!`, `seon.cluster.agent/apply-lifecycle!`):

| Op | Executing function | What it does |
|---|---|---|
| `:attempt` | `seon.cluster.agent/write-attempt!` | writes the agent and the attempt row (`seon.task/attempt-tx`) on main in one transaction |
| `:start-flow` | `seon.cluster.agent/start-attempt!` | `fork-attempt!`, `write-merge-base!`, `acquire-context!`, `seed-attempt!`, `start-branch-flow!` |
| `:problem` | `seon.cluster.agent/write-problem!` | `seon.task/create!` as root (`seon.task/<class>-<key>`) |
| `:pause`, `:resume`, `:cancel` | `seon.cluster.agent/follow!` | `flow/pause`, `flow/resume`, or `seon.cluster.agent/stop-flow!` interrupting |
| `:stop` | `seon.cluster.agent/follow!` (open) or `apply-lifecycle!` (ended) | `stop-flow!` with `:seon.agent/finish?` true; ended path then `release-branch!` |
| `:merge-base` | `apply-lifecycle!` | writes `:seon.task/merge-base` from `seon.cluster.registry/base-commit-id` |
| `:merge` | `apply-lifecycle!` | `seon.merge/checkout`, then `seon.merge/merge!`; a returned refusal is recorded as `:seon.task/refusal` |
| `:publish` | `apply-lifecycle!` | `seon.merge/publish!`; a refusal throws so `failed-pass!` records the fault |
| `:close`, `:relaunch` | `seon.cluster.agent/close!` | one transaction writing the branch head's end (and relaunch) on main |
| `:delete-branch` | `apply-lifecycle!` calling `seon.task/delete-branch!` | retire the branch (see D) |

Problem ops are also written by `seon.cluster.agent/failed-pass!` (ends `:error` for `:attempt`, `:start-flow`,
`:merge-base`, `:merge`). Relaunch and merge decisions are in `seon.cluster.agent/assignment-actions`.

## (C) Agent start path

Assignment is a fact, start is derived from it:

1. **Ready.** A task is ready when `seon.task/ready` returns it: its `seon.task/execution` (own, or nearest ancestor's
   down `:seon.task/children`, rule `execution` in `seon.task/ready-rules`) is `:running`, it is a leaf with no
   `leaf-done`, no blocking end without a relaunch, and no `blocked` need. `seon.task/execution` reads the same rule.
2. **Plan.** `seon.cluster.agent/plan-facts` takes the ready leaves split by `basis-valid?` (obligations on main, via
   `seon.task/obligations`; a `:prove` step counts a successor needing it). `seon.cluster.agent/plan` picks each startable
   leaf with no open attempt, oldest first, while open attempts counted as running/paused/held are fewer than
   `:seon.config.agent/max-running`. The attempt number is `attempts-made + 1`, the handle key `seon.id/id [handle number]`,
   the agent id `"attempt/<key>"`, the branch `:attempt-<key>`, the fork point `:main` or the relaunched end commit.
3. **Attempt row.** `write-attempt!` writes creation (`seon.cluster.agent/creation-tx`) plus `seon.task/attempt-tx` on main
   in one transaction (facts: `:seon.task/assigned`, `:seon.task/assignment`, `:seon.task/branch`, `:seon.task/agent`,
   `:seon.task/merge-base` set later). Root's `seon.task/assign!` is the same write for a task root assigns by hand.
4. **Fork.** `fork-attempt!` calls `seon.cluster.registry/fork!` from main (or from the relaunched end commit's own branch
   via `seon.db/commit-database`), unless the store already holds the branch. It answers
   `seon.cluster.registry/base-commit-id`, which `write-merge-base!` records on main.
5. **Graph start.** `acquire-context!` (`{:seon.agent/branch branch}`) acquires the branch execution; `seed-attempt!`
   writes the agent and rows onto the branch when the fork point predates them; `start-branch-flow!` registers the
   branch's wake route then calls `start-flow!` with the intent (`:running`, `:paused` or `:held` from the plan).
6. **Flow graph.** `start-flow!` (through `seon.cluster.agent/start-claimed-flow!`) builds the graph from
   `seon.cluster.agent/graph-definition`; the turn proc reads its task and `seon.task/effective`.

**Starts without a task (root maintenance).** `seon.cluster.agent/db<->flow` starts any agent with
`:seon.schedule.task/owner` or work through `agents-to-start`. Root's maintenance schedules are seeded by
`seon.schedule/root-maintenance-seed-call`. The test `seon.cluster.db-flow-test/every-agent-start-has-a-task` asserts
the opposite, that a graph starts only from an assignment; it is red on HEAD (TASK-CHAIN phase 5b, status line
2026-10-13). Per the root ruling recorded in that test, a schedule owner with no assignment should not be started, so
`agents-to-start` is the place where the ruling conflicts with the code. Consequence on HEAD: root's schedule owner
starts with no assignment and chain-world teardown fails (per TASK-CHAIN phase-status line).

**Stop and relaunch.** A graph stops only through `:stop`, `:cancel` or a cancelled task (`close!` writes `:cancelled`).
Relaunch from the end commit is `assignment-actions` `:relaunch` (capped or stalled, or refused merge, with work and
allowance), which writes the end plus `:seon.task.attempt/relaunch` in `close!`; `seon.task/relaunched` then forks
the next attempt at that commit.

## (D) Branch retirement

Entry points that delete a branch:

- `seon.task/delete-branch!`: called from `seon.task/close!` (root decline) and from `apply-lifecycle!` `:delete-branch`
  (ended, merged, nothing unmerged). Submits `my.agent/retire-branch!` as io work to the environment's work launcher
  (`seon.flow/submit!`), inline when no launcher. Main is never deleted.
- `seon.cluster.registry/retire-branch!`: the primitive. Calls `d/delete-branch!`. Refuses `::cluster-connected`
  (active connection in this process) and `::cannot-retire-main`; treats `:branch-does-not-exist` as done. Retires the
  base ref too (`base-branch`).
- `my.agent/retire-branch!`: closes admissions (`seon.cluster.host/close-admissions!`), joins graphs
  (`seon.cluster.host/retire!`), releases connections, then `registry/retire-branch!`. `my.agent/retire-own-branch!` (own
  branch only) writes `:seon.agent/admission-closed-tx` on main's agent rows of the branch and answers
  `:my.agent/retiring?`; root's `db<->flow` then deletes it.
- `seon.cluster.agent/acquire-context!` also calls `registry/retire-branch!` on a failed create.
- Other callers: `seon.cluster.registry/retire-unclaimed!`, `seon.cluster.source` (404, 718), `seon.merge` (306),
  `seon.cluster.boot` (734, restart), `seon.cluster` (2726, ensure-cluster), `seon.test`, `seon.eval.drive`.

**Deletion gate.** `seon.cluster.agent/taskless-actions` deletes a taskless agent's branch only when the agent is ended
(`:seon.agent/admission-closed-tx` on main's row or on the branch head, `branch-admission-end`) and `source/unmerged-count`
is zero. `seon.cluster.agent/assignment-actions` deletes merged, not kept branches (`:delete-branch`, after a publish).
`:seon.config.agent/keep-ended-branches?` keeps them.

**O10 (retired probe branch still listed 12 min later). Hypotheses, none reproduced:**
- H1 (hypothesis, most likely): the end is written on the branch head only (`retire-own-branch!`), so root's db<->flow
  must be woken by the branch's listened route to see it. The route is unlistened on `:stop` and the branch's
  `:stop` only runs after its graph exits, so an idle or stopped probe whose write did not wake root is never
  re-derived until a prime. Check whether a root prime (resume, boot, or any main wake) happened in those 12 min.
- H2 (hypothesis): `seon.cluster.agent/taskless-actions` requires `(zero? unmerged)`. A probe that wrote declarations
  (`source/unmerged-count` > 0) is never deleted, and the taskless listing keeps it.
- H3 (hypothesis): `seon.task/delete-branch!` submits the deletion as detached io work. Its `:seon.flow/complete!` records
  a fault only on failure; `seon.cluster.agent/apply-lifecycle!` throws to `failed-pass!`, which records a fault but
  writes no problem task for `:delete-branch` (not one of its ops), so a refusal such as `::cluster-connected` retries
  silently each pass. Check the fault store for the probe branch name.
- H4 (inferred): `seon.cluster.registry/branches` lists the branch, but `taskless-agents` only derives it when the
  agent row exists on main; a probe whose agent row lives only on its own branch is never listed as taskless and
  never deleted.

## (E) Rule violations: non-root writes to main or another agent's branch

Rule (CLAUDE.md): an agent writes only its own branch. Violations found by rg (`branch-connection`, `home-connection`,
`send!`, `retire-branch!`); a grep for every literal `"main"` write was not done (limit).

1. `seon.fault/record!` (inner `commit-on`, src/seon/fault.clj around the `home-connection` binding): writes a copy of an
   agent's fault onto the cluster's home (main) via `db/branch-connection` when `copy?` holds. Runs in the faulting
   agent's process. Fix: the agent writes its fault to its own branch; root's db<->flow copies it.
2. `seon.fault/record!` also writes to the origin branch when it differs from the process's own (`origin` from
   `placed-on`), via `db/branch-connection` (src/seon/fault.clj around the `commit-on` target).
3. `seon.my.agent/retire-branch!` (src/my/agent.clj): a non-root agent deletes lane branches (`registry/retire-branch!`)
   and calls `seon.cluster.host/close-admissions!` on the context connection for a child lane. Violates "root executes
   stopping and deletion" (inferred: the connection is the agent's own, the child lane is another compartment).
4. `seon.message/send!` (src/seon/cluster/message.clj): delivery is an in-process `async/offer!` to the recipient's inbox
   port from the sender's process; the keep-copy transacts on the sender's `connection`. Message delivery should be a
   fact on the sender's branch that root's db<->flow executes (inferred: the recipient inbox is in-process so this is
   the in-process-delivery seam, not a main write).
5. `seon.task/assign!` (src/seon/task.clj) writes the attempt rows on the agent's branch via `db/branch-connection`. This
   is root's action (root assigns) and is allowed; listed because the agent branch is written from a root function.
6. `seon.cluster.agent/acquire-context!` (create path) writes and retires branches; root-only in practice (inferred).
7. Reads only (not violations): `seon.task/main-head`, `seon.task/assigned-obligations`, `seon.context` (203),
   `seon.sci.eval` (3682, 4087), `seon.cluster.agent` (1159 `active-branch-connection`).

`send!` grep: the only `defn send!` in src is `seon.cluster.message/send!` and its callers (`my.message/send`,
`seon.render.web` for the HTTP channel).

## (F) History

- `241b28434` R1: armer becomes db<->flow; functions stay in `seon.cluster.agent` (cycle avoidance).
- `b7cc27520`: attempts fork their predecessor's end commit; detached work never runs inline; `:stop` finishes the pass.
- `00cc97460` (F33): failed merge ends `:error` and writes root's problem task.
- `3d6418255` (H5): capped or stalled attempts with work relaunch by themselves.
- `d7ccec1b6` (F22): satisfied prove closes without merge.
- `4cfa03eab`: relaunched attempt forks its predecessor's end commit from its own branch (base ref continuity).
- `6e8b157ed` (F64/F65): not-analyzable merge refusal relaunches (fixable-refusals, later folded into assignment-actions).
- `595f8f7de` (A6): one end-to-action owner, `assignment-actions` over `seon.task/end-kind`.
- `be25d0dfb` (F51): satisfied end deletes its branch.
- `99aa4953f` (M13): taskless ended agent's branch deleted once nothing unmerged.
- `bae97ee71` (H4/M7/M10): Prove products merge; every assignment forks main; `fork-attempt!` loses predecessor clause.
- `969555d6d`: Prove step ends by itself via `base-commit-id`.
- `cde025f68` (M13 held clause): held tasks open no model turn.
- `fa0b0b6cb` (M8c): merge gate takes proofs from the task, not the agent's request.
- Designs: `docs/research/agent-platform/2026-10-11/runtime-follows-db.md` (one assembly path; "problems are tasks"; reconciler
  is the one proc that hosts the path). `docs/research/agent-platform/2026-10-12/hybrid-done-and-merge.md` (assignment is the
  merge request; root merges; `:done` never stored; derived actions in `seon.merge/next-actions`, the design name; code
  names are `seon.cluster.agent/next-actions` and `assignment-actions`). The design's `seon.merge/next-actions` and the code's
  `seon.cluster.agent/next-actions` diverge in name: the code is the owner.

Messages read: `241b28434`, `595f8f7de`, `6e8b157ed`, `4cfa03eab`, `bae97ee71` (H4/M10). Full messages are in `git log -1`.

## (G) Open questions

1. Which root prime or wake delivered the O10 probe's end to root's db<->flow? Check the wake log for that branch.
2. Should `agents-to-start` start a schedule owner with no assignment? The test says no; the schedule seed says yes.
   Root must rule before phase 5b can go green.
3. Is `:delete-branch` refusal (`::cluster-connected`) reported anywhere a human sees? (H3.)
4. Is `my.agent/retire-branch!` a non-root power that should be removed, leaving only `retire-own-branch!`?
5. Should `seon.fault` copy to main, or should root's pass copy agent branch faults? (E1, E2.)

## (H) Overengineered paths

Each item: symbol, what it does, the simpler form, rough lines removable. Inferred items say so.

1. `seon.cluster.agent/run-pass!`: derives lifecycle three times (before and after `:stop`, after `:merge`). The writes
   already wake the next pass (its own comment), so one derive plus one plan is the simpler form. ~12 lines. Inferred that
   the second derivation is needed for the same pass (the comment says it is); verify before cutting.
2. `seon.cluster.agent/write-attempt!` and `seon.cluster.agent/seed-attempt!`: two writes of the same creation and row, to
   two worlds. `seed-attempt!` exists because a fork could predate its row; after M10 every fork is from main after
   `write-attempt!`, except relaunch forks. Simpler: write the row before fork in all cases and delete `seed-attempt!`.
   ~20 lines. Inferred (relaunch fork commits may predate rows).
3. `seon.cluster.agent/assignment-actions` `copied?` clause: an end main holds with no merge base, "copied before merge bases
   existed". A legacy special case. Simpler: delete it once no such data exists in any store (check). ~6 lines. Inferred.
4. `seon.cluster.agent/apply-plan!` `:stop` dispatch: two paths to the same `stop-flow!` call (`follow!` `:stop` and
   `apply-lifecycle!` `:stop`, both `:seon.agent/finish? true`). Simpler: one stop executor. ~8 lines.
5. `seon.task/delete-branch!` plus `seon.cluster.agent/apply-lifecycle!` `:delete-branch`: async submit to the work launcher
   to avoid waiting on the store roster permit, then `my.agent/retire-branch!` through `requiring-resolve` delays
   (`seon.task/retire-branch!`, `seon.task/submit-work!`, `seon.task/record-fault!`, `seon.task/process-id`). A layer that
   only forwards to a `my.*` namespace. Simpler: `seon.cluster.registry/retire-branch!` called directly, with the
   admission and release steps in one function. ~35 lines. Inferred that the detached path is required for the permit.
6. `my.agent/retire-branch!` and `my.agent/retire-own-branch!`: two retirement paths plus a `:my.agent/retiring?` flag and a
   refusal path. Simpler: the agent writes its end (`:seon.agent/admission-closed-tx`) only; root deletes. ~40 lines.
7. `seon.cluster.registry/retire-branch!` recursion on `base-branch`, plus the `base-branch` ref: a second branch deleted
   by name pattern. Simpler: one call with the base ref deleted in the same call, no recursion. ~4 lines. Inferred.
8. `seon.cluster.agent/taskless-agents` and `seon.cluster.agent/taskless-actions` duplicate the assignment deletion rule
   (unmerged count, admission end). Simpler: one rule over all agents with an optional assignment. ~25 lines. Inferred.
9. `seon.cluster.agent/basis-valid?` uses `cache/lookup` with a `(constantly 1)` key scheme per plan pass, plus a successor
   query. The cache is a per-plan memo (a derived value cached by the commit id and symbol). Simpler: compute it inside
   `plan-facts` (called once per pass) and delete the cache key. ~8 lines. Inferred that `plan-facts` runs once per pass.
10. `seon.cluster.agent/failed-pass!` and `apply-plan!`: each action failure is wrapped in `ex-info` with the action in
    `ex-data`, then unwrapped in `failed-pass!`. Simpler: `failed-pass!` takes the action from a catch in `apply-plan!`
    directly and writes the end there. ~10 lines.
11. `seon.cluster.agent/write-problem!` and the problem text repeated in `plan` (`:invalid-task`), `unfinished-intent`, and
    `failed-pass!`: three problem-text builders. Simpler: one problem builder keyed by class. ~15 lines. Inferred.
12. `seon.cluster.agent/lifecycle-facts` reads every assignment and every taskless agent on each pass (whole-program work
    per wake). Measured cost not taken (inferred O(assignments) per wake). Simpler: restrict reads to assignments whose
    branch or end changed since the last pass (`d/listen` delta), per the cost rule. Not sized here.
13. `seon.cluster.agent/close!` reads the branch head's end and writes it through `db/with-branch`, separately from
    `branch-end`. Two reads of one end. Simpler: pass the end from `lifecycle-facts` (already read). ~6 lines. Inferred.

Rough removable total for H: about 170 lines across `seon.cluster.agent`, `seon.task`, `my.agent` and `seon.cluster.registry`.
All estimates are unmeasured; each needs its own proof (red test or measured cost) before deletion.
