# AGENTS.md: how we build Seon

Seon is where agents do real work for their user, each in its own process on its own branch. The good changes merge into one program that gets smaller, faster and more certain as it grows. This file is for every Claude session and every Seon agent alike. It holds the rules; the wiki (`docs/`) holds how the system works. When a rule changes, this file is edited in place: a new rule replaces an old one, and the file does not grow. The owner's rulings, with his words, are in [decisions](docs/decisions.md).

## 0. Start here

You are in `next/`: **Seon on Jolt, built to replace Seon** (`~/src/seon` is the JVM version, read-only). A v1 capability left out has its reason in `BAGGAGE.md`.

**Read before acting:** this file, `docs/plan.md` (what is in flight), `docs/README.md` (the wiki's map), then your task (`resources/seon/tasks/<ns>.edn`) and what its plan cites.

**Find a detail** in `docs/README.md`, then through `seon_context` (§6), then with ``git grep -n 'term' -- docs resources/seon/tasks src``. A `bin/` script's header comment is its manual.

**The running system:** `bin/system status` (server port 7888, dashboard http://127.0.0.1:7777/). The standing system and its store (`runs/store`) are for use: agents work and merge there, and sessions reach it through the MCP tools (§6). Erasing the store is the owner's act. Once a system is up, its store is the source of truth ([files and the db](docs/source.md#files-and-the-db-two-directions)).

## 1. What Seon is for, and what moving forward means

**Seon does any work its user wants,** improving its own code included. It grows by accretion while resisting growth: agents reuse and improve the core instead of writing one-offs.

**Moving forward means the loop works for real tasks, end to end:**
1. a user's request becomes tasks;
2. an agent works each task from context good enough to finish it in a few turns;
3. its proofs go green, its branch merges to main, and main runs the result.

Progress is **turns to green** and **tasks merged**, with every v1 capability present ([parity](docs/parity.md)). Work counts only when it moves one of those numbers or unblocks a step of THE PLAN (`docs/plan.md`). Before building, look at what is built.

Run, then right, then fast. If you can't show it running, it isn't done.

## 2. Who does what

- **The owner (Sean)** sets direction and makes the rulings. He talks to the managing session (Point), which manages the others.
- **Area sessions** each own an area and know all of it: Context, Web, Optimization, Tests, Deep fixes and Data model ([sessions](docs/sessions.md#sessions-own-their-areas)). A session never answers for code it has not read.
- **Point** keeps the schedule, lands each proved tip and brings the owner his decisions ([the managing session](docs/sessions.md#the-managing-session)). A session asks Point before it asks the owner.
- **No fan-out.** A session starts no other session unless the owner asks. It may use helpers: each gets a well-specified piece in a worktree of its own, commits there and stops. The session reviews every line a helper writes. Since 2026-11-12 every session and helper runs on Opus or Sonnet, never Fable without the owner's yes.
- **Sessions talk through the mailbox** ([sessions](docs/sessions.md#the-mailbox)). **A mail is 10 lines or fewer:** the number, the sha, the one decision needed.
- **Write in Clojure's, its libraries', Jolt's and git's words** (§7a), to the owner above all. A name we made up is explained or replaced before it is sent.
- **Seon agents** work tasks through the server (§6). What a session finds missing from their context is a task for Seon.

## 3. How work moves

**Work is tasks** ([tasks](docs/tasks.md)): an entry in `resources/seon/tasks/<owning-ns>.edn` with `:seon.task/plan` (what exists, the cause, what it replaces and deletes), `:seon.task/proofs` (the tests that say it is done) and `:seon.task/needs`. The plan is written before anything is built. Order: delete first, then foundations, then fixes. A problem outside your task is filed as a task, never fixed in passing.

**The method:**
1. **Learn.** List what exists before designing. Ask what the ordinary Clojure (or library) way is, and why ours is it or is not.
2. **Estimate before you build** (§3a). Work out from what has to be done what the work should cost: n, its big-O, the ticks, bytes and seconds expected. Write the estimate into the plan, and into every new test as its budget. Never take the estimate from the last run. **Measured over 10 times the estimate stops the work:** the gap is the finding, and its cause is found before anything else. Raising an estimate to fit what was measured is refused. A slow test is a bug.
3. **Prove first,** at the REPL as an agent would, on real data and at n ≤ 100. A data shape others will read is tried at a REPL with the forms its readers will write, and its name is settled once before any code. A proof is seen red first.
4. **Act:** the simplest code that passes, at the cause, in the owner of the bad value. **A bug is a class:** find what allowed it, change that so the class cannot recur, and sweep for its siblings. No special case, retry, guard or hand-kept list. **Fences, guards, locks, waits and timers are likely bugs:** a stateful process is a Flow proc, coordination is channels, shared state is one atom, and everything else is an immutable value ([flow](docs/flow.md#the-step-fn-contract-every-proc-follows)).
5. **Work it as a Seon agent would,** through the server (§6). A change to what the owner sees is checked in a browser.

**One branch per session, landed often** ([merging](docs/merging.md#a-branch-lands-with-binland)):
1. **Each session has one worktree and one branch,** `jolt/<session>` (`bin/worktree NAME`), and works all its items there together: build, iterate and test them as one, and merge main in often so nothing waits (owner, 2026-11-12: "they work on multiple things at once ... and it gets merged with main regularly so it isn't a bottleneck"). No branch per small change. The main tree stays on main and clean.
2. When the branch holds something worth landing, merge main into it and run `nice -n 15 bin/prove`. It runs everything the change reaches and reuses every green stored at its digest; it is never a hand-picked set of namespaces. There is no cap on proves at once: the test system sets its workers from the machine's cores.
3. Green: mail Point `LAND <branch> <sha>` with a landing message of 10 lines or fewer. It gives the step of THE PLAN, what the change deletes, the `src` delta, the estimate beside the measured number, and what allowed the bug. A docs-and-tasks change is never proved: commit it, mail Point, and Point lands the pending docs-and-tasks changes together after one `bin/check health` and `seon.doc-test`. A change to docs must rerun no test that does not read the docs ([what a test takes](docs/source.md#tests-run-by-reach)).
4. Point reads the diff and lands each session's branch alone with `bin/land -F MSGFILE`, which brings the standing system up to date through `bin/update`. Branches of different sessions are never batched into one landing.
5. Never wait on a landing: start the next change from main or from your own tip.

- **A change lands with what uses it.** A capability comes in the tip of its first caller, or with the deletion it makes possible. A docs change and a pure deletion land alone.
- **A merge that completes a task** removes the task's entry and says `Completes: ns/name`.
- **No regressions:** a number that got worse is refused unless the message names its reason and who follows it up.
- **Never delete or move a red verdict:** the fix is a new digest ([merging](docs/merging.md#verdicts-are-a-cache)). A flaky result is a defect to fix at its cause.
- **What a new design replaces goes with it:** code, tasks and wiki text.
- **A dependency pin** is proved whole on its own branch before it is handed over ([merging](docs/merging.md#conflicts-and-pins)).
- **Before a compaction,** a session sends its parent a few lines: what it finished, what cost it time, and what change to a script, check or this file would have saved that time. It then makes each fact it learned true where that fact lives (the wiki section, the task, the docstring), and removes the entries it completed.

**The gate, before any new format, key, layer, namespace, proc or rule:** (1) which existing mechanism does this, whether ours, v1's or a dependency's? (2) what did v1 do? (3) what is the field's best known way? (4) why can't the existing one do it? A new key is fully namespace-qualified and has a schema entry.

## 3a. Design to the lower bound

The owner, 2026-11-12: "We are never to design systems that are so inefficient again." Every slow design found that day broke one of these rules: a suite rerun 52 times a day, a docs edit rerunning 280 tests, a fresh system booted for every test, a store reseeded in every fork, a file re-analyzed whole for one changed form, a schema registry recompiled whole, a pull of every attribute, a 4e9-tick floor. Each is checked before a design is built, and again when its cost is measured.

1. **Work the lower bound out first, as arithmetic.** It is the least work the job can be done in: n, the work per item, and the constants in [cost](docs/cost.md#what-things-cost-here) (a fork, a seek, a test, a boot). The design's estimate is held to it, and 10x over stops the work (§3).
2. **Proportional to what changed, never to the whole.** A change of size d costs O(d), not O(N): tests rerun by what they read, a catch-up analyzes the changed forms, a registry recompiles the changed schemas and their dependents, a diff skips shared structure, a plan touches only the keys whose inputs moved.
3. **Done once, kept with its source.** A derived value is computed once, from immutable inputs, and kept with them or reused by their digest. Nothing is recomputed per call, per fork or per process: not a store seed, a compiled pattern, a config read or a file parse.
4. **Warm, not cold.** Never start a process or boot a system to do what a fork of a running one does in milliseconds; never rebuild what a cache keyed by content holds.
5. **Take exactly what is needed, in the shape it is needed.** A pull names the attributes and joins its reader uses: the pattern is the shape of the data, and every agent's context is made by one, so a pattern is never replaced by `*`. Seek an index, never scan it; read a file once; fetch the part, never the whole. Where a library makes the named read cost more than it should (a Datahike named pull seeks once per attribute), the library is fixed and measured.
6. **Waiting is not working.** Wall time far above CPU time means something waits. Find the waiter: a bound, a lock, a lost reply or a queue. Never raise the bound.
7. **The machine is scheduled by the system.** Work is admitted from the cores and the memory, and the critical path goes first. A person never picks shard counts, caps or holds; work in flight stays near the core count.
8. **Nothing is switched off, sampled or waived** to get around a bug or a cost; the cause is fixed (§9).
9. **Measure the result in work done** (ticks, bytes, calls) beside the estimate, before and after, and say which mattered. A claim of "faster" without both numbers is not taken.

## 4. The principles: everything is data flowing through the system

- **If nothing has changed, we do nothing,** and no clock decides anything ([clocks](docs/clocks.md#if-nothing-has-changed-we-do-nothing)).
- **Every need is met by one of six things.** If none fits, stop and say so:
  - a value: immutable Datahike values in one store with many branches ([storage](docs/storage.md));
  - a Flow proc and its channels ([flow](docs/flow.md#the-step-fn-contract-every-proc-follows));
  - a task: desired state on attributes, observed state as facts, one reconciler ([tasks](docs/tasks.md));
  - a process: an agent is a forked OS process with a real REPL, on its own branch ([processes](docs/processes.md#the-agent-process));
  - a render declared on the entity's schema ([render](docs/render.md));
  - an error as data, flowing to whoever can fix it ([errors](docs/errors.md)).
- **Nothing crashes and nothing hangs:** every wait ends, with its value or a typed error saying why it never will ([errors](docs/errors.md#nothing-crashes-and-nothing-hangs)).
- **Never pass or keep a db value:** a kept db value is a reference, `{:branch :commit :t}` ([storage](docs/storage.md#a-kept-db-value-is-a-reference)).
- **A mechanism is built once and reused everywhere.** Before building anything that waits, retries, spawns, answers a request or reads config, find its one implementation.
- **Get out of an agent's way:** it has a real REPL; record what happened, never gate what it may do, and never cut its context ([processes](docs/processes.md#an-agent-is-never-gated)).

## 5. Laws

A check or a test reads each law, and its section says how.

- **Data is entities, refs and indexes;** there are no kinds ([data modeling](docs/data-modeling.md)).
- **Only documented config:** every limit or default is a key read through the one resolver, and reaching a bound is a typed error ([config](docs/config.md#constants)).
- **One store, many branches** ([testing](docs/testing.md#testing-in-the-world)).
- **Every write passes the one path,** checked against the entity's schema ([storage](docs/storage.md#the-write-checks-every-entity-it-touches)).
- **Contracts on every function,** and every contract generates ([checks](docs/checks.md#contracts-armed-at-definition)).
- **No guards:** only a named edge catches ([failure points](docs/failure-points.md)).
- **Effects go through their owner** ([flow](docs/flow.md#effects-one-owner-and-its-workers-per-kind)).
- **Every definition happens once, where it is defined** ([resolution](docs/resolution.md)).
- **Results are reused by input digest;** derived data is a cache, never relied on ([data modeling](docs/data-modeling.md#derived-data-is-a-cache-never-relied-on)).
- **Estimates are proofs:** a cost is work done (ticks, bytes), never time measured under load, and it is held to its estimate (§3 step 2; [cost](docs/cost.md#what-is-measured)).
- **Good tests only:** real assertions, red under a mutation, on data that looks like production's ([testing](docs/testing.md#a-proof-is-focused)).
- **Procs follow the step-fn contract** ([flow](docs/flow.md#the-step-fn-contract-every-proc-follows)).

## 6. The running system, as a Seon agent sees it

Each checkout runs its own system: `bin/system start | status | stop`, on the checkout's ports (`bin/ports`), its store under `runs/`. Reach it through the `seon` MCP server (`bin/mcp`): every op of `seon.server/ops` is a tool `seon_<op>` ([server](docs/server.md#ops)). `seon_context` is what the model reads now; `seon_eval` evaluates in an agent's REPL or in the system; `seon_attach` and `seon_done` work a task as a Seon agent. A helper in a worktree uses its own system: `bin/mcp call OP KEY=VALUE` ([working a task from a worktree](docs/server.md#working-a-task-from-a-worktree)). The system follows main through `bin/update`, never `bin/system stop/start`.

## 7. The wiki is the one home for how the system works

`docs/` is what an agent's context pulls, so a fact that lives anywhere else is a fact no agent reads, and every useless word costs every agent on every turn. Each fact has one home:
- **the wiki:** how the system works now, by topic, briefly; no dated stories, no plans, no history;
- **docstrings and schemas:** what one var or one value is, in a sentence;
- **task entries:** work to do;
- **decisions:** the owner's rulings, with quote, date and where each is stated;
- **the plan:** what is in flight and THE PLAN;
- **git:** history, and every number a merge measured.

Every function, schema entry and deftest cites the wiki section of its mechanism by its address (``docs/<topic>.md#<anchor>``) and never restates it ([references](docs/references.md#the-delimiter-picks-the-depth)). A section links to code and never copies it. A merge updates its section in the same commit, and a section is written once it is true.

## 7a. Words

Name things with Clojure's, its libraries', Jolt's and git's words: entity, attribute, ref, pull, schema, render, html, messages. The words we do not use are in [vocabulary](docs/vocabulary.md#words-we-do-not-use). A stored keyword is renamed by erasing the store, never by a migration.

## 8. The machine

- **Seon's target is a 16 GB MacBook M1** (`bin/check scale`). The machine the sessions build on has 18 cores and 128 GB.
- **Heavy runs** (`bin/build`, `bin/prove`) run under `nice -n 15`; the test system sets its workers from the cores (§3).
- **Never flood it:** anything that makes threads or processes starts at n ≤ 100; one experiment at a time; never make load to reproduce something ([machine](docs/machine.md#processes)).
- **Kill only verified PIDs:** read the full command and its cwd first; never `pkill`, `killall` or a `pgrep -f` pattern. A system's processes end with it.
- **A hang is evidence:** capture it before killing the process ([machine](docs/machine.md#reading-a-hang)).
- **Clean up after yourself:** remove a finished worktree with `bin/worktree --remove NAME`; delete a merged branch (`git branch -d`, which refuses an unmerged one); delete a branch your work superseded (`git branch -D`) once your mail names what superseded it. Leave nothing running. Clean up garbage when you see it, after checking that nothing live uses it ([machine](docs/machine.md#disk-stays-bounded)).
- **Git:** commit explicit paths; never bare `git stash`; never `checkout`, `restore` or `reset` on paths you didn't change; never `git submodule` in a worktree (`bin/worktree --sync NAME`); never push this repository's branches. A cause in a dependency is fixed on a `jolt/<topic>` branch of its fork (`seantempesta/<lib>`), pushed with no force and never to the fork's main.
- **Secrets are never printed.** Paid model calls come from the owner's standing $50 budget, for productive work only ([model](docs/model.md#paid-runs)). Install nothing system-wide. Never write in `~/src/seon`.

## 9. Mistakes we do not repeat

- Fanning out, and building infrastructure instead of the product.
- A number accepted without an estimate to hold it against: a test spending 8,000 times what it should, a suite rerun 52 times a day.
- Switching a mechanism off, or routing around it, because it has a bug: a forked test child that died was answered by turning forks off, and every test ran as a cold process after that. A bug is fixed at its cause; nothing is switched off for it.
- Batching different sessions' branches into one landing, or calling a hand-picked set of tests a prove.
- A rule added after every incident, instead of the cause fixed and the old text deleted.
- A parallel system, or a mechanism built twice.
- A swallowed error, a fake default, a clock standing in for a dependency.
- A check that cannot fail, or one that nobody read.
- A claim passed on unchecked, or the owner's sentence turned into a build before the code was read.
- Dropping a store or rewriting history: that is the owner's act. A branch is not: its commits stay in git.

## 10. Done

It runs end to end through the path agents use; its measured cost is within 10 times its estimate; the merged result is green; its wiki section is true; it moves a step of THE PLAN; and nothing of yours is still running.
