# AGENTS.md: how we build Seon

Seon is where many agents do real work, each in its own process on its own branch, and the good changes accrete into one program that gets smaller, faster and more certain as it grows. This file is how we work on it. It is short on purpose. Decisions with their reasons live in `docs/DECISIONS.md` (`../notes/DECISIONS.md` until it moves here). Failure points live in `docs/DRAGONS.md`, and words in `docs/vocabulary.md`.

## The spirit (why the first version felt like magic)

The first version ran overnight: a dozen lanes in parallel, each owning one directory, each told to go past experiments and get something working. By morning there was a guided tour of a running system:
- a thousand agents forked in a few seconds;
- an idle agent at 1.4 MB;
- a dashboard showing the whole system live;
- every number on screen measured, not claimed.

Keep that. Concretely:

1. **Make it run, then make it right, then make it fast, and measure each step.** A working end-to-end slice beats a perfect design document. Build the smallest real thing, run it, look at it, then improve it.
2. **The demo is the spec.** Every capability is proven by something you can watch: a tour step, a dashboard tile, a REPL form with its reply. If you can't show it running, it isn't done.
3. **Insight is a feature.** The system shows what it's doing: health, throughput, memory, every eval, every merge, every error, live. A user should understand the system by looking at it. Nothing important is hidden in a log.
4. **Numbers, always, and honest ones.**
   - Estimate before you measure, and derive the estimate from what the work must do.
   - Measure at three or more sizes, under the bench lock, and report the machine load.
   - When the result surprises you, find out why; that is where the discoveries are.
   - Say plainly what was not measured.
5. **Own the whole stack.** When the cause is in Chez, Jolt, Datahike or konserve, fix it there with a proper fix on a `jolt/<topic>` branch. The forks are ours. Most of the big wins came from removing waste the old design hid:
   - code copied on every fork;
   - a thread per `go` block;
   - an O(N²) branch list;
   - a cache that slowed every miss;
   - a flush per eval.
6. **Parallel and owned.** Work is split into lanes that each own their files, report with commits and numbers, and never touch another lane's tree. Root integrates. A lane that finds its plan's premise wrong stops and sends it back with the evidence.
   - **A lane that changes shared foundations works in its own worktree** (owner, 2026-10-21): `bin/worktree NAME` makes `../next-NAME` on branch `jolt/NAME`, with each submodule a worktree at its pinned commit and `target/` an APFS clone, so it builds (`bin/build seon`) and tests at once and its half-done work never breaks another lane. On Jolt this is safe: each process loads only its own `JOLT_PWD` and `target/`.
   - It lands only green work: `bin/check` green in the worktree, then commits cherry-picked onto main by explicit paths. It never edits the main tree; builds still take the one build lock; `bin/worktree --remove NAME` cleans up (the branch is kept).
7. **Small.** About 10k lines of our own code. When something grows, ask what else should be carrying it. Every addition names what it replaces.

## Get out of the way (owner, 2026-10-20)

An agent has a real REPL into a real session. Clojure's own semantics are the interface: `ns` creates a namespace, `defn` defines a function, `ns-unmap` removes one. We teach exactly that, live by it, and record what happened. We never decide what an agent is "allowed" to do.

The instinct to add a gate is wrong here. Examples: "only record defs in namespaces the db already knows", "is this a known kind of form?", "refuse unless it matches the list". That instinct produced a check that silently threw away every new namespace an agent created.

When you feel it, stop and ask three questions:
- **What would a plain Clojure REPL do?** Do that.
- **Is this recording what happened, or judging it?** Record it. The proofs and the merge judge it, not a filter in the middle.
- **What breaks if the check isn't there?** If the answer is "nothing we've observed", don't add it. If something real breaks, fix that thing at its cause.

## The pillars: everything is data flowing through the system (owner, 2026-10-21)

**If nothing has changed, we do nothing** (owner, 2026-10-21). No proc steps, renders, pings, flushes or polls on an idle system; work exists only because data changed. **The system is reactive:** a proc reports its own problems as facts when they happen and its changes as events; nothing pings to ask whether it is healthy. Health is the absence of open problem facts.

This is not a traditional program with a scheduler, timers and callers that wait. Data flows: a fact is written, a commit flows to whoever reads it, a message lands on a queue, a proc runs because work arrived and parks when there is none. Every need is met by one of these pillars. If a need seems not to fit one, stop and say so; never route around them.

- **A value.** State is immutable Datahike values with structural sharing, in one store with many branches. Each process owns its connection: agents own their branch, root owns main. A record is a fact; history is kept.
- **A Flow proc and its channels.** Every component is a step-fn proc, declared as data (`resources/seon/system.edn`), with bounded channels. A dependency is a channel a proc parks on; back-pressure is a full channel; ordering is dataflow; the end of a run is the message count reaching zero. Procs run on fibers; a blocking foreign call gets a declared thread.
- **A task.** All work is task entities: desired state on attributes, observed state as facts, `:needs` between them. One reconciler closes the gap and costs only what changed. Merges, checks and builds are tasks; an OS child is an effect owned by a proc, and its exit is a fact.
- **A process.** Agents are forked OS processes, isolated, each on its own branch, speaking messages to its stand-in proc.
- **A render.** Every view, the model's context included, is a render declared on the value's schema, over the one printer.
- **An error is data** that flows to whoever can fix it: a defect holds its message until the code is fixed; a typed error goes to the requester, agent or task that can act on it.

**A mechanism is built once, as a pillar, and reused everywhere** (owner, 2026-10-21: "simple and extremely well built code that we reuse everywhere because it's a pillar and not just a hack"). Before building anything that waits, retries, admits, holds, spawns, watches a process, answers a request or reads config, find its one implementation (`docs/patterns.md`, or ask root who owns it) and use it. If it doesn't exist yet, its owner builds it; you don't build a second one. Before building a pillar: read the research (`../notes/option3-RESEARCH-async.md`, `docs/core-async.md`, `docs/flow.md`) and prove the approach in a REPL at n ≤ 100.

**Routing around a pillar** (each of these happened on 2026-10-21): a timer, sleep, poll, deadline or retry instead of a queue; a fallback default instead of an error fact; a new runner, manager or wrapper beside the reconciler and procs; a store per test instead of a branch; an env var instead of a config fact; text or regex instead of the compiler's facts; a script's line order instead of `:needs`; an OS thread instead of a fiber; stdin as a process's lifecycle instead of a fact or message.

## Laws (short; the reasons are in DECISIONS)

- **Only documented config** (owner, 2026-10-21). A limit, bound, dial or default is a key in the config file, with its unit, provenance and what happens at the bound, and a `seon.config.*` schema. No literals in code, no `System/getenv` outside the config loader.
- **One store, many branches, as in production** (owner, 2026-10-21). Tests, checks and runs take a branch of one seeded store. A separate store is a last resort (a backup, a deep debug) and says why where it is made.
- **Contracts on every function.** Malli contracts are armed from the definition, and every contract must generate (`mg/check` runs in `bin/check`).
- **No guards or fences.** Do the right thing. Name every outside-world failure point in `DRAGONS.md`, with its one edge function and its declared error. Nothing else catches.
- **No added caches.** A content-keyed cache is allowed only for a unit measured as expensive.
- **Effects go through owner procs** (`my.fx/call`, `my.fx/send`). The asker records the outcome on its own branch.
- **Durability is continuous group commit** (owner, 2026-10-21): the flusher takes everything unflushed and syncs once, then again; commits arriving during a sync share the next one. A write is answered when committed in memory; one followed by an effect outside the database waits for the sync that covers it (`docs/patterns.md#continuous-group-commit`).
- **Every definition happens once, where it is defined (owner, 2026-10-21).** Behaviour varies through the environment (a forked process holding exactly the definitions it needs, e.g. a test's own stubs as real definitions) or through data (arguments, config facts), never by mutating a shared var. Program code does not call `intern` or `alter-var-root` (judged at the done rule from the compiler's resolved references, never by text); the platform arms contracts at definition, not after.
- **Estimates are proofs.** A function whose input grows carries a cost proof: an overrun, or growth steeper than the work needs, is red like any failing test. Every contracted call is counted (`seon.profile`), and every eval records its costliest functions.
- **Tests run by reach digest:** only tests reaching a change run, and results are shared by digest. Only good tests: real assertions, sensitive to mutation, not timing-only.
- **Use the library's words and mechanisms:** Clojure, core.async.flow, Datahike, Malli, git, nREPL.
- **Procs follow the step-fn contract** (F2's rules, `docs/DECISIONS.md`):
  - the step-fn is a Var with the four arities, and `describe` declares `:params`, `:ins`, `:outs`, an honest `:workload` and a `:ping-map-fn`;
  - `transform` is `(state, in, msg) -> [state' outputs]`, outputs are data, and an effect is an output to the `:io` proc that owns it;
  - a step never blocks on another proc: request/reply is a message out, a pending entry keyed by request id, and a message back;
  - state is plain data under its schema, and a live host object lives only in its owning proc (opened in `init`, released at `::flow/stop`);
  - channel buffers are bounded by their loss semantics;
  - no thread, future, go-loop or atom outside procs, except an edge reader thread for a blocking read that feeds a proc's in-port;
  - the fork server and agents run the same contract in their own single-threaded loop.

## How work is handed out (we dogfood Seon)

All work, ours and Seon's agents', is a **task** in a task file: `resources/seon/tasks/<owning-ns>.edn`, loaded with `my.root/load-tasks!`. Each entry has:
- `:seon.task/plan`: the research, with file:line, what exists, what it replaces, what gets deleted;
- `:seon.task/proofs`: the deftests, cost proofs included, that say it is done;
- `:seon.task/needs`: the tasks that must land first.

The order of the tasks is the order of the work: **delete first, then foundations, then fixes**, so nothing is fixed and then deleted. Root writes and orders the tasks (from audits, mined problems and the owner's words), hands each to one lane, and lands its work. A lane may refine its task's plan as it learns, never its proofs' intent without root.

## How to work a task (every lane, every agent)

0. **Work it as a Seon agent would** (owner, 2026-10-21: "build the task and load it and use the context system to make sure every step is the same; fix problems at each stage"):
   - write your task's entry in its task file and load it into the standing root (`(my.root/load-tasks! sys "<dir>")` through `seon_eval` as root);
   - `seon_attach` to it, read `seon_context` before each step, and evaluate through the agent (`seon_eval`);
   - finish with `seon_done`, so the judge does exactly what it does for a Seon agent.

   The system records all of it: the task, the attempt, every eval (origin `:outside`), the context you read, and the judgment. That record is replayable like any agent's. Where the context lacked what you needed, the output was noise, a tool was missing or the judge was wrong, that stage's problem is a task. Platform code (`seon.*`) moves into this flow as the self-improvement loop lands; until then do its code in your worktree and still work its task through the door.
1. **Take one task.** For foundation work, open a worktree: `bin/worktree <task-name>`. Work only there and only on that task's files. Never edit the main tree, another lane's files, or anything outside the task. Never run `git checkout`, `restore`, `stash` or `reset` on paths you don't own.
2. **Learn, plan, act, in that order** (owner, 2026-10-21). First research the existing code end to end, before writing anything; then write the plan; then build.
   - Read the task's plan, DECISIONS, `docs/DRAGONS.md`, and the code it touches with its callers and its tests.
   - Read the dependency (Jolt, Datahike, Flow, malli) before working around it.
   - Ask: is this code already scheduled for deletion or replacement (`../notes/PLAN-v2.md`, other tasks' plans)? If so, stop and tell root.
   - Write what you found into the task's plan: the cause, with file:line evidence.
   - If the premise is wrong, stop and send the evidence to root.
   - **Plan:** a few lines, before code: the primitive you'll use (the one the platform or dependency already has: a flow proc, a channel, a branch, a fact, the alt-handler waiter), what changes, what it deletes, and the test that proves it. No new layers, wrappers, runners or managers: if the primitive doesn't fit, say so in two lines. For a change to a dependency (Jolt, Chez, Datahike, Flow) or a design rule, send root the plan and wait for its go. Work the dependency's own way, never with a hack around it.
3. **Estimate before measuring.**
   - Derive what the work must cost: what N counts, how many passes, the unit costs from `seon.cost/units`.
   - Write the estimate as a cost proof (`seon.cost/proof`) for anything whose input grows.
4. **Write the proofs first and watch them fail.**
   - The task's named deftests, a `bin/check` mode where the behaviour is end to end, and the cost proof.
   - A proof must fail for the right reason before the code exists: real assertions, sensitive to mutation, never timing-only, never `string?`/`map?` placebos, never gated to zero assertions.
5. **Write the simplest code that makes them pass,** at the cause, in the owner of the bad value.
6. **Be suspicious of every number.**
   - Compare each measurement with its estimate.
   - A ratio past 3× either way is a finding: explain it before going on.
   - Say plainly what was not measured.
7. **If you touched what the owner sees (the dashboard, a render, the context), have it verified by eye.** Run your worktree's own root (`bin/root start`, on its own ports from `bin/ports`), then launch a Haiku or Sonnet subagent with the built-in browser. Its job: compare the page and the context against the system's facts read through the door. Is every number real? Does anything mislead? Does it show what is going on? It reports what is wrong or useless, with screenshots. Fix what it finds, or file it.
8. **Prove it whole:** `bin/test`, then `bin/check` in your worktree. `bin/check` fails when any step fails, keeps each step's output and the stores under `target/check/<time>/`, and is the bar.
9. **Report to root:** commits, proofs (red → green), numbers against estimates, what you deleted, what's left. Root cherry-picks onto main by explicit paths, runs `bin/check` there, and closes the task.

## Look at the running system, as a Seon agent sees it

Each checkout runs its own standing root: `bin/root start` (its store kept under `runs/`, on the checkout's ports from `bin/ports`; the owner's main tree is 7888 with the dashboard on 7777). Reach it through the **`seon` MCP server** (`bin/mcp`, which defaults to your checkout's entrance). Its tools:
- `seon_agents`: agents, and tasks with their blocked-by;
- `seon_eval`: evaluate in an agent's own REPL, exactly the path a Seon agent's forms take, recorded `:outside`; or as `root`;
- `seon_context`: what the agent's model would read now;
- `seon_attach` / `seon_done`: work a task as a Seon agent.

Without MCP, the same door is one line: `printf '{:op :eval :agent "root" :code "(my.root/problems sys)"}\n' | nc -w 10 127.0.0.1 7888`. Use it to see what agents see: when output is noise, a tool is missing or an error names nothing, that is a task (below). Load task files into it with `(my.root/load-tasks! sys "<next>/resources/seon/tasks")`.

## Found a problem? File a task (owner, 2026-10-21)

When you hit something outside your task, don't fix it in passing and don't just mention it. Examples: REPL output that is noise or cut wrong, a context section that misleads, a missing tool, an error that names nothing, a slow path, anything a Seon agent would trip on. Write it up as a task in the owning namespace's task file, filled out so a Seon agent can work it with nothing else:
- **`:seon.task/plan`:**
  - what you saw, exactly: the form, its output, the turn or store it came from;
  - the cause as far as you traced it (file:line);
  - what correct looks like;
  - what it would replace or delete.
- **`:seon.task/proofs`:** the deftests (and cost proof) that will say it is fixed, by name, with what each asserts written in the plan.
- **`:seon.task/needs`:** what must land first.

Tell root; root orders it among the rest. Mined problems (`my.root/problems`) become tasks the same way.

## The docs are a wiki, and always true (owner, 2026-10-21)

- `docs/<topic>.md`, one concept per page, indexed in `docs/README.md`. A section is addressed `topic#heading-slug` and ends with a `Describes:` line naming its vars, attrs and files as EDN.
- Reference docs as data: `{:seon/docs ["merge#behind"]}` on a definition, `:seon.task/docs` on a task. The context pulls the named sections in, so explain a thing once in the wiki and point at it.
- **Every lane keeps the wiki true for what it builds** (owner, 2026-10-21). Landing a change includes its wiki section (new or updated, with a `Describes:` line naming the real vars), and a pillar's owner writes its `docs/patterns.md` section.
- **Every lane checks its own context.** Before landing, read your task's context through the door (`seon_context`, or `(seon.context/…)` as root) and confirm it pulls the sections and code the task needs and nothing that misleads; what's missing or wrong is a task for the context or the page.
- A change that makes a section wrong updates it in the same commit. `bin/check docs` goes red on a dangling reference, a named var or attr that doesn't exist, or a section older than the code it describes. (Tasks: `resources/seon/tasks/seon.docs.edn`.)

## Mistakes we have made, and do not repeat

- **A clock or timer deciding anything** (owner, 2026-10-21: "no clocks or timers … running and pausing based on workload"). Procs run on queued work and park without it; batches follow the queue; runs end on the message count reaching zero; runaway work is bounded by work done (allocation, steps), never elapsed time. Measure time, never act on it. The one exception is the cron proc: at a scheduled time it puts a message on a queue, and nothing else.
- **A timeout, deadline, sleep or retry standing in for a dependency.** Flows depend on each other through channels that park. "Never came" is an observed, recorded fact (stuck, stalled, dead), never a clock that ends a run.
- **A gate by the shape of a form or a list of kinds.** Record what actually happened: the compiler's events, the runtime's state. The proofs and the merge judge.
- **A swallowed error, an ignored answer, a fake default.**
  - Every `:seon.error/type` answer is read or recorded.
  - Unknown is not `{}`, `0`, `nil` or `:ok`.
  - A proc answers every request it took, even when its step throws.
- **Deleting a record:** a store, a branch, a log, history. Runs are kept. Reclaiming space is an explicit owner act.
- **A cut that drops instead of eliding.** Every cut says what it omitted and how to read the rest. Storage keeps the exact value; renders budget it.
- **A clipping function** (owner, 2026-10-21): no `clip`, `subs`, `take n` or hand-made truncation anywhere output is made for a person or a model. Output gets better in exactly two ways:
  - a general improvement to the one printer (`seon.print`), which every value benefits from;
  - a render function declared on the value's schema (`:seon.render/ai` for the model's context, `:seon.render/html` for the page), written when we know that output needs focus.

  Refinement of output is ongoing work: when you see poor output, file it or fix it this way.
- **The same check in several places,** or a second producer of the same view. One owner per fact, one function per rule.
- **Redefining instead of defining.** No `alter-var-root`, `intern`, `with-redefs` or stub-by-rebinding. Tests get a forked environment holding exactly their definitions.
- **Text standing in for structure.** No regex over code. Ask the compiler, the reader or the db.
- **A measurement that disturbs itself:** a meter that counts its own reads, a watchdog counted as work. And a number accepted without an estimate.
- **A check that cannot fail.** A pipeline that drops exit codes, an assertion on a value's type, a test gated off. If it cannot go red, it proves nothing.
  - A check that only prints is the same mistake: smoke, scale, fx and orphan printed `:pass 0` for a day and stayed "green" (2026-10-21). Every mode ends on its own verdict (`X OK` / `X FAILED`) and exit status.
  - When you change a rule (what judges, what merges, what is program), run the whole `bin/check` and read every step that touches it, not just the ones that go red.
- **Landing on a check nobody read.** Root cherry-picked a lane's commits, started `bin/check`, and moved on: main went red in three steps and silently empty in four (2026-10-21). Root lands, then reads the check to its last line before launching or landing anything else; a lane reports its `target/check/<time>/` path with the result.
- **A cache keyed by time,** or any cache not measured as necessary.
- **Shared-tree accidents:** one lane's checkout discarding another's work. Own your files, use your worktree, commit early.

## The machine

- **Builds:** run as `nice -n 15 /usr/bin/lockf -k ~/src/seon-jolt/build.lock bin/build`, which pins the patched Chez via `JOLT_CHEZ`.
- **Probes and timings:** probes run under `nice -n 10`. No timed runs while load is above ~20.
- **A process that must outlive your tool call** (a standing root, a server) must be re-parented to PID 1: a double fork. **Start your root with `bin/root`** (start | status | stop): detached, on your checkout's own ports (`bin/ports`), log in runs/root.log; `bin/mcp` reaches it. The tool host kills a background task's whole process tree when the task is stopped or its agent exits; nohup or setsid alone don't help. (ISOLATION, 2026-10-21.)
- **Ports are config, never literals:** don't bind 7777 or 7888 (the owner's root and dashboard). A check's root listens on nothing; your worktree's own ports come from its config/local.edn.
- **Your own scratch:** temp files and logs go under your worktree (`target/` or `runs/`) or a subfolder named after your lane; the session scratch folder is shared by every lane, and one lane overwrote another's log there (2026-10-21).
- **One full check at a time:** `bin/check` takes `~/src/seon-jolt/check.lock` and runs niced. Iterate with single modes.
- **Never flood the machine** (2026-10-21: two experiments made 7,500 and 5,900 threads and drove load to 982). Anything that can make threads or processes starts at n ≤ 100, under `nice -n 15`; watch `ps -M -p PID | wc -l` while it runs and kill your own process past 200 threads. One experiment process at a time.
- **Hygiene:** kill only your own PIDs, delete only paths you created, install nothing system-wide.
- **Secrets:** never printed. DeepSeek is the default model; check the context before any paid call.

## The bar for "done"

- It runs end to end, through the same path agents use.
- `bin/check` is green.
- Its numbers are in the commit.
- You can watch it work.
