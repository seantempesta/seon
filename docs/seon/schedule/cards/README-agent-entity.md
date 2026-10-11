---
title: "The agent entity (AE cards)"
type: reference
status: active
created: 2026-10-08
---

# The agent entity: one home for everything about an agent (AE-1 … AE-7)

Owner (2026-10-08 night), on where an agent's settings live: "an agent has their own entity and the task should be
ref'ed there. Everything about the agent should live there." This answers RESPEC R-9 and R-8 and amends RT-1, RT-3,
RT-6 and RT-13 in place. Authority: AGENTS.md "Model from the agent outward" (the agent's record is its one-hop
attachments); agent-loop.md §3.11 states the target this index schedules.

## What exists (default, 2026-10-08 night, one JVM read, 8 ms)

The agent row `[:seon.agent/id …]` is written on main before the agent's branch forks, so the same entity (same eid)
exists on both. Writers then pick a side by whatever connection they hold, and readers pick a side by hand:

| Fact about the agent | Written today | Read today |
|---|---|---|
| assignment (`:seon.task/agent`) | the task home (`seon.task/written` → `home-connection`) | at the home, by seven hand-written `(or (some-> (task/home db) db/db) db)` lookups: `seon.context/current-state`, `seon.render.ns/held-task`, `seon.turn/next-agent-work`, `seon.turn/task-proofs`, `my.task` (three), `my.program/request-merge!`, `seon.cluster.status` |
| settings (`:seon.agent/settings` component) | whatever connection the caller holds (`seon.agent/settings!`) | the agent's own branch (`seon.ai/agent-overlay` in `seon.turn`, `seon.turn/max-episode-runs`, `seon.bootstrap`) |
| messages to the agent | the recipient's `:seon.agent/branch` (`seon.cluster.message/send!` routes by it) | its branch |
| call cap anchor | none: derived as the latest outside wake's `t` on the branch (`seon.turn/outside-wake-t`) | its branch |
| answered basis | none: derived from answering turns on the branch | its branch |

Live: `live-flash-4` (branch `:agent-13d811644932`) holds its assignment `c7bd8e7419fa` only at the home and its
settings `{max-episode-runs 5, no-provider false}` only on its branch; root's settings at the home would never reach
it (task `my.agent/settings-root-writes-on-main-never-reach-an-agent-on-its-branch`), and root has no route to the
branch (run 3: the writer refuses a branch write from root's cluster connection).

## The design in five lines

1. **One rule for where a fact about an agent is written: addressed to the agent → the task home; done by the agent
   → its branch.** Its record (identity, branch, namespace, settings, held assignments with their grants, archival)
   and everything sent to it (assignments, messages) are written at the home, the one place root writes, so root
   changes an agent without touching its branch. What the agent does (evaluations, call rows, its answered basis,
   its program) is written on its branch inside its eval request's one transaction.
2. **The writer refuses the wrong side.** Each `:seon.agent/*` attribute and each attachment attribute declares
   `:seon.agent/written-at` `:home` or `:branch` in its schema row; the checked writer refuses a home attribute on a
   branch that is not the home and the reverse, by name. This refuses the R-9 bug class at its producer.
3. **One read: `seon.agent/record [database agent-id]`.** It pulls the record at the home seen from `database`
   (`seon.task/home`, moved here as `seon.agent/home`) with one selector derived from the agent entity schema
   (settings component, held assignments and their tasks), and reads the branch-side facts (answered basis, calls
   spent on the current grant) from `database`. Every reader above calls it; an eval request reads it once, when its
   world is built, and passes the value down (block, proofs, cap, provider settings). No cache: the read is one pull
   (O(attributes + held)), about 1 ms; a `seon.cache` kind keyed by the home's commit id would be invalidated by every
   unrelated home write and saves nothing measurable. Add one only if AE-3's measure says otherwise.
4. **The agent refs its task through its assignments, components of the agent.** `:seon.agent/assignments`
   (cardinality many, component) holds each assignment: `:seon.task/assignment` (identity, unchanged),
   `:seon.task/task` (a ref to the task, replacing the `:seon.task/assigned` handle value), `:seon.task/revision`,
   `:seon.task/branch`, `:seon.task/grant` and, per RT-6, `:seon.task/answer`. Holding is the component's presence
   (RT-3); completion or give-back is `[:db/retractEntity assignment]`, which also drops the agent's edge. The agent's
   one-hop pull shows every held task. A direct `agent → task` ref cannot carry the revision, grant and answer
   (Datahike has no edge attributes), and root holds several, so the assignment stays an entity, owned by the agent.
5. **The call cap is anchored on the assignment's grant.** `assign-tx` mints `:seon.task/grant` (a UUID) on each
   assignment it writes; every model call row records the grant it spent against, as a value (CX-13 already records
   the assignment key there). Calls left = the cap (the record's settings, else the cluster dial) − the count of call
   rows carrying the grant (one `:avet` seek). Re-assigning after `:capped` (RT-13) mints a new grant: the agent and
   branch resume with a fresh budget. A message, a task revision or a schedule firing never refills it. The outside-
   wake anchor (`outside-wake-t`, `spent-runs`) is deleted.

The answered basis `:seon.agent/answered-t` (agent-loop.md §3.5 target) is a home `t`, since every wake (assignment,
message) is written at the home; it is a branch-side attribute of the agent, written by the model eval request's one
recording transaction, so recording stays one transaction on the branch.

## What is deleted

- The per-branch settings route: `seon.agent/settings!` writing through the caller's connection; the branch copy of
  `:seon.agent/settings`; `seon.turn/max-episode-runs`'s private settings query; `seon.ai/agent-overlay` reads on a
  branch value (it becomes a projection of the record's settings).
- Seven hand-written home lookups and `seon.task/agent-task` (latest assignment by transaction): one `seon.agent/record`.
- `seon.cluster.message/send!`'s recipient-branch routing (messages go to the home, AE-6).
- `seon.turn/outside-wake-t`, `spent-runs` and the outside-wake anchor in `episode-runs`/`turns-left` (AE-5), and with
  them the `:seon.wake/inside` declarations' role in the bound.
- `:seon.task/agent` and `:seon.task/assigned` (replaced by the component edge and the task ref; RT-1's listened
  attribute becomes the component edge, AE-4).

Net: the record function and the side declaration are added (about 40 lines); the lookups, the anchor derivation and
the routing (about 150 lines) go.

## Schema changes (reset, no migration)

The store is disposable: AE-4 lands at a reset (§5 manifest of agent-loop.md: dump assignments and agent settings,
re-apply by documented forms). `seon.agent.edn`: `:seon.agent/assignments` (component, many), `:seon.agent/answered-t`
(`:branch`), `:seon.agent/written-at` declared on every agent attribute. `seon.task.edn`: `:seon.task/task` (ref),
`:seon.task/grant` (uuid), the assignment row without `:seon.task/agent` and `:seon.task/assigned`. The call row:
`grant` (value, indexed). `seon.wake.edn`: a listened attribute may wake its entity (`:seon.wake/listen :entity`) as
well as its value, so the component edge `[agent :seon.agent/assignments a]` wakes the agent. Each schema change is
published and adopted on a branch of the live store before its card commits.

## Cost

| Operation | Work | Proportional to |
|---|---|---|
| `seon.agent/record`, once per eval request | one pull at the home, one grant count on the branch | attributes + held assignments; calls on the grant (index count) |
| root's settings or assignment write | one home transaction | the written datoms |
| writer's side check | one schema-property lookup per agent attribute in the transaction | the transaction's agent datoms |
| cap check | one `:avet` count | calls on the grant (O(log n) with DH-1's subtree counts) |

Today's `outside-wake-t` walk is O(the agent's wakes) per pass; this removes it.

## Branch, then merge back for historical reference

The owner asked for the design and its implementation to be done on a branch and merged back so the history keeps how
it was built. With today's tooling:

- **The implementation is Seon-branch work, as every lane's should be.** Each AE card runs as a Seon agent `ae` on its
  own Datahike branch: `defn` on the branch, the reaching tests and the card's proof run in the eval request,
  `my.program/write-branch!` writes the files back, `bin/lane commit agent-entity --branch-evidence ae …` commits, and
  root's merge request closes the branch. Datahike history keeps every evaluation and test result on that branch until
  retention collects it; git keeps the commits. Host-bound cards (schema, writer, wake) commit through
  `bin/lane commit … --test …`, which publishes and adopts on disposable branches.
- **The git side.** The shared working tree has one checkout and lanes may not switch it or open worktrees, so this
  design commits on `refactor/agent-platform` through `bin/lane commit`, and a ref `design/agent-entity` names that
  commit (`git branch design/agent-entity <commit>`, no checkout). A true side branch with a `--no-ff` merge needs a
  checkout or a worktree: Q-AE3.

## Order (each card about 15 minutes)

1. **AE-1** `seon.agent/record`: the one read (addition only).
2. **AE-2** settings are written and read at the home; the writer refuses agent attributes on the wrong side (fixes
   R-9).
3. **AE-3** every other reader of agent facts calls the record once per eval request; the seven lookups are deleted.
4. **AE-4** assignments are components of the agent and ref their task (reset; amends RT-1, RT-3, RT-6).
5. **AE-5** the call cap is anchored on the assignment's grant (answers R-8; amends RT-13).
6. **AE-6** messages to an agent are written at the home.
7. **AE-7** the answered basis is a stored home `t` on the agent.

AE-1 … AE-3 need nothing unlanded and fix the live R-9 defect. AE-4 is the reset point and replaces RT-1's schema
change (launch RT-2 after AE-4, or RT-2 first and AE-4 converts it). AE-5 follows AE-4 and CX-13. AE-6 and AE-7 follow
AE-4 and change the wake path (Opus).

## Owner questions

**Q-AE1. The assignment: a component of the agent, or its own entity pointing at the agent?**
1. **(recommended)** A component (`:seon.agent/assignments`): the agent's one-hop record holds its tasks, as asked;
   holding is the component's presence; needs the entity-side wake (AE-4, a small `seon.cluster.wake` change).
2. Keep RT-1's shape: the assignment points at the agent (`:seon.task/agent`, a reverse attachment like
   `:seon.message/_to`); no wake change, but the task is not on the agent, only reachable from it.
3. A direct `:seon.agent/task` ref with the revision, grant and answer elsewhere: one task per agent, root needs
   several agents; rejected.

**Q-AE2. The cap anchor.**
1. **(recommended)** A grant UUID minted per assignment, recorded on each call row: re-assignment refills, nothing
   else does.
2. Count call rows carrying the assignment key since the assignment's home `t`: no new attribute, but compares a home
   `t` with branch rows (the fragility Q1 of README-context rejected).
3. A cumulative `:seon.task/calls-granted` on the assignment: root raises it to grant more; needs the spent count at
   the home writer, which reads the branch.

**Q-AE3. "Branch, then merge back" in git.** 1. **(recommended)** Commit on `refactor/agent-platform` and name the
commit with a ref (no checkout; the Seon branches carry the build history). 2. Build a side branch with
`git commit-tree` and record a `--no-ff` merge with plumbing, never touching the shared tree. 3. A worktree for this
design (needs your personal authorization for that use).

**Q-AE4. Messages at the home (AE-6).** Recommended yes (one rule; wakes all at the home, the answered basis is one
home `t`). The alternative keeps them on the recipient's branch and makes AE-7's answered basis a pair of `t`s.
