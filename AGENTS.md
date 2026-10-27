# AGENTS.md: how we build Seon

Seon is where many agents do real work for their user, each in its own process on its own branch, and the good changes accrete into one program that gets smaller, faster and more certain as it grows. This one document serves every Claude session and every Seon agent: **one process for both** (owner, 2026-10-21: "a self reinforcing system where agents learn from each other be it claude agents or seon agents"). The same tasks, the same server, the same context from the same wiki, and lessons go into the same sections.

## 0. Start here

You are in `next/`, **Seon on Jolt, built to replace Seon** (`~/src/seon`, the JVM version, now read-only reference): everything v1 does, smaller, faster and more certain; a v1 capability left out is left out on purpose, with its reason in `BAGGAGE.md`.

Read before acting: this file (§§1–3 first), then `docs/plan.md` (what is in flight, and THE PLAN), then `docs/README.md` (the wiki's map) and `docs/parity.md`, then your task (``resources/seon/tasks/<ns>.edn``) and what its plan cites.

The running system: `bin/root status|start` (the owner's main tree: server port 7888, dashboard http://127.0.0.1:7777/); Claude reaches it through the `seon` MCP server (§6). The standing root's store (`runs/store`) holds kept runs, which are evidence: never experiment on it. Experiments run in a worktree (`bin/worktree NAME`) on that worktree's own root and stores.

## 1. What Seon is for, and what moving forward means

**Seon does any and all work its user wants** (owner, 2026-10-22: "Do not assume the system is constrained in what it should do for the user"): editing files, running commands, fetching from the web, working in any repository, and improving its own code. Software usually grows by tangling, so cost and defects outrun capability. Seon grows by accretion instead: many agents each change a branch, the proven changes merge into main, and the merged code runs next. That recursive loop is the heart of it, and `next/` must resist growth and stay fast while it grows (owner, 2026-10-23: "don't hyper fixate on LoC. I want some resistance to growth mostly so agents reuse what's there and improve the core parts rather than making one offs"): a change reuses and improves the core rather than adding a one-off beside it, and a merge that grows `src` names what it replaces.

**Moving forward means the loop works for real tasks, end to end:**
1. a user's request becomes tasks;
2. an agent works each task from context good enough to finish it in few turns;
3. its proofs go green, its branch merges to main, and main runs the result.

Progress is measured as **turns to green** and **tasks merged**, with every v1 capability present (`docs/parity.md`). A piece of work counts as progress when it moves one of those numbers or unblocks a step of THE PLAN that does. Infrastructure (storage, checks, builds) matters as far as it unblocks the loop, and no further.

**The current priorities are THE PLAN in `docs/plan.md`,** in order. Before starting anything, know which step it serves.

How we keep the first version's spirit: run, then right, then fast; the demo is the spec (if you can't show it running, it isn't done); the system shows what it is doing, live; numbers are honest (estimated first, measured, with what wasn't measured stated); a cause in a dependency (Chez, Jolt, Datahike, konserve) is fixed there, on a `jolt/<topic>` branch; every addition names what it replaces.

## 2. Who does what

The owner rules, root directs and merges, worktree sessions each work one task, and Seon agents work the same tasks through the same server.

- **The owner (Sean)** sets direction and makes the rulings. Bring him one decision at a time, with evidence and, for a big one, priced options (the simplest viable first, your recommendation marked).
- **Root** (the orchestrating session; in Seon, the root agent) owns **direction, THE PLAN, merging into main and the docs**. Its output is the whole system moving forward, not findings it made itself. Root:
  - keeps several things in flight at once (worktrees implementing, agents researching, a batch merging) and records them in the plan's "In flight";
  - **delegates investigation, debugging, measurement, research and implementation**: subagents (Explore or general-purpose) for reading many files, auditing or research; `bin/agy-agent` (Gemini) for root-cause hunts and web search; sessions in their own worktrees for code. Independent questions go to agents launched in parallel, in one message;
  - **investigates personally only what fits in a few tool calls** (one status, one grep, one file). Past that, or once a guess has failed, root writes a brief, launches an agent and turns to other work;
  - checks what agents return (a reproduction, a file:line, a number) before acting on it, and puts the result into a task, the plan or the wiki section it concerns;
  - merges verified work into main (§3) and keeps the plan and the wiki true.
- **Worktree sessions** are Claude sessions each in its own git worktree on its own branch, working one task (§3): they write the proofs, the code and the wiki edit, prove their change, and report. One that has found no cause or clearly correct fix after about fifteen minutes stops and reports what it measured. One whose code contradicts its plan stops and sends the plan back with the evidence; nobody redesigns on the fly.
- **Starting sessions** (owner, 2026-10-23): a session may run research subagents (Haiku, for web search and reading) on its own. It asks root before starting another Claude session; root keeps the one list of what is running. The owner may tell any session to start one directly, and that instruction overrides this rule.
- **Seon agents** are forked processes working tasks through the same server, tasks and context (§6). What our worktree sessions find missing from that context is a task for Seon.

### Root's working rules (what a fresh root needs; the owner's rulings behind them are in docs/decisions.md)

What a fresh root needs to run the work; the owner's ruling behind each rule is in [decisions](docs/decisions.md).

- **On resume, read before replying to anything:** this file, `docs/plan.md` (Now, In flight, THE PLAN), `docs/decisions.md` (the newest entries stand), `docs/restart.md`, and the last lines of `claude_scratch/merge-log.md`. Then `git log --oneline --first-parent -15`, `uptime`, `bin/root status` and the sessions list, and write the owner a five-line state summary before acting.
- **Sessions:** Claude sessions are filed in the project's sidebar group, with titles that say their current work. Model by task: Opus for design and dependencies, Sonnet for mechanical work, reviews and the merge session, Haiku or Sonnet for browser testers. Archive a session once its branch has merged and it holds nothing else.
- **The app caps one session's cross-session messages at about 10 per owner message.** So standing rules go in this file, not in messages, and root's current orders and assignments go in `claude_scratch/root-orders.md` (gitignored, edited in place), which every session reads before each new task, branch, run or ready report; each session keeps its status in `claude_scratch/status/<name>.md`, rewritten in place. Batch what must be sent, most important first. Sessions report ready to the merge session when one runs, else to root.
- **Heavy runs** (`bin/prove`, full checks, builds of a changed tree): up to three at once while `uptime` load stays under ~8 on the 18-core machine. Never tell every session to run at the same moment. Each run writes under a directory of its own, `target/prove/<run-id>` (it names it on its first line), so two runs on one checkout never overwrite each other; the main tree's runs belong to the merge session. Nothing waits on main's verification: no lock (owner, 2026-10-27, "Remove it"); load stays down by not running the same work twice.
- **Root acts without asking** on merges of verified branches (the merge session does them), pushes of pinned fork commits, and cleanup of verified garbage: a leftover process or a merged worktree, checked by PID, path and cwd. A worktree any config names is live, as `.worktrees/merges` is for main. Owner questions are for rulings: a new mechanism, format or rule; priorities; spending.
- **A hang is evidence:** before killing a process stuck at 0% CPU (a test, an agent, a root), capture `kill -INFO PID` (Jolt's fiber dump on its stderr: each fiber, what it is parked on, its frames), `sample PID 5`, `lsof -p PID` and its thread list, and put them on the defect's task. A hang killed without them is a hang nobody can fix.
- **Kill only verified PIDs:** read the full command and cwd (`lsof -a -p PID -d cwd`) first, never `pkill` or `pgrep -f` patterns. `./seon` runs from `target/` with a relative command line.
- **A pin bump is verified** with `git ls-tree HEAD reference-code/<lib>` and `gh api repos/seantempesta/<lib>/commits/<sha>` before it is reported.
- **Fewer sessions, each with one job** (owner, restart of 2026-10-24, docs/restart.md): at most five working sessions plus merge; no session starts another without root's go; a session is archived once its branches merge (the owner asked first).
- **An independent audit every 10 merges or once a day,** whichever comes first (restart of 2026-10-24): an agent that has not seen the work reads the merges since the last audit against §3's value rules and §9, and files what to cut; root assigns the cuts.
- **A merge is verified by `bin/prove --merge` in the main tree** (after `nice -n 15 bin/build`): it holds main's lock, runs every test and check step the change reaches with no green at its digest, process tests included, contracts and smoke among them, ends on one MERGE OK/FAILED and writes its own line to `claude_scratch/merge-log.md`. Read every red it names before keeping or reverting; never a hand-named list of tests (one missed render-test twice, 2026-10-26). **Nothing tracked is edited or committed while it runs** (the binary goes stale). **Restart root after a merge with `bin/update`**, never `bin/root stop/start`, until task seon.fx/the-source-tree-follows-main merges: the standing root imports main's program from `.worktrees/merges`, which only bin/update moves (it froze for 262 commits on 2026-10-26). After a restart, check that a just-merged var's entity is on root's main. A design the size of a subsystem gets an independent review before it is approved (2026-10-26).
- **Root merges verified fixes as they come in** (owner, 2026-10-24: "merge fixes as they come in"): a fix whose report carries its numbers, its self-review rating and a green `bin/prove`, when no merge session is running; the merge steps are the merge session's below.

### Briefing an agent

A brief is complete, or the agent spends its turns rediscovering. Every brief states:
1. **the question or task**, and where it sits in THE PLAN (why it matters now);
2. **what is known**, as paths: the task entry, wiki sections, `claude_scratch/` papers, the exact failing command and its output, earlier agents' findings;
3. **the scope**: read-only or which worktree it may write; what not to touch (the standing store, `~/src/seon`);
4. **what done looks like**: the shape of the answer and the evidence it must carry (a reproduction form, file:line, measured numbers, proofs red → green);
5. **where the result goes**: a file in its worktree or `claude_scratch/`, plus a short reply.

For a worktree session, the brief is its task entry (§3) plus its worktree name. When the cause of a problem is unclear, send two agents with independent briefs; neither sees the other.

### Forest before trees

At each owner message, and before each new piece of work, root states in two lines: **the goal (§1), the step of THE PLAN this serves, and what else is in flight.** Stop and step back when:
- root has spent about three rounds of its own tool calls on one problem without an answer;
- the work is a detail (one regression, one byte count, one log) that no current step of THE PLAN names;
- a fix keeps growing, or needs edge cases in several places (the model is wrong: back to the plan);
- the owner says "forest", "step back", "delegate", or asks the same thing twice.

Then write down what is known, hand the detail to an agent with a brief, and return to the plan.

## 3. How work moves

The same method serves our worktree sessions and Seon's agents: small changes, integrated often, testing the merged result and running only what the change reaches.

**Work is tasks.** All work is a task in ``resources/seon/tasks/<owning-ns>.edn`` (`my.root/load-tasks!`):
- `:seon.task/plan`: what exists, what v1 did, the cause, what it replaces and deletes;
- `:seon.task/proofs`: the deftests and cost proofs that say it is done;
- `:seon.task/needs`: what must merge first.

Its references are derived from the plan's citations by the one parser ([references](docs/references.md)), and the names it changes are its write set. **A worktree session's brief is its task** (owner, 2026-10-21: process, not whim): root writes what it needs into the task and the wiki sections it cites. What anyone learns goes back into the task or that section, never only into a report. Order: delete first, then foundations (a shared interface merges first as its own small task), then fixes.

**Every ready task runs now, in parallel** (owner, 2026-10-21: "get rid of all of it": no cap, no locks, no waiting). Worktrees are truly independent (owner, 2026-10-22: "Each lane should be able to do anything they want to the codebase, fully test it and then we can shut it down and merge it"): each works in its own worktree (`bin/worktree NAME`: `.worktrees/NAME` on `jolt/NAME`) on its own root and stores, may change any file, tests there what its change reaches, and stops; root merges and resolves conflicts. Write sets inform root's merge order; they never restrict a worktree. One waiting on a dependency reads and plans, and keeps nothing running. **A session that finishes takes its next task itself** (owner, 2026-10-23: "lets focus on making you not the bottleneck"): the first ready task in THE PLAN's order (`resources/seon/tasks/`, `:needs` merged, within its expertise) that no branch claims yet. It claims it by creating the branch, `bin/worktree <task-name>` (git refuses a second branch of one name, so two sessions never take one task; no lock), and says which it took in its next report. Root is asked only for a ruling, a brief for work no task describes, or a conflict between tasks. Claude sessions use worktrees until Seon agents can do this work inside the system (owner, 2026-10-23); the end goal is Seon agents in the system, with git only to commit the changes Seon writes back.

**A worktree session's method:**
1. **Learn:** find the wiki sections, tasks and code for the problem; read what v1 did (`~/src/seon`, read-only); research the field's best known way (web, `bin/agy-agent`), and verify it. Cite what you find in the plan; where a section and the code disagree, that is a wiki edit the task owes.
2. **Plan in a few lines:** the primitive, the write set, what it deletes, the tests, its wiki section. A change to a dependency or a design rule waits for root's go. No new layers, runners or wrappers.
3. **Prove first:** prove the idea in a REPL at n ≤ 100, write a cost proof for anything whose input grows, and write the proofs and watch them fail for the right reason.
4. **Act:** write the simplest code that passes, at the cause, in the owner of the bad value.
5. **Work it as a Seon agent would**, through the server (§6): attach, read the context, evaluate, `seon_done`. What the context lacked is a task. If the owner will see it (a page, a render), have a subagent with the browser compare the page against the facts.

**Integrate small and often.** Branches live hours; merge main into the branch whenever main moves. To be merged, a worktree session merges main in, runs plain `nice -n 15 bin/prove` (the in-process tests its change reaches, each reused by digest from the shared test-results/) and reports ready: commits, numbers against estimates, what it deleted, and that nothing of its is running. It runs no `--merge`. **The merge session merges** branches into main (never copying files; when no merge session runs, root merges and sessions report ready to root): one session, named merge, does only this, for root, so integration never waits on root's turns. A session reports ready to it, never to root, by SendMessage to the session titled "Merge: integrate ready branches into main". A ready branch that already contains current main and whose own plain `bin/prove` ran at that tip (with the process tests its change reaches) is merged `--ff-only` with no run on main (owner, 2026-10-27: "Why are we reverifying on main? Don't do unnecessary work if the agent on the worktree can validate the merge."); prefer asking a branch one merge behind to merge main and re-prove. Only when it combines branches into a tree nobody has tested (two or more ready, or one without main) it runs ONE `nice -n 15 bin/build` and ONE `nice -n 15 bin/prove --merge` for the batch (process tests read the whole tree, so a `--merge` per branch is about N squared runs). It checks the pins (`git ls-tree`) and, after a pin bump, moves the main tree's submodule checkouts to them before building. A red names its test: revert only the branch whose change reaches it (`(my.agent/tests-reaching ...)`, or its diff against the test), send it back with the log, keep the rest. The dev-speed branches (reuse, the shared test root, tree-id) merge as soon as their targeted tests are green and their first full run is main's next. Then `bin/update`, check that a just-merged var's entity is on root's main, remove the merged worktrees (`bin/worktree --remove NAME`) and tell the sessions and root in one line a batch. It does not wait for a batch to fill: it merges what is ready whenever no batch is verifying. It reads the `src` diff stat; a merge that grows `src` names what it replaces, or why no existing part could do it (§1: resistance to growth, not a line count). **Standing orders** (owner, 2026-10-27: "If we merge 5 branches and there are no conflicts and then we run all the tests and they all pass we just saved 5 separate runs … don't just be a gatekeeper"): the merge session merges EVERY branch reported ready whenever no `--merge` is running, with no waiting for counts or reviews, then runs one `bin/build`, one `bin/prove --merge` and `bin/update`; reds go to their owners to fix forward, and it reverts only what stops a root booting or breaks most tests. It acts without waiting on root, tells root in one line per batch (what went in, the verdict, the reds and their owners), and messages root at once when blocked or stuck. A worktree session that is idle takes its next task itself (THE PLAN order, its area's task files, root's audit lists), reports ready when its targeted tests are green, messages root at once when blocked or stuck, and does not wait to be told. **A worktree session reviews its own branch before it reports ready** (root's merges are the bottleneck; a review round trip through root costs an hour): one Sonnet subagent reads `git diff main...HEAD` (and any fork commits it pins) against §4, §5, §9 and §7a, with file:line findings; the session fixes them, merges main, checks its pins equal main's or are its intended bump (pushed to its fork), runs plain `bin/prove` (whose health step holds the branch to what a Seon agent is held to: the problems it caused since its merge base with main, never the ones main already had, which are root's to show; [health](docs/health.md#checks-are-callers-of-seon)), and reports "self-review: N findings, all fixed" with its verdicts. The merge session then checks the verdict and the pins and merges it into the batch, without another round trip. **Every branch is reviewed before it merges** (owner, 2026-10-22: "make sure agents are doing good work and not building fragile fences and guards again"): a reviewer reads its diff against main for §9's mistakes (a guard, a fake default, a clock deciding, a second mechanism, a fix at the reader instead of the producer, an old §7a word, a test that cannot fail), each finding with file:line and its right fix; the worktree session fixes them before its branch merges. The mechanics (exit statuses, what a merge runs, batching, conflicts, pin bumps): [merging](docs/merging.md).

**Every change earns its place** (restart of 2026-10-24, docs/restart.md). A branch's report states the step of THE PLAN it moves, what it replaces or deletes, its `src` line delta and its measured value (turns to green, ticks, bytes, wall time). It compares each number with main's before it; a number that got worse is refused unless the report names its reason and who follows it up (owner, 2026-10-26: "don't accept regressions for no reason"). A merge that grows `src` with no measured value, or adds a namespace, a config key, a schema attribute, a proc, a route or a second way to do something, waits for root's gate (the four questions below) with its evidence; "nothing reads it yet" is a reason to wait, not to merge.

**Review for value, not only for §9.** The self-review and the merge reviewer each rate the branch worth it, questionable or overbuilt, and for the last two say what to cut. Overbuilt goes back. Prefer deleting to adding; a fix that adds a layer to work around another layer is the wrong fix.

**No regressions get through.** The merge runs `bin/prove`, the process tests the change reaches, and the cost proofs on any pin bump, and leaves its greens at main's digest (a second `bin/prove` on main reuses everything). Once a day, and before any freeze or cutover, the full run (`bin/test --all`, `bin/check 100`) runs on a clean main checkout. A red on main gets an owner and a task within the hour, and nothing touching its area merges until it is green. A green `bin/prove` counts only with its plan: the merge compares the plan's line (keys, tests, left out, to run) with main's before it, and a jump in what is left out is red until explained (2026-10-25: a quoted name in a render made 662 of 686 tests look like process tests, and `bin/prove` ran 29 of them while saying OK).

**A view is a render of what already exists, checked in a browser** (owner, 2026-10-24: "the debug page should be doing basically zero work … in the past I've asked for the debug page and entirely separate machinery that is somehow slower is built"). A page shows records through their views and the render cache; it never recomputes what the system already computed (a debug page shows what was sent, as recorded, not a rebuilt request), and its cost proof shows it costs what changed. A session whose work changes what an agent is sent or what the owner sees opens the page in a browser (a subagent with the built-in browser) before it reports.

**Measure before and after.** Every perf or cost claim carries numbers from the same input before and after, and every "faster" is checked by an independent run. `claude_scratch/perf-<date>.md` stays current; a regression found there is a task the same day.

### Testing: compute only what is needed

**Every computation must earn its time** (owner, 2026-10-22: "I want faster development"; "We should always be trying to minimize extra computations and do smarter testing"). A run that cannot tell you anything new is waste, and waste slows every worktree on the machine.
- **Run only what your change reaches: `bin/prove`.** It runs the tests and the `bin/check` steps whose reach digest has no green result, and reuses every other (§5, results reused by input digest), so an unchanged tree runs nothing; `bin/test` and `bin/check` without arguments are its two halves, `bin/test NS` one namespace, `bin/check MODE` one step. Seon agents run the same selection (`(my.agent/prove)`, the turn's observe, the judge's `:tests`). Then `bin/check smoke`.
- **Iterate at the REPL first.** Prove an idea at n ≤ 100 in a REPL before any suite runs.
- **Long runs only when the work is done** (owner, 2026-10-22: "Don't run long tests unless we are pretty sure it's done"): the full `bin/test` and `bin/check 100` (about 10 minutes) run once per worktree session, when it believes its task is finished and its review findings are fixed. Never while iterating, never twice for the same tree, never "just to be safe".
- **Main's batch runs the process tests once:** `bin/prove --merge` on the merged batch (in-process greens come back by digest wherever the batch did not move them); the full suite runs once a day.
- **Never repeat a run on an unchanged tree.** A green result at a digest is the answer; reuse it. A flaky result is a defect to fix at its cause (a clock, a race), never a reason to re-run.
- **The full runs are explicit:** `bin/test --all` and `bin/check 100`.

**Root's gate, before approving any plan, format, key or rule** (owner, 2026-10-21): (1) which existing mechanism does this, ours, v1's or the dependency's? (2) what did v1 do here? (3) what is the field's best known way? (4) does it add a format, key, layer or rule, and why can't the existing one do it? An agent gathers the evidence; root shows it to the owner. No evidence, no approval.

**Learn as we go.** Each merge's lesson goes into the wiki in the same commit (the section where the problem lives, linked from the others like it). A failed run's cause stays on its task. A problem outside your task is filed as a task (what you saw, the cause, what correct looks like, its proofs), never fixed in passing.

## 4. The principles: everything is data flowing through the system

**If nothing has changed, we do nothing.**
- No proc steps, renders, pings, flushes or polls on an idle system.
- **Reactive:** a proc reports its own problems as facts and its changes as events. Health is the absence of open problem facts.
- **No clock or timer decides anything.** Work runs because it arrived, is admitted by its measured workload, and parks when there is none. Time is measured and recorded, never acted on: `bin/laws clocks` lists every place time decides and `bin/laws measures` every clock read, each named in [clocks](docs/clocks.md) with its reason; an unnamed one is red.
- **The exceptions:**
  - the cron proc, which only puts a message on a queue at a scheduled time;
  - a message's delayed insert (core.async `timeout`) when a provider says "retry after a short wait";
  - the kernel's TCP keepalive for a silent network peer.

Every need is met by one of these. If one doesn't fit, stop and say so; never route around them:
- **A value.** Immutable Datahike values in one store with many branches. Agents own their branch; root owns main. A record is a fact, and history is kept (v1's ruling; `:seon.config.store/keep-history?`, docs/storage.md#history).
- **A Flow proc and its channels.** Every component is a step-fn proc declared as data (`resources/seon/system.edn`), with bounded channels.
  - A dependency is a channel a proc parks on, and back-pressure is a full one.
  - A run ends when the message count reaches zero.
  - Procs run on fibers; a blocking foreign call gets a declared thread.
- **A task.** All work is task entities: desired state on attributes, observed state as facts, `:needs` between them. One reconciler closes the gap at the cost of what changed. Merges, checks and builds are tasks; an OS child is an effect, and its exit is a fact.
- **A process.** Agents are forked OS processes:
  - fast (copy-on-write sharing of the precompiled program);
  - free (a real, unrestricted REPL);
  - isolated (each on its own branch, its crashes and memory its own);
  - named by role, task and checkout;
  - ended when their run ends.
- **A render.** Every view, the model's context included, is a render declared on the value's schema, over the one printer.
- **An error is data**, flowing to whoever can fix it. A defect holds its message (per key) until the code is fixed. A typed error goes to the requester, agent or task that can act on it.

**A mechanism is built once, and reused everywhere.**
- Before building anything that waits, retries, admits, holds, spawns, watches a process, answers a request or reads config, find its one implementation: the wiki section where that mechanism lives states the pattern and names its one implementation and owner.
- If it doesn't exist, its owner builds it; nobody builds a second one.
- Growth is resisted, not counted (owner, 2026-10-23): new work reuses what is there and improves the core part it needs, so the next change can reuse it too. A one-off helper, render or wrapper beside an existing mechanism is the mistake to avoid; line counts are not a target.

**Get out of an agent's way.**
- An agent has a real REPL, and Clojure's own semantics are the interface. Record what happened; never gate what an agent may do.
- When you feel like adding a check, ask three questions:
  - What would a plain REPL do?
  - Am I recording or judging?
  - What observed thing breaks without it?

## 5. Laws

The laws every change is held to; a check or a test reads each of them.

- **Only documented config.** Every limit, bound or default is a key in `resources/seon/config/default.edn`, with its unit, provenance and what happens at the bound, under a `seon.config.*` schema.
  - It's read through one resolver (task → model → provider → config).
  - No literals, no `System/getenv` outside the loader.
  - Ports too: each checkout has its own (`bin/ports`).
- **One store, many branches,** as in production. Tests, checks and runs take a branch of one seeded store. A separate store is a last resort, and says why where it is made.
- **Contracts (Malli function schemas) on every function,** instrumented at definition, and every contract must generate (`mg/check` in `bin/check`).
- **No guards.** Every outside-world failure point is named in the failure points page (`docs/failure-points.md`), with its one edge function and declared error. Nothing else catches: `bin/laws catch` lists every `catch` and is red on one the page does not name.
- **Effects go through their owner** (`my.fx/call`, `my.fx/send`). The asker records the outcome on its own branch.
- **Durability is continuous group commit.** A write is answered when committed in memory. One followed by an outside effect waits for the sync that covers it.
- **Every definition happens once, where it is defined.**
  - Behaviour varies through the environment (a forked process holding exactly its definitions) or through data.
  - `intern`, `alter-var-root` and `with-redefs` are code smells (owner, 2026-10-21: "intern isn't banned it's a code smell"). A legitimate use builds vars programmatically in a namespace you own, where no definition form exists (a REPL harness, a test environment), and its docstring says why. Filling a gap in another namespace (a library's, Jolt's stdlib) is fixed in that namespace's owner instead. Stub-by-rebinding is replaced by a test environment that holds the stub's definition. `bin/laws resolution` lists every such site in `src`, each named in [resolution](docs/resolution.md) with its reason.
- **Results are reused by input digest.** A test's result is keyed by the digest of everything it reaches, a check step's by its build's source, a build's by its sources. Unchanged inputs are never run again.
  - Content-keyed reuse is allowed for units measured as expensive; no other caches, and never one keyed by time.
- **Estimates are proofs.** A function whose input grows carries a cost proof. **Cost is work done (ticks, bytes, calls), never wall or CPU time** (owner, 2026-10-23: "Work done, not scheduled time slices"). An overrun, or growth steeper than the work needs, is red, judged on the exact dimensions (bytes and contracted calls, which the same code on the same input counts the same on any machine). Wall and CPU time are measured, recorded and shown, never red: a verdict is reused by digest, and time moves with load. The same holds for every test and check step: no assertion that the machine's load can turn red, such as a measured time against a bound ([cost](docs/cost.md)).
- **Good tests only:** real assertions, sensitive to mutation, never timing-only, never a placebo a stub would pass. A test's sample data must look like production data, or its numbers lie.
- **Use the library's words and mechanisms:** Clojure, core.async and Flow (`docs/core-async.md`, `docs/flow.md`), Datahike, Malli, git, nREPL.
- **Procs follow the step-fn contract:**
  - a Var with four arities, whose `describe` declares ins, outs, an honest `:workload` and a ping-map;
  - `transform` returns `[state' outputs]`, with data outputs;
  - a step never blocks on another proc (request/answer with credit, `{:id :reply}`, exactly one answer);
  - state is plain data under its schema, and a host object lives only in its owning proc;
  - buffers are bounded by their loss semantics;
  - no thread, future or atom outside procs, except an edge reader for a blocking read.

## 6. The running system, as a Seon agent sees it

Each checkout runs its own standing root with `bin/root start` (also `status`, `stop`, and `next`: stop root and start the newest generation's binary, the one the `:build` effect made from the last merges commit, on the same store).
- It runs detached, on the checkout's ports (`bin/ports`; the owner's main tree uses 7888, with the dashboard on 7777).
- Its store is kept under `runs/`.

Reach it through the `seon` MCP server (`bin/mcp`, a stdio relay to the server, which answers MCP itself):
- `seon_agents`;
- `seon_eval` (an agent's own REPL, recorded `:outside`, or `root`);
- `seon_context` (what the model reads now);
- `seon_attach` and `seon_done` (work a task as a Seon agent).

Without MCP, one line works:
```
printf '{:op :eval :agent "root" :code "(my.root/problems sys)"}\n' | nc -w 10 127.0.0.1 PORT
```

## 7. The wiki is the one home for how the system works

**Use the wiki for everything about the system** (owner, 2026-10-27: "we should be using the wiki for everything so it gets better because that's what agents are pulling into their context"). `docs/` is what a task's context pulls, so a fact that lives anywhere else is a fact no agent reads. Mechanisms, rulings, measurements and the reasons for them are written into the wiki page where the problem lives, in the commit that changes the system. Never duplicate the wiki: say a thing once, where it belongs, and cite it from everywhere else.

Where each kind of information lives, and how the wiki that holds the system's memory is kept.

- **Pages:** ``docs/<topic>.md``, one concept per page; `docs/README.md` is the map, and it states how the wiki is kept (one home per concept, a leading sentence that stands alone, patterns in their topic sections, everything linked, current truth rather than a dated log). Sections are addressed `topic#slug` and mirrored in the database.
- **Every function, schema entry and deftest carries a citation to the wiki section (or the plan step) that describes its mechanism.** Its docstring says what this var is, in a sentence, and cites the section for how it works; it never restates the section. A function's docstring is checked (the `:uncited-var` problem class, [health](docs/health.md)); a deftest's leading `;;` comment and a schema entry's `:description` carry the citation too, not yet checked. The citations are read by one parser from the text itself ([references](docs/references.md)), and these are the forms it looks up:
  - a section: its bare address ``docs/<topic>.md#<anchor>`` (GitHub's slug of the heading), which pulls the heading and its lead (summary), or a link to it, which pulls the section (doc); a page the same way. A docstring wants the bare address;
  - a var: ``ns/name`` (a `#'` before it is punctuation); a schema key: ``:ns/keyword``; a task: ``ns/task-name``; a file: its path (never ``path:line``: cite the var);
  - **the delimiter picks the depth** (bare word summary, one backtick or a link doc, three backticks code; two backticks a literal that cites nothing; a word inside a `"..."` string is data): [references](docs/references.md#the-delimiter-picks-the-depth) has the rules and their limits, which this section does not restate.
  - Example, a docstring's text: Writes the merged files back to the merges branch. See docs/source.md#commit-the-merges-branch.
  - A comment that records where a number or a choice came from (its provenance, in `default.edn` or a source file) may name an archived scratch paper; what a reader needs to understand the mechanism is in the wiki.
- **Merging a change updates its section in the same commit.** Before reporting, read your task's context through the server and confirm it pulls what the task needs.
- **`claude_scratch/` is the Claude sessions' scratch space,** for working papers, evidence and logs a session needs while it works (`merge-log.md` is written there). It is never system documentation. Anything in it that is still true moves to the wiki, and then the paper moves to `claude_scratch/archive/` in the same commit; no live doc cites a scratch paper as current.

### Where information lives

Each kind of information has one home, and a fact found anywhere else is moved there:
- **the wiki** (`docs/`): how the system works now, by topic; no dated logs, no plans;
- **docstrings and schemas**: what one var or one value is, in a sentence, beside its code, with the citation to its wiki section; a key's meaning is its comment in `resources/seon/config/default.edn`. A page cites the var rather than restating it, and a docstring cites the page rather than restating it;
- **task entries** (`resources/seon/tasks/`): work to do, its reproduction, cause, proofs and what it deletes;
- **DECISIONS** (`docs/decisions.md`): the owner's standing rulings, one line each with its quote, date and where it is now stated;
- **the plan** (`docs/plan.md`): what is in flight, the open owner questions and THE PLAN, on one screen;
- **git**: history: who did what, when, and every number a merge measured (in its commit message);
- **`claude_scratch/`**: a session's working papers only (above).

## 7a. Words

**Name things with Clojure's, its libraries' or Jolt's words** (owner, 2026-10-22: "stop using names that aren't clojure or clojure library or jolt or whatever related"; §5, "Use the library's words"). Write the right word in every new text, and **fix an old word wherever you come across it** in what you are already changing (code, docstring, doc, task, commit message). A file or command name keeps its old spelling until a rename task moves it; your prose still uses the new word.

| Old house word | Say instead | Whose word |
|---|---|---|
| verb | function (by its var, `my.task/fail!`); an `:op` for a request type | Clojure; nREPL `op`, MCP `tool` |
| lane | worktree session (a session in its own git worktree, on its own branch) | git |
| landing, land | merge (into main) | git |
| door, entrance | server (root's request server; the `seon_*` MCP tools) | nREPL, MCP |
| dragon | failure point (and its edge function) | plain English |
| census | `mg/check` (the generative check of every contract) | Malli |
| pillar | principle | plain English |
| fence | guard | plain English |
| merge train | octopus merge (branches merged into main in one commit) | git |
| harvest | stage (onto the branch) | git |
| stand-in | proc | core.async Flow |
| dial | config key | Clojure map, Datahike config |
| admit, guarantees | instrument | Malli `malli.instrument` |
| refusal | invalid, explained (a contract's); a reason (the done rule's); refused (a merge's) | Malli `m/explain` |
| attempt | run (one agent's try at a task); a clojure.test run is a "test run", a whole root's is "root's run" | owner, 2026-10-23: "I don't like the attempt name. Call it agentic loop or run" |
| story, run-state, task story | the task's render (a render is a function and the data it renders): task-page over the task's data; the layout is render-ctx | owner, 2026-10-26: "A render is just a function and the data being rendered. No story" |

Library words stay: proc, step-fn, transact, branch, merge, schema, registry, contract (a Malli function schema), flusher, group commit, fork server, lease, generation. **Waste** stays our word for the turns and tokens a run spent past what its work needed (`:seon.run/waste`; owner, 2026-10-23). A stored keyword is renamed by erasing the store, never by a migration (DECISIONS).

## 8. The machine

The machine Seon is built on and for, and the rules that keep it from being flooded.

- **The target is a 16 GB MacBook M1** (owner, 2026-10-22). Seon stays within `:seon.config.machine/memory-mb`, and `bin/check scale` fails past it.
- **Builds:** `nice -n 15 bin/build`, in parallel, no lock. Full checks run niced, each in its own worktree. Every process on a store uses the same LMDB build. Details, and the disk rules (worktrees removed at merge, no new top-level directories, pruning): [machine](docs/machine.md).
- **Never flood it.** Anything that makes threads or processes starts at n ≤ 100 under `nice -n 15`; kill your own process past 200 threads. One experiment at a time.
- **Leave nothing running when you finish,** and say so. A process that must outlive a tool call is double-forked (`bin/root` does).
- **Clean up garbage when you see it, whoever left it** (owner, 2026-10-23: "why not kill the leftover processes? why wouldn't you"; "cleanup garbage when you see it"). A leftover process (an orphan whose session is gone, an old binary's relay, a lock helper in a dead scratch folder), a merged worktree, a finished experiment's store or output: end or delete it now, and say what went. Garbage is what nothing live uses; check that first, by PID, path and working directory (`lsof -a -p PID -d cwd`), never by pattern (`pkill`, `killall`, `pgrep -f`). Keep what is evidence or someone's work in progress: the standing store (`runs/store`), anything git or the store records, a worktree with unmerged commits or uncommitted changes, a live session's checkout (check every session's `cwd` with `list_sessions` before removing a worktree: a session whose folder is gone can no longer be reached). Reclaiming a record (a store, a branch, history) stays the owner's act ([machine](docs/machine.md#disk-stays-bounded)).
- **A root's processes end with it:** kill the root, or its fork server's process group (`kill -9 -PGID`), never a fork server by pid. Killing the fork server first skips the group kill that ends its agents ([failure points](docs/failure-points.md), root dies).
- **Git in a worktree:** `git -C` on your own checkout only; never `git submodule` in a worktree (after merging main, `bin/worktree --sync NAME` from the main tree); never bare `git stash`; never `checkout`, `restore` or `reset` on paths you don't own; commit explicit paths; never push this repository's branches. A fork commit your branch pins is pushed to its fork, ``seantempesta/<lib>``, on a ``jolt/<topic>`` branch, with no ask (owner, 2026-10-23: "You don't have to ask me to push public repos anymore"): no force, never a fork's main, verified with `gh api` after.
- **Secrets are never printed.** Paid model runs spend from the owner's standing $50 budget, on productive work only, with the context checked first; past it they need the owner's go ([model](docs/model.md#paid-runs)). Install nothing system-wide. Never write in `~/src/seon`.

## 9. Mistakes we have made, and do not repeat

The mistakes made so far, each stated so it is not made again.

- **Root doing the work itself** (owner, 2026-10-22: "you spent most of the session just debugging rather than outsourcing with agents"): debugging, measuring or reading many files personally, step by step, while the plan stood still. Delegate it with a brief (§2).
- **Trees before the forest:** a detail chased past its worth with no step of THE PLAN asking for it; guessing at a cause instead of launching agents to find it.
- **Serial work:** one agent at a time, or waiting on one result with independent work ready.
- **A clock, timer, sleep, poll, deadline, ping or fixed slot** standing in for a dependency or a workload. "Never came" is a recorded fact (blocked, stalled, dead).
- **A swallowed error, an ignored answer, a fake default.** Unknown is not `{}`, `0`, `nil` or `:ok`, and a proc answers every request it took.
- **Routing around a principle:**
  - a fallback instead of an error fact;
  - a new runner or wrapper beside the reconciler;
  - a store per test;
  - an env var instead of config;
  - regex over code instead of the compiler's facts;
  - script order instead of `:needs`;
  - a thread instead of a fiber;
  - stdin as a lifecycle.
- **Building a mechanism twice,** or two producers of one view.
- **Reinventing what v1 already solved,** or designing before researching.
- **A half-baked plan that got built,** or too many worktrees at once on overlapping work.
- **A clipping function** (`clip`, `subs`, `take n` for output). Output improves only through the one printer, or a render declared on the value's schema. Every cut says what it left and how to read the rest.
- **A check that cannot fail,** or that only prints. Every check mode ends on `X OK` / `X FAILED` and an exit status. When a rule changes, read every step it touches.
- **Merging on a check nobody read,** or copying whole files over newer changes.
- **Re-running everything on every change,** a full run while iterating, or a second run of an unchanged tree (§3, Testing).
- **Deleting a record** (a store, a branch, history). Runs are kept, and reclaiming space is the owner's act. The one exception: until Seon holds a user's data, the standing store is test data, erased (never migrated) when its schema changes, on the owner's go ([machine](docs/machine.md#disk-stays-bounded)).
- **Throwing away good work, or keeping its slow copy** (owner, 2026-10-23: "don't throw away good work have agents learn from it if you want a clean implementation. No crappy slow repetitive code"). Before rewriting, read what an earlier branch, worktree or note did and keep what it learned (its measurements, its proofs, its cause); then write the clean implementation once, with no repeated or slow code. A worktree is removed as soon as its branch is merged and nothing in it is unsaved: commits merged, scratch papers filed (to the wiki or `claude_scratch/archive/`), and a store or run output recorded or handed to the owner.
- **Flooding the machine,** killing by pattern, leaving things running, or walking past garbage (a leftover process, a merged worktree) because someone else left it.

## 10. Done

It runs end to end through the path agents use; the merged result is green; its numbers are in the commit; its wiki section is true; you can watch it work; it moves a step of THE PLAN; and nothing of yours is still running.
