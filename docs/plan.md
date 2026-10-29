# The plan

What Seon is for, where it stands, the one step being worked now, and the steps after it. [AGENTS.md](../AGENTS.md) is how a session works; the [map](README.md) is how the system works; [decisions](decisions.md) are the owner's rulings; git is the history.

## The goal

**A user asks for something, and a Seon agent does it, end to end, while the user watches.** In full:

1. **A request becomes a task**: its plan (what to change and why) and its proofs (the tests that say it's done).
2. **An agent works the task at a real REPL**, in its own forked process on its own branch, in few turns:
   - it sees its world as data (`@ctx`, pulled from the db), with the task, the code its plan cites and what it last did;
   - every eval answers small: its value or an error, plus one line of what changed;
   - main's difference from its branch is in that line every time, so nothing at the end is a surprise;
   - the tests its change reaches run by themselves, by digest; a failure comes back as data it can fix.
3. **Done means green and merged**: the done rule checks the proofs at the branch's commit; the branch merges into main; main runs the result next.
4. **The user watches it live**: the agent's page is a chat (the task, the agent's prose, each eval with a one-line outcome), and the agent sees what the user sees.

**Measured as** turns to green and tasks merged. **Held to:** nothing crashes or hangs (every failure is data to the agent); everything shown is derived from the db at t; one mechanism per job; every change earns its place; a v1 capability is present or dropped on purpose ([parity](parity.md)).

## Where it stands (2026-10-29)

The eval protocol is settled and merged: a call is prose and a form (several forms answer `:forms` and each one's value, DECISIONS 2026-10-29), its answer Clojure's value as data with timing and work, a top-level `do` unrolled as Clojure does, the whole conversation sent ([prompt](prompt.md), DECISIONS 2026-10-28). Since then the work has been the machine: what Seon costs on an ordinary MacBook, and what crashes or hangs.

**Memory, measured.** An idle root on a resumed store went 1,438 to 1,106 MB, and its fork server 438 to 381 MB (the fork server preloads main, root returns free pages past 10%, the node cache weighs what it retains); `e2e calc` 927 to ~836 MB. Ten agents working at once (`bin/check swarm`, scripted, a scratch store) peaked at 2.4 GB: root 845 MB, fork server 124 MB, each agent ~140 MB. On the standing store root is ~2.0 GB once it has embedded (the embedding model alone is 380-500 MB) and the fork server 670 MB, so ten agents are ~4.1 GB today. Of root's static 740 MB boot image, 213 MB is Chez's expanded source for every procedure (measured; dropping it keeps every frame's file:line) ([machine](machine.md#roots-heap)).

**Target:** an idle root at or under 650 MB, the fork server under 300 MB, ten working agents under 3 GB, so an 8 GB Mac runs Seon with room to spare.

**Stability, found this session:** a Datahike commit loop lost a report because Jolt's `reduce` read past `reduced` (fixed in Jolt e13d154f); the main carrier's loop held its mutex across a throw (fixed, 80579b46), yet its test still fails ~1 run in 20 (cause not found); root's file watcher died on one bad batch (a task with a nil ns) and stopped watching; any forked process that touches an Apple framework (Metal, Foundation) aborts, so the embedding model cannot run in a fork; Datahike's stress-test units are red; `bin/update` refused every pin bump (fixed).

In flight, in order:
- Jolt 80579b46 pinned (the reduce and carrier fixes), proving; then the full run on main.
- Six salvaged branches from old worktrees, as one batch: each eval tells an outside agent how main differs, done answers one line, a dead agent never wedges its task; the `:uncited-var` class; dead reach code deleted.
- Main's commit notice carries a commit, not a db (no pinned versions under churn): held, `e2e calc` root +30 MB unexplained.
- The image keeps each frame's file:line and drops the expanded source (task seon.main/the-image-keeps-no-inspector-information; owner chose this way): a Chez strip option and Jolt's build, in its gates.
- Root's watcher outlives a bad batch, and the nil-ns task load race; the main carrier's remaining race in Jolt; Datahike's stress-test reds.
- The agent page and context redesign: a separate session (`(seon/changes)` after each turn; a prose-only reply winds the agent down).

## Now: one step

**Step 1: one real task, end to end, through Seon.** Done when an agent attached to a real task file entry finishes it: green, merged by Seon into main, written back, and the owner can watch the whole run on its chat page. Nothing else is built until this works. The known problems in its way, in order (each is a task with its proof):

| # | Problem | Done when |
|---|---|---|
| 1 | ~~fixed (heap fix 4)~~ A test child inherits the agent's heap ceiling, so tests die out of memory in the judge's fork | `seon.reconcile-test` runs green in a test child forked from an agent |
| 2 | ~~fixed (a-frame-is-written-whole)~~ An agent's reply can be lost: a large frame fails on "try again", read as a dead peer, and the error is ignored | a frame larger than the socket buffer to a slow live peer arrives whole; a failed send is an error to the asker |
| 3 | ~~fixed (the orphan watch)~~ Orphans: a process outlives its parent for hours | killing a parent ends its children |
| 4 | Every eval answer doesn't say how main differs from the branch | each eval's line names main's merges since the base, the names both changed, and a would-be conflict |
| 5 | The done rule refuses "behind" and answers 74 KB; its reds carry no messages | done answers one line and a handle; a red carries its first failure; behind is told, not a surprise |
| 6 | A dead agent leaves its task running and attach returns the dead agent | a death is a fact; attach starts a fresh agent on the same branch |
| 7 | The run concept (approved schema design: the agent is the unit, no stored judgement, turns bounded per wake) and test results (open: per-test facts with running averages and no history, in place of slice 1's file cache) | an agent's record is its turns, evals and messages; a test's status, failure and averages are queryable facts |
| 8 | ~~The agent's page isn't a chat~~ merged (db03b7a6fb); live after `bin/update` | `/agent/<branch>` shows the conversation; the tiles move to `/debug` |
| 9 | Partly fixed (113 s to 57 s) A one-function prove takes 30 s | everything but the reached tests' own run takes milliseconds |
| 10 | ~~fixed (Jolt e13d154f, pinned 80579b46)~~ Jolt: the background-GC hang: `reduce` realized past `reduced`, and Datahike's commit loop lost a report | its cause found and fixed in Jolt, every gate green |
| 11 | Making a store allocated 1.05 GB: fixed in Datahike (linear schema transaction, ea53e8d580); tests branching the seeded store is on a branch | store creation linear; tests make no store of their own |
| 12 | Partly fixed (1,438 to 1,106 MB idle; preload, free pages, node cache) Root's heap: the 740 MB boot image (213 MB of it expanded source), the embedding model in root (380-500 MB once it has embedded), the fork server's 670 MB on the standing store | root idle at or under 650 MB, the fork server under 300 MB, ten working agents under 3 GB ([machine](machine.md#roots-heap)) |
| 13 | ~~test fixed; the Jolt loop open~~ The `line-parks` fork test grew to 35 GB: a thread started in a forked child loops in Jolt's collector | the test starts no thread in a child (on a branch); Jolt refuses or survives such a thread |
| 14 | Partly fixed (root keeps at most 10% free: Jolt eddb1882) Jolt: a full collection in a tight forked heap doesn't reclaim garbage; an agent's ~90 MB of start-up garbage; an agent may grow 1 GiB whatever the machine | each measured before and after; an agent's allowance sized from the machine |
| 15 | The infrastructure built for parallel sessions (merge batches, worktree roots, shared test roots, the test service, stall watcher) | one measured pass: each piece's cost (lines, run time, defects) against what it saves now; most of it deleted |
| 16 | Stability found 2026-10-29: root's watcher dies on one bad batch (a task with a nil ns); the main carrier's test fails ~1 run in 20; a forked process touching an Apple framework aborts; Datahike's stress-test units red | each a task with its proof; the watcher records the error and keeps watching; the embedding model runs in an exec'd helper |

**Beside step 1: the machine and its config** (owner, 2026-10-29: "I want this to run on an average user's MacBook"). In order, each a task with its measurement before and after:
1. The embedding model leaves root: a Flow proc whose work wakes it, running an exec'd helper that loads the model (~0.45 s) and exits when the queue empties; a fork cannot load it (task seon.vector/the-model-is-loaded-outside-root). Root -380 to -500 MB.
2. The image drops each procedure's expanded source and keeps every frame's file:line (task seon.main/the-image-keeps-no-inspector-information). About -210 MB per root, a 111 MB binary.
3. The fork server's 670 MB on the standing store: preload only what agents read.
4. The ~165 MB of load-time data in the image, by namespace; the node cache sized from the machine.
5. An agent's ~90 MB of start-up garbage, and its 1 GiB growth allowance sized from the machine's RAM.
6. Config at every level, one resolver, no env var switches: defaults < profile < --config < --set < main's root entity < model < provider < task < agent, each key's schema naming the levels that may set it; bin/ passes --set (owner, 2026-10-29). First task: delete the eight #env tags nothing sets.
7. `bin/check scale` charges ten working agents, some running tests, against an 8 GB-class `:seon.config.machine/memory-mb`, and fails past it.

**Not now** (no session builds these until step 1 is done): relevance and ranking work, new renders beyond the chat page, self-modifying environments, declared design, new config keys or namespaces that no problem above needs, Jolt pin bumps that fix no red on main.

## After that

Each step is done when its demo runs, measured.
2. **Ten real tasks in a row**, each through step 1's path, unattended: turns to green and tasks merged recorded per task; every failure a task.
3. **Context that earns its tokens**: the context selection recalibrated on real working turns (tests ranked too), measured against what agents read.
4. **All work inside Seon**: Claude sessions work as outside agents (`seon_attach`, `seon_eval`, `seon_done`); worktrees only for what the store doesn't hold yet (docs, bin, pins, native code), each a task to bring it inside.
5. **Work the system can check**: contracts with cost bounds on every function a change touches, the composed bound within the task's budget.
6. **Root is main, one system**, then a **self-modifying environment**.

Beside these, the missing and partial rows of [parity](parity.md) are the backlog.

## Waiting on the owner

Nothing.
