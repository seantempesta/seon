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

## The model (everything is one of these)

- **A process.** Agents are forked OS processes: half a millisecond, a megabyte or two, isolated, and memory returned on exit. Root holds threads; anything that forks stays single-threaded.
- **A Flow proc.** Every component is a step-fn proc with an id, the same verbs (send, ping, pause, resume, stop, restart via `update!`) and bounded channels. Procs are declared in `resources/seon/system.edn` and started explicitly. Ordering is dataflow.
- **A task.** All work is task entities with `:needs`, a plan and proofs that accumulate. Desired state lives on attributes, and observed state is facts. One React-like reconciler closes the gap, and it costs only what changed. Merge is a task.
- **A value.** State is immutable Datahike values with structural sharing. Each process owns its connection: agents own their branch, and root owns main. Cost follows what changed: tx reports, digests, shared nodes.
- **A render.** Every view, the model's context included, is a render declared by a schema. Renders are incremental, and HTML patches go out through Datastar. UI actions are evals.

## Laws (short; the reasons are in DECISIONS)

- **Contracts on every function.** Malli contracts are armed from the definition, and every contract must generate (`mg/check` runs in `bin/check`).
- **No guards or fences.** Do the right thing. Name every outside-world failure point in `DRAGONS.md`, with its one edge function and its declared error. Nothing else catches.
- **No added caches.** A content-keyed cache is allowed only for a unit measured as expensive.
- **Effects go through owner procs** (`my.fx/call`, `my.fx/send`). The asker records the outcome on its own branch.
- **Durability is batched,** with a flush window of 10 ms by default. A write that is followed by an effect outside the database waits for its flush.
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
2. **Research the existing code first, end to end, before writing anything.**
   - Read the task's plan, DECISIONS, `docs/DRAGONS.md`, and the code it touches with its callers and its tests.
   - Read the dependency (Jolt, Datahike, Flow, malli) before working around it.
   - Ask: is this code already scheduled for deletion or replacement (`../notes/PLAN-v2.md`, other tasks' plans)? If so, stop and tell root.
   - Write what you found into the task's plan: the cause, with file:line evidence.
   - If the premise is wrong, stop and send the evidence to root.
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
7. **Prove it whole:** `bin/test`, then `bin/check` in your worktree. `bin/check` fails when any step fails, keeps each step's output and the stores under `target/check/<time>/`, and is the bar.
8. **Report to root:** commits, proofs (red → green), numbers against estimates, what you deleted, what's left. Root cherry-picks onto main by explicit paths, runs `bin/check` there, and closes the task.

## Look at the running system, as a Seon agent sees it

A standing root runs at `bin/root 7888` (its store kept under `runs/`; the dashboard on 7777). Reach it through the **`seon` MCP server** (`bin/mcp 7888`, registered in `.mcp.json`). Its tools:
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

## Mistakes we have made, and do not repeat

- **A timeout, deadline, sleep or retry standing in for a dependency.** Flows depend on each other through channels that park. "Never came" is an observed, recorded fact (stuck, stalled, dead), never a clock that ends a run.
- **A gate by the shape of a form or a list of kinds.** Record what actually happened: the compiler's events, the runtime's state. The proofs and the merge judge.
- **A swallowed error, an ignored answer, a fake default.**
  - Every `:seon.error/type` answer is read or recorded.
  - Unknown is not `{}`, `0`, `nil` or `:ok`.
  - A proc answers every request it took, even when its step throws.
- **Deleting a record:** a store, a branch, a log, history. Runs are kept. Reclaiming space is an explicit owner act.
- **A cut that drops instead of eliding.** Every cut says what it omitted and how to read the rest. Storage keeps the exact value; renders budget it.
- **The same check in several places,** or a second producer of the same view. One owner per fact, one function per rule.
- **Redefining instead of defining.** No `alter-var-root`, `intern`, `with-redefs` or stub-by-rebinding. Tests get a forked environment holding exactly their definitions.
- **Text standing in for structure.** No regex over code. Ask the compiler, the reader or the db.
- **A measurement that disturbs itself:** a meter that counts its own reads, a watchdog counted as work. And a number accepted without an estimate.
- **A check that cannot fail.** A pipeline that drops exit codes, an assertion on a value's type, a test gated off. If it cannot go red, it proves nothing.
- **A cache keyed by time,** or any cache not measured as necessary.
- **Shared-tree accidents:** one lane's checkout discarding another's work. Own your files, use your worktree, commit early.

## The machine

- **Builds:** run as `nice -n 15 /usr/bin/lockf -k ~/src/seon-jolt/build.lock bin/build`, which pins the patched Chez via `JOLT_CHEZ`.
- **Probes and timings:** probes run under `nice -n 10`. No timed runs while load is above ~20.
- **Hygiene:** kill only your own PIDs, delete only paths you created, install nothing system-wide.
- **Secrets:** never printed. DeepSeek is the default model; check the context before any paid call.

## The bar for "done"

- It runs end to end, through the same path agents use.
- `bin/check` is green.
- Its numbers are in the commit.
- You can watch it work.
