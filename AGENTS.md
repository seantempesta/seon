# AGENTS.md: how we build Seon

Seon is where many agents do real work for their user, each in its own process on its own branch, and the good changes accrete into one program that gets smaller, faster and more certain as it grows. This document serves every Claude session and every Seon agent: **one process for both** (owner, 2026-10-21: "a self reinforcing system where agents learn from each other be it claude agents or seon agents"). It holds the hard rules; each detail is in the wiki (`docs/`), at the address given.

## 0. Start here

You are in `next/`, **Seon on Jolt, built to replace Seon** (`~/src/seon`, the JVM version, read-only reference): everything v1 does, and a v1 capability left out has its reason in `BAGGAGE.md`. A person arriving starts at [README.md](README.md#start-here).

**Read before acting:** this file (§§1–3 first), `docs/plan.md` (what is in flight, and THE PLAN), `docs/README.md` (the wiki's map), `docs/parity.md`, then your task (``resources/seon/tasks/<ns>.edn``) and what its plan cites.

**Find a detail** in this order:
1. `docs/README.md`: every page of the wiki, and how it is kept;
2. your task's `:seon.task/plan` and what it cites, read through `seon_context` (§6);
3. ``git grep -n 'term' -- docs resources/seon/tasks src``;
4. `docs/decisions.md`, for a ruling's own words;
5. the header comment of a `bin/` script, which is that command's manual.

**The running system:** `bin/root status` (the owner's main tree: server port 7888, dashboard http://127.0.0.1:7777/). The standing root's store (`runs/store`) is evidence: never experiment on it, and send that root only read-only evals unless the owner says yes to the write. The Web session owns servers and demos. Experiments use a worktree's own root and stores (`bin/worktree NAME`).

## 1. What Seon is for, and what moving forward means

**Seon does any and all work its user wants** (owner, 2026-10-22: "Do not assume the system is constrained in what it should do for the user"), improving its own code included. Seon grows by accretion: many agents each change a branch, the proven changes merge into main, and the merged code runs next. It must resist growth while it grows (owner, 2026-10-23: "don't hyper fixate on LoC. I want some resistance to growth mostly so agents reuse what's there and improve the core parts rather than making one offs").

**Moving forward means the loop works for real tasks, end to end:**
1. a user's request becomes tasks;
2. an agent works each task from context good enough to finish it in few turns;
3. its proofs go green, its branch merges to main, and main runs the result.

Progress is **turns to green** and **tasks merged**, with every v1 capability present ([parity](docs/parity.md)). Work counts when it moves one of those numbers or unblocks a step of THE PLAN (`docs/plan.md`), infrastructure included: before starting, know which step you serve. Before building, look at what is built (owner, 2026-11-05: "just fucking look around and don't rebuild shit that we've already built"): what an agent reads and a person sees is the pull of its entity ([ctx](docs/ctx.md)) through the render on each entity's schema ([render](docs/render.md)).

Run, then right, then fast. The demo is the spec: if you can't show it running, it isn't done. The system shows what it is doing, live.

## 2. Who does what

- **The owner (Sean)** sets direction and makes the rulings. Bring him one decision at a time with its evidence, through the question tool, your pick first; a well-scoped task or a clear defect needs no asking.
- **Named sessions each own an area** (Context, Web, Optimization), and one managing session keeps the landing order (`docs/plan.md`). A session knows its whole area and never answers for code it has not read (owner, 2026-11-08: "We are moving away from monolithic orchestrators who don't know anything and implementation agents who don't know the big picture").
- **Sessions work together, with no fixed ceremony** (owner, 2026-11-06: "I don't want the work done twice. If you do it well merge it... No more ceremony and mandated fixed policies"): each says by message what it is taking.
- **No fan-out** (owner, 2026-10-28: "This system of launching 10+ agents just burned my tokens and was not productive"). A session starts no other session unless the owner asks, and uses one helper at a time.
- **A helper is hands for a well-specified piece.** It works in a worktree of its own, branched from the session's branch, commits there and stops: no `bin/land`, no push.
- **The session reviews every line a helper writes, and lands it itself; review is never delegated** (owner, 2026-11-08: "Well spec'ed tasks can be handed to sonnet, but the opus agents are responsible for reviewing the code").
- **Seon agents** work tasks through the server (§6). What a session finds missing from their context is a task for Seon.

Asking the owner, helpers by the shape of the work, briefs and reviews: [sessions](docs/sessions.md).

### A session's working rules

- **On start:** read §0's list and the newest entries of `docs/decisions.md`; run `git log --oneline --first-parent -10`, `uptime` and `bin/root status`; tell the owner in a few lines what you will do.
- **Root follows main through `bin/update`**, never `bin/root stop/start` ([merging](docs/merging.md#a-branch-lands-with-binland)).
- **A hang is evidence:** capture it before killing the process ([machine](docs/machine.md#reading-a-hang)).
- **Step back** when about three rounds of tool calls have not answered a question, the work is a detail no step of THE PLAN names, or a fix keeps growing or needs edge cases in several places: write down what is known and tell the owner.

## 3. How work moves

**Work is tasks** ([tasks](docs/tasks.md)). A task is an entry in ``resources/seon/tasks/<owning-ns>.edn``: `:seon.task/plan` (what exists, the cause, what it replaces and deletes), `:seon.task/proofs` (the tests that say it is done) and `:seon.task/needs`. The plan is the hard part, written before anything is built. Your brief is your task: what you learn and have verified goes back at once into it or the wiki section it cites, never only into a report. Order: delete first, then foundations, then fixes. A problem outside your task is filed as a task, never fixed in passing.

**Each session in its own worktree; the main tree stays on main.** A session (Claude's or Seon's) works a short branch, ``jolt/<task-name>``, in its own worktree (`bin/worktree NAME`) and lands it itself. Nobody works or checks out a branch in the main tree (owner, 2026-11-08: "That's just wrong"). A parked branch is landed, or retired by the owner. A session takes the task the owner names, or the first ready task in THE PLAN's order. The end goal (owner, 2026-10-23) is Seon agents doing this work inside the system, with git only to commit what Seon writes back.

**The method:**
1. **Learn.** List what exists before designing; read the wiki sections, tasks and code for the problem, what v1 did, and the field's best known way. Where a section and the code disagree, the task owes a wiki edit.
2. **Plan in a few lines:** the write set, what it deletes, the tests, its wiki section, and a cost line (what n is, its big-O, the number expected; reality 10 times over stops the work). A special case means the wrong design. A change to a dependency or a design rule waits for the owner's go. Aim for 90% of the benefit for 10% of the effort.
3. **Prove first:** work at the REPL as an agent would, since what can't be done from outside can't be done from inside. Prove the idea at n ≤ 100, write a cost proof for anything whose input grows, and see each proof fail for the right reason.
4. **Act:** the simplest code that passes, at the cause, in the owner of the bad value. **A bug is a class until shown otherwise** (owner, 2026-11-03: "Always be suspicious about bugs. Usually fixing them directly is the wrong solution. Root cause and see if we should be doing something that eliminates the bug class."): find what allowed it (an open schema, a guard, a second way of doing a thing), change that so the class cannot recur, then sweep for its siblings. No hacks: no special case, retry or hand-kept list. Its report says what allowed the bug, why the class is now impossible and what the sweep found.
5. **Work it as a Seon agent would**, through the server (§6); what the context lacked is a task. A change to what the owner sees is checked in a browser ([checks](docs/checks.md#checking-a-page-in-a-browser)), and a render computes nothing the system already computed ([render](docs/render.md#a-render-does-no-work-of-its-own)).

**Land small and often; nothing is run twice** ([merging](docs/merging.md#a-branch-lands-with-binland)):
1. **Iterate** at the REPL, then with `bin/test NS` or `bin/test NS/TEST`.
2. **Prove once, when done:** `nice -n 15 bin/prove` in your worktree; never again on a tree it proved green.
3. **Audit** (owner, 2026-11-09: "If you find yourself needing heroics and writing a shitload of code we are doing the wrong thing and we need to fix the design"). The landing session reads its whole diff and writes the audit into the landing message; the managing session reads it and gives its go. The message states the step of THE PLAN, the `src` delta, what it replaces and deletes (a branch that grows `src` names what it deletes), and its estimate beside each number measured ([merging](docs/merging.md#a-landing-message-carries-its-audit)).
4. **Land:** `/Users/sean/src/seon-next/bin/land -F MSGFILE` in your worktree. Never merge by hand; never wait for another session's proof.

- **Combine your own ready branches,** prove once and land once. **Never wait on a landing:** start the next change in a new worktree branched from the landing branch, and never touch a worktree while its `bin/land` runs.
- **A merge that completes a task** removes the task's entry and says `Completes: ns/name` ([merging](docs/merging.md#a-merge-says-what-it-completes)).
- **No regressions** (owner, 2026-10-26: "don't accept regressions for no reason"): a number that got worse is refused unless the message names its reason and who follows it up. A cost bound moves only with the owner's yes.
- **What a new design replaces goes with it** (tasks, wiki text, pointers); code with no caller is cut only with the owner's go.

**Testing: compute only what is needed.**
- **Run only what your change reaches:** `bin/prove` reuses every green stored at its reach digest ([merging](docs/merging.md#a-merge-runs-what-the-change-reaches)). Never repeat a run on an unchanged tree.
- **A green `bin/prove` counts only with its plan:** a jump in what it left out is red until explained.
- **No full runs from a session** (owner, 2026-11-08: "don't waste everyone's time"): `bin/test --all` and `bin/check 100` are the daily run's and a pin bump's ([merging](docs/merging.md#conflicts-and-pins)). A red on main gets an owner and a task within the hour.
- **A test runs on the world**, at the smallest input that shows its claim ([testing](docs/testing.md#testing-in-the-world)).
- **A slow test is a bug** (owner, 2026-11-08: "Slow tests should be treated as bugs and fixed"); **a flaky result is a defect** to fix at its cause, never a reason to run again.
- **Never delete or move a red verdict:** the fix is a new digest ([merging](docs/merging.md#verdicts-are-a-cache)).

**The gate, before any new format, key, layer, namespace, proc or rule** (owner, 2026-10-21): (1) which existing mechanism does this, ours, v1's or the dependency's? (2) what did v1 do here? (3) what is the field's best known way? (4) does it add a format, key, layer or rule, and why can't the existing one do it? The session gathers the evidence and shows it to the owner. No evidence, no approval.

## 4. The principles: everything is data flowing through the system

- **If nothing has changed, we do nothing,** and no clock or timer decides anything, outside three exceptions the owner allowed ([clocks](docs/clocks.md#if-nothing-has-changed-we-do-nothing)).
- **Every need is met by one of six things.** If none fits, stop and say so; never route around them:
  - a value: immutable Datahike values in one store with many branches ([storage](docs/storage.md));
  - a Flow proc and its channels ([flow](docs/flow.md#the-step-fn-contract-every-proc-follows));
  - a task: desired state on attributes, observed state as facts, one reconciler ([tasks](docs/tasks.md)); an OS child is an effect, and its exit is a fact;
  - a process: an agent is a forked OS process with a real REPL, on its own branch, named by role, task and checkout ([processes](docs/processes.md#the-agent-process));
  - a render declared on the entity's schema, `:seon.render/hiccup` for html and `:seon.render/model` for the model's messages ([render](docs/render.md));
  - an error as data, flowing to whoever can fix it ([errors](docs/errors.md)).
- **Nothing crashes and nothing hangs** (owner, 2026-10-28: "I want our system to be robust and to have it not crash or hang ever."): every wait ends, with its value or a typed error saying why it never will, and the agent fixes a failure or messages root when it needs another process ([errors](docs/errors.md#nothing-crashes-and-nothing-hangs)).
- **Never pass or keep a db value:** a kept db value is a reference, `{:branch :commit :t}` ([storage](docs/storage.md#a-kept-db-value-is-a-reference)).
- **A mechanism is built once, and reused everywhere.** Before building anything that waits, retries, admits, spawns, answers a request or reads config, find its one implementation: the wiki section where it lives names it and its owner. Nobody builds a second one, or a one-off beside it.
- **Get out of an agent's way:** an agent has a real REPL; write down what happened, never gate what it may do, and put no cut on its context or its evals ([processes](docs/processes.md#an-agent-is-never-gated)).

## 5. Laws

The laws every change is held to; a check or a test reads each of them, and its section says how.

- **Data is entities, refs and indexes;** there are no kinds ([data modeling](docs/data-modeling.md)).
- **Only documented config:** every limit, bound or default is a key read through the one resolver, with no literals and no `System/getenv` outside the loader ([config](docs/config.md#constants)); reaching a bound is a typed error, never a silent cut ([config](docs/config.md#a-bound-fails-loudly)).
- **One store, many branches,** as in production; a separate store is a last resort ([testing](docs/testing.md#testing-in-the-world)).
- **Every write passes the one path,** checked against the entity's schema ([storage](docs/storage.md#the-write-checks-every-entity-it-touches); `bin/laws transactions`).
- **Contracts on every function,** and every contract generates ([checks](docs/checks.md#contracts-armed-at-definition)).
- **No guards:** only a named edge catches ([failure points](docs/failure-points.md); `bin/laws catch`).
- **Effects go through their owner** ([flow](docs/flow.md#effects-one-owner-and-its-workers-per-kind)).
- **Durability is continuous group commit** ([storage](docs/storage.md#durability-is-continuous-group-commit)).
- **Every definition happens once, where it is defined** ([resolution](docs/resolution.md); `bin/laws resolution`).
- **Results are reused by input digest,** and derived data is a cache, never relied on ([data modeling](docs/data-modeling.md#derived-data-is-a-cache-never-relied-on)).
- **Estimates are proofs,** and cost is work done, never time: no assertion that the machine's load can turn red ([cost](docs/cost.md#what-is-measured)).
- **Good tests only:** real assertions, red under a mutation, on data that looks like production's ([testing](docs/testing.md#a-proof-is-focused)).
- **The library's words and mechanisms** (§7a).
- **Procs follow the step-fn contract** ([flow](docs/flow.md#the-step-fn-contract-every-proc-follows)).

## 6. The running system, as a Seon agent sees it

Each checkout runs its own standing root: `bin/root start | status | stop | next | kill`, detached, on the checkout's ports (`bin/ports`), its store under `runs/`.

Reach it through the `seon` MCP server (`bin/mcp`, a stdio relay): every op of `seon.server/ops` is a tool ``seon_<op>`` ([server](docs/server.md#ops)). `seon_context` is what the model reads now; `seon_eval` evaluates in an agent's own REPL or in `root`; `seon_attach` and `seon_done` work a task as a Seon agent.

**A helper in a worktree uses its own root, never the session's:** `bin/mcp call OP KEY=VALUE` is the same tool on this checkout's root ([working a task from a worktree](docs/server.md#working-a-task-from-a-worktree)). Without MCP, one request line: ``printf '{:op :agents}\n' | bin/ask PORT``.

## 7. The wiki is the one home for how the system works

**Use the wiki for everything about the system** (owner, 2026-10-27: "we should be using the wiki for everything so it gets better because that's what agents are pulling into their context"). `docs/` is what a task's context pulls, so a fact that lives anywhere else is a fact no agent reads. Say a thing once, where it belongs, and cite it from everywhere else ([how the wiki is kept](docs/README.md#how-the-wiki-is-kept)).

Information has one home by what it is, and a fact found anywhere else is moved there:
- **the wiki** (`docs/`): how the system works now, by topic; no dated logs, no plans;
- **docstrings and schemas**: what one var or one value is, in a sentence, beside its code;
- **task entries** (`resources/seon/tasks/`): work to do, its reproduction, cause and proofs;
- **DECISIONS** (`docs/decisions.md`): the owner's rulings, each with its quote, date and where it is now stated;
- **the plan** (`docs/plan.md`): what is in flight, the open owner questions and THE PLAN;
- **git**: history, and every number a merge measured (in its commit message);
- **a session's scratchpad**, outside the repository: working papers, never cited.

- **Every function, schema entry and deftest cites the wiki section that describes its mechanism** (in its docstring, its `:description`, its leading `;;` comment), by its bare address (``docs/<topic>.md#<anchor>``), and never restates it ([references](docs/references.md#the-delimiter-picks-the-depth); an uncited function is a problem, [health](docs/health.md)).
- **A wiki section links to code, never copies it** (owner, 2026-11-09: "Don't put code into docs if we can link to the code.").
- **Merging a change updates its section in the same commit.** Before reporting, read your task's context through the server and confirm it pulls what the task needs.
- **Write the wiki when it is true, not before:** what is verified goes into its section at once; an open design's findings wait until it is settled.

## 7a. Words

**Name things with Clojure's, its libraries' or Jolt's words** (owner, 2026-10-22: "stop using names that aren't clojure or clojure library or jolt or whatever related"), and git's for branches: entity, attribute, ref, pull, schema, render, html, messages. The words we do not use, each with its ruling: [vocabulary](docs/vocabulary.md#words-we-do-not-use). **Waste** stays our word for the turns and tokens an agent spent past what its work needed. A stored keyword is renamed by erasing the store, never by a migration.

## 8. The machine

- **Seon's target is a 16 GB MacBook M1** (`:seon.config.machine/memory-mb`; `bin/check scale` fails past it). The machine the sessions build on has 18 cores and 128 GB.
- **Heavy runs** (`bin/build`, `bin/prove`) run under `nice -n 15`, in parallel while nothing is starved, judged by the cores and free memory (owner, 2026-11-10: "I have 18 cores and load is 8"). Nothing tracked is edited in a tree while a run reads it ([machine](docs/machine.md#builds-and-checks)).
- **Never flood it:** anything that makes threads or processes starts at n ≤ 100; kill your own process past 200 threads; one experiment at a time; never make load to reproduce something ([machine](docs/machine.md#processes)).
- **Kill only verified PIDs:** read the full command and its cwd (`lsof -a -p PID -d cwd`) first; never `pkill`, `killall` or a `pgrep -f` pattern.
- **A root's processes end with it:** kill the root, or its fork server's process group, never a fork server by pid.
- **Leave nothing running when you finish,** and say so.
- **Clean up garbage when you see it, whoever left it** (owner, 2026-10-23: "why not kill the leftover processes? why wouldn't you"), after checking that nothing live uses it. Keep what is evidence or someone's work: the standing store, what git or the store holds, a live session's worktree ([machine](docs/machine.md#disk-stays-bounded)).
- **Git:** commit explicit paths; never bare `git stash`; never `checkout`, `restore` or `reset` on paths you didn't change; never `git submodule` in a worktree (`bin/worktree --sync NAME`); never push this repository's branches. A cause in a dependency is fixed there, on a ``jolt/<topic>`` branch of its fork (``seantempesta/<lib>``), pushed with no ask: no force, never a fork's main, verified with `gh api` after.
- **Secrets are never printed.** Paid model calls spend from the owner's standing $50 budget, on productive work only, with the context checked first; past it, the owner's go ([model](docs/model.md#paid-runs)). Install nothing system-wide. Never write in `~/src/seon`.

## 9. Mistakes we have made, and do not repeat

The rules are above; each mistake links where its incident is told.

- **Fanning out** ([sessions](docs/sessions.md#no-fan-out)).
- **Building infrastructure instead of the product** (owner, 2026-10-28: "we built out a lot of infrastructure thinking it would speed things up and it really hasn't"; [decisions](docs/decisions.md#build-the-product-not-infrastructure-owner-2026-10-28)).
- **Trees before the forest:** a detail no step of THE PLAN asks for; a cause guessed instead of measured.
- **A clock standing in for a dependency or a workload** ([clocks](docs/clocks.md#if-nothing-has-changed-we-do-nothing)).
- **A swallowed error, an ignored answer, a fake default** ([errors](docs/errors.md#unknown-is-never-a-default)).
- **Routing around a principle:** a fallback instead of an error; a runner or wrapper beside the reconciler; a store per test; an env var instead of config; regex over code instead of the compiler's facts; script order instead of `:needs`; a thread instead of a fiber; stdin as a lifecycle.
- **A parallel system, or a mechanism built twice** (owner, 2026-11-04: "I don't want agents to keep building parallel systems"; [decisions](docs/decisions.md#no-parallel-systems-owner-2026-11-04)).
- **Reinventing what v1 already solved,** designing before researching, building a half-baked plan, or too many worktrees at once on overlapping work.
- **A clipping function** ([render](docs/render.md#no-clipping)).
- **A check that cannot fail,** that only prints, that ran nothing, or that nobody read ([machine](docs/machine.md#reading-a-number)); whole files copied over newer changes. When a rule changes, read every step it touches.
- **Dropping a store, a branch or history:** the owner's act. Only the standing store, while it holds no user's data, is erased when a change needs it, the other sessions warned first ([machine](docs/machine.md#disk-stays-bounded)).
- **Throwing away good work, or keeping its slow copy** (owner, 2026-10-23: "don't throw away good work have agents learn from it if you want a clean implementation. No crappy slow repetitive code").

## 10. Done

It runs end to end through the path agents use; you have used it as a person would and checked its claims on the real thing; the merged result is green; its numbers are in the commit; its wiki section is true; you can watch it work; it moves a step of THE PLAN; and nothing of yours is still running.
