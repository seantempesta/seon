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

## Where it stands (2026-10-29, evening)

The eval protocol is settled and merged (a call is prose and a form; several forms answer `:forms` and each one's value; [prompt](prompt.md), DECISIONS 2026-10-28/29). The other session owns context and the agent page (R1 merged: the model reads the agent's live walk; R2 next: root's after-turn work as facts, test results as facts). This session owns the machine: memory, stability, the test tooling.

**Context and the agent page** (task seon.context/the-context-is-the-agents-evals-and-seons; DECISIONS 2026-10-29: the model's context is the render of @ctx by the one walk, the model and the person read one layout, nothing derived held). Merged: several forms answer each one's value; the agent is the unit of its record (seon.agent/the-agent-is-the-unit-not-the-run cut 1); the fold and :same-as deleted; the model call, the process and the embedding are their own entities; R1 (a turn's request 5.5M ticks / 13 MB to 2.1M / 6 MB). On branch jolt/r2-one-layout-and-facts: one layout (render-ctx; chat-ctx deleted; seon.render-test/the-model-and-the-page-read-the-same-parts) and R2a (an agent's test results as facts on its branch; the shared cache kept). Next: R2b (root's after-turn merge of main, proofs and merge check as facts; parts keyed by the data they show, so a move of main redraws only what reads main), R3 (the request from its basis), cut 2 (the reconciler schedules agents), cut 3 (the run schema deleted, the store erased on the owner's go), and seon.proofs/the-verdict-cache-empties-itself.

**Memory, measured.** `e2e calc` root 927 -> 609 MB today (fork server preloads main, root keeps at most 10% free (Jolt eddb1882, `:seon.config.root/max-heap-free-ratio`), node cache weighs what it retains, the image keeps file:line but drops each procedure's expanded source (Chez 3359d674, Jolt 58af2fd0): `target/seon` 250 -> 111 MB). The standing root is ~1,090 MB idle until it embeds; the embedding model then adds 380-500 MB. Ten scripted agents peaked at 2.4 GB on a scratch store (~140 MB an agent); an agent's start-up footprint (~61 MB idle, ~80 MB after one eval) is first-use writes into inherited pages, not allocation (fork to ready allocates ~2 MB; the schema hash, 31.5 MB per agent, is gone: Datahike 62030f8d). Target: root idle <= 650 MB, fork server < 300 MB, ten working agents < 3 GB ([machine](machine.md#roots-heap)).

**Stability.** Fixed today: Jolt's `reduce` past `reduced` (Datahike's lost commit report); the main carrier's mutex on a throw (its test 150/150 on our patched Chez; Jolt's gates now run on that Chez: branch jolt/the-gates-run-on-the-patched-chez, not pinned); root's watcher survives a bad batch, and task loads share one lock; two proves on a new tree share its branch; a pin bump runs the forks; the Datahike stress test's own store path; `bin/update` on a pin bump; the full run's reds (backpressure, instrument, test-inputs: several-form evals now answer a vector; branch jolt/check-reds, green, not merged; lifecycle: the other session's branch jolt/lifecycle-one-form).

**Open, most serious first:**
- **A forked agent running a shell child of more than ~2 s spins**: ~1.6 M non-waiting waits in Jolt's `monitor-enter-slow!` on io_poller's `pm` lock after the child exits, ~1 GB of garbage, sometimes an OOM at the agent's ceiling or a hang. Likely cause (from the source, being confirmed): an object monitor's OS mutex/condition/owner are inherited across fork unreset (docs/forks.md: "a lock inside an object is not covered"). Agent working in worktree monitor-fork (Jolt branch jolt/a-forked-child-resets-the-runtimes-monitors).
- **`bin/update` can merge unreviewed work into main**: it merges `jolt/merges`, which the standing root advanced to the main tree's CHECKED-OUT branch; the held commit-notice branch (c0a9fdde89, 46c1d2929f, 10c98b1e7d) reached main that way at 7dfd1f7b75 with its +30 MB in `e2e calc` unexplained and without its review fixes. Rule until fixed: never run `bin/update` while the main tree is on a topic branch; fix: the root's source follows main, not the checkout's HEAD (task owed).
- `seon.server-test/a-later-read-draws-no-page-cold-and-costs-what-the-one-before-did` goes red in long shards (later reads +5%): something grows with what ran before; agent working (worktree read-cost).
- Jolt gate flakes on our Chez: gcpolicy's output interleaves with a GC log line (every run); fibers' async-callback-test sees an extra thread ~1 in 20; agent working (worktree gate-flakes).
- The heap histogram on an OOM (Jolt c6d8d43e, Seon jolt/an-oom-carries-its-heap): built, gates green, unmerged: it once hung a forked agent (likely the same fork-monitor defect).

**Waiting on the owner:** the embedding helper (branch jolt/the-model-runs-in-its-own-process c2f43ab361, worktree embed-helper): root 1,675 -> ~1,140 MB after a backlog, the model gone when idle; reviewed, fixes in; two calls: keep the `:more` flag (backlog 210 -> 70 s; now released by an empty request) and the helper's start/exit kept in the worker's ping (deaths reach the error facts).

## Now: one step

**Step 1: one real task, end to end, through Seon.** Done when an agent attached to a real task file entry finishes it: green, merged by Seon into main, written back, and the owner can watch the whole run on its chat page. Nothing else is built until this works. The known problems in its way, in order (each is a task with its proof):

| # | Problem | Done when |
|---|---|---|
| 1 | ~~fixed (heap fix 4)~~ A test child inherits the agent's heap ceiling, so tests die out of memory in the judge's fork | `seon.reconcile-test` runs green in a test child forked from an agent |
| 2 | ~~fixed (a-frame-is-written-whole)~~ An agent's reply can be lost: a large frame fails on "try again", read as a dead peer, and the error is ignored | a frame larger than the socket buffer to a slow live peer arrives whole; a failed send is an error to the asker |
| 3 | ~~fixed (the orphan watch)~~ Orphans: a process outlives its parent for hours | killing a parent ends its children |
| 4 | Partly: an outside agent's eval carries main as last heard (272a43fefa). Every turn should say how main differs from the branch | R2b: root merges main into the branch each turn main moved, and the main part shows what came in, the names both changed and a conflict |
| 5 | The done rule refuses "behind" and answers 74 KB; its reds carry no messages | done answers one line and a handle; a red carries its first failure; behind is told, not a surprise |
| 6 | A dead agent leaves its task running and attach returns the dead agent | a death is a fact; attach starts a fresh agent on the same branch |
| 7 | Partly: the agent is the unit of its record (cut 1 merged); an agent's test results as facts (R2a, on its branch); the reconciler still schedules runs (cut 2) | an agent's record is its turns, evals and messages; a test's status, failure and averages are queryable facts |
| 8 | ~~The agent's page isn't a chat~~ merged (db03b7a6fb); one layout for the model and the person on jolt/r2-one-layout-and-facts | `/agent/<branch>` is the chat beside the parts the model reads; `/agent/<branch>/debug` is @ctx printed |
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
2. ~~Done (7577ce2536)~~ The image drops each procedure's expanded source and keeps every frame's file:line: `e2e calc` root 833 -> 609 MB, a 111 MB binary.
3. The fork server's 670 MB on the standing store: preload only what agents read.
4. The ~165 MB of load-time data in the image, by namespace; the node cache sized from the machine.
5. An agent's start-up footprint: not garbage (fork to ready allocates ~2 MB; the 31.5 MB schema hash is gone, 07c4d1a6ea) but first-use writes into inherited pages: find the objects written and warm them in the fork server (an agent is mapping them: worktree dirty-pages); and its 1 GiB growth allowance sized from the machine's RAM.
6. Config at every level (first two tasks merged 4a34eb9227/0781a5b29e: the dead #env tags deleted; the profile is `--profile NAME`, so root restarts with `nice -n 15 bin/update --profile deepseek`; the other five filed in resources/seon/tasks/seon.config.edn; owner, 2026-10-29: env vars are fine when aero captures them into config entries, never a switch read elsewhere), one resolver: defaults < profile < --config < --set today, each key's schema naming the levels that may set it; main's root entity, model, provider, task and agent levels join the same resolver the day a key needs them (owner, 2026-10-29: only levels in use); bin/ passes --set. First task: delete the eight #env tags nothing sets.
7. `bin/check scale` charges ten working agents, some running tests, against `:seon.config.machine/memory-mb` sized so root and ten working agents stay under ~4 GB (owner, 2026-10-29: fits 8 GB, runs beside other apps on 16 GB), and fails past it.

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
