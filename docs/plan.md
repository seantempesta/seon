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

## Where it stands (2026-10-28, midday)

A real agent, working through the server on a fresh store, fixed a printer bug in 4 evals: it reproduced it, wrote a test that went red, fixed it, ran the namespace green. It could not finish. The loop breaks after the work:
- the done rule refused it as "behind" main, which had moved during the run;
- the done rule's test run went red on ~140 unrelated tests, likely because a test child inherits the agent's absolute heap ceiling;
- `seon_done` answered 73,856 characters;
- the dead agent then wedged its task, so it could not be re-attached.

So the work itself is within reach; the harness around it is what fails. Since then: the standing store was erased (fresh, 40 MB); store creation went from 1.05 GB to 366 MB (Datahike's schema transaction was quadratic); test verdicts left Datahike (slice 1, to be revisited below); the chat page merged. The way of working changed too: the owner and one Claude session, with one-use helpers ([AGENTS](../AGENTS.md) §2), after a night of ten parallel sessions built infrastructure instead of the product.

## Now: one step

**Step 1: one real task, end to end, through Seon.** Done when an agent attached to a real task file entry finishes it: green, merged by Seon into main, written back, and the owner can watch the whole run on its chat page. Nothing else is built until this works. The known problems in its way, in order (each is a task with its proof):

| # | Problem | Done when |
|---|---|---|
| 1 | A test child inherits the agent's heap ceiling, so tests die out of memory in the judge's fork | `seon.reconcile-test` runs green in a test child forked from an agent |
| 2 | An agent's reply can be lost: a large frame fails on "try again", read as a dead peer, and the error is ignored | a frame larger than the socket buffer to a slow live peer arrives whole; a failed send is an error to the asker |
| 3 | Orphans: a process outlives its parent for hours | killing a parent ends its children |
| 4 | Every eval answer doesn't say how main differs from the branch | each eval's line names main's merges since the base, the names both changed, and a would-be conflict |
| 5 | The done rule refuses "behind" and answers 74 KB; its reds carry no messages | done answers one line and a handle; a red carries its first failure; behind is told, not a surprise |
| 6 | A dead agent leaves its task running and attach returns the dead agent | a death is a fact; attach starts a fresh agent on the same branch |
| 7 | The run concept (approved schema design: the agent is the unit, no stored judgement, turns bounded per wake) and test results (open: per-test facts with running averages and no history, in place of slice 1's file cache) | an agent's record is its turns, evals and messages; a test's status, failure and averages are queryable facts |
| 8 | ~~The agent's page isn't a chat~~ merged (db03b7a6fb); live after `bin/update` | `/agent/<branch>` shows the conversation; the tiles move to `/debug` |
| 9 | A one-function prove takes 30 s | everything but the reached tests' own run takes milliseconds |
| 10 | Jolt: the background-GC hang (1 in 5) | its cause found and fixed in Jolt, every gate green |
| 11 | Making a store allocated 1.05 GB: fixed in Datahike (linear schema transaction, ea53e8d580); tests branching the seeded store is on a branch | store creation linear; tests make no store of their own |
| 12 | Root's heap is 1.22 GB on a fresh store (expected ~600 MB): a 740 MB boot image, a node cache weighed at 4 KB per 12 KB node, strings copied per index, a 124 MB `*e` | each part measured before and after; root under ~600 MB; an agent's cap bounds only what it allocates itself |
| 13 | The `line-parks` fork test grew to 35 GB: a thread started in a forked child loops in Jolt's collector | the test starts no thread in a child (on a branch); Jolt refuses or survives such a thread |
| 14 | Jolt: a full collection in a tight forked heap doesn't reclaim garbage; Chez keeps up to ~530 MB of free pages per agent; the fork server freezes replaced data into static | each measured before and after |
| 15 | The infrastructure built for parallel sessions (merge batches, worktree roots, shared test roots, the test service, stall watcher) | one measured pass: each piece's cost (lines, run time, defects) against what it saves now; most of it deleted |

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
