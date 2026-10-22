# AGENTS.md: how we build Seon

Seon is where many agents do real work, each in its own process on its own branch, and the good changes accrete into one program that gets smaller, faster and more certain as it grows. This is the one document on how we work, for every Claude lane and every Seon agent. **One process for both** (owner, 2026-10-21: "a self reinforcing system where agents learn from each other be it claude agents or seon agents … everything linked to the same processes we will be using internally, not complex one offs"): the same tasks, the same door, the same context built by the same parser from the same wiki, and lessons landing in the same sections. Nothing is a Claude-only format or a one-off mechanism.

## 0. Start here

You are in `next/`, **Seon on Jolt: the system being built to replace Seon** (`~/src/seon`, the JVM version, now read-only reference). The aim is a straight upgrade: everything v1 does, next does, smaller, faster and more certain; a v1 capability left out is left out on purpose, with its reason in `BAGGAGE.md`.

Read, in order, before acting:
1. this file (the laws and the method; `CLAUDE.md` is this file);
2. `../RESUME.md`: the current state and THE PLAN (what is being fixed now, in order);
3. `docs/README.md`: the wiki's map; `docs/parity.md`: each v1 capability and where it stands here;
4. the task you are given (`resources/seon/tasks/<ns>.edn`), and what its plan cites.

The running system: `bin/root status` shows this checkout's standing root (the owner's main tree: entrance 7888, dashboard http://127.0.0.1:7777/); `bin/root start` starts it. Claude reaches it through the `seon` MCP server (`.mcp.json` runs `bin/mcp 7888`; §5). The standing root's store (`runs/store`) holds kept runs, which are evidence: never experiment on it. Experiments run in a worktree (`bin/worktree NAME`) on that worktree's own root and stores.

The reasons live elsewhere:
- decisions: `../notes/DECISIONS.md`;
- the plan: `../RESUME.md` (THE PLAN); designs in progress: `../notes/DESIGN-tasks.md`;
- what v1 and next's history teach: `../notes/LEARNING-2026-10-22.md`;
- the method's research: `../notes/option3-RESEARCH-parallel.md`;
- the wiki: `docs/`;
- failure points: `docs/DRAGONS.md`.

## 1. What we are building

**The goal: `next/` becomes the replacement for Seon, and stays tiny and performant** (owner, 2026-10-21). Its code, its docs and wiki, its door (MCP) and everything else improve toward that one system. At its heart is the recursive self-improvement loop: agents work tasks, merges land, and the merged code is written to disk and runs next. One system, never separate agents fighting over the machine.

The first version ran overnight and was a live, measured system by morning. Keep its spirit:
- **Run, then right, then fast; measure each step.** A working slice beats a design document.
- **The demo is the spec.** If you can't show it running (a tile, a REPL reply, a check), it isn't done.
- **Insight is a feature.** The system shows what it's doing, live. Nothing important hides in a log.
- **Honest numbers.**
  - Estimate first, from what the work must do.
  - Measure at three or more sizes, with the load noted.
  - A ratio past 3× either way is a finding.
  - Say what wasn't measured.
- **Own the stack.** A cause in Chez, Jolt, Datahike or konserve is fixed there, on a `jolt/<topic>` branch, the dependency's own way.
- **Small.** About 10k lines of our own code. Every addition names what it replaces.

## 2. The pillars: everything is data flowing through the system

**If nothing has changed, we do nothing.**
- No proc steps, renders, pings, flushes or polls on an idle system.
- **Reactive:** a proc reports its own problems as facts and its changes as events. Health is the absence of open problem facts.
- **No clock or timer decides anything.** Work runs because it arrived, is admitted by its measured workload, and parks when there is none. Time is measured and recorded, never acted on.
- **The exceptions:**
  - the cron proc, which only puts a message on a queue at a scheduled time;
  - a message's delayed insert (core.async `timeout`) when a provider says "retry after a short wait";
  - the kernel's TCP keepalive for a silent network peer.

Every need is met by one of these. If one doesn't fit, stop and say so; never route around them:
- **A value.** Immutable Datahike values in one store with many branches. Agents own their branch; root owns main. A record is a fact, and history is kept (v1's ruling; the store runs with `:keep-history? false` today, `seon.db/store-cfg`, and THE PLAN restores it).
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
  - ended when their attempt ends.
- **A render.** Every view, the model's context included, is a render declared on the value's schema, over the one printer.
- **An error is data**, flowing to whoever can fix it. A defect holds its message (per key) until the code is fixed. A typed error goes to the requester, agent or task that can act on it.

**A mechanism is built once, as a pillar, and reused everywhere.**
- Before building anything that waits, retries, admits, holds, spawns, watches a process, answers a request or reads config, find its one implementation: the wiki section where that mechanism lives states the pattern and names its one implementation and owner.
- If it doesn't exist, its owner builds it; nobody builds a second one.

**Get out of an agent's way.**
- An agent has a real REPL, and Clojure's own semantics are the interface. Record what happened; never gate what an agent may do.
- When you feel like adding a check, ask three questions:
  - What would a plain REPL do?
  - Am I recording or judging?
  - What observed thing breaks without it?

## 3. Laws

- **Only documented config.** Every limit, bound or default is a key in `resources/seon/config/default.edn`, with its unit, provenance and what happens at the bound, under a `seon.config.*` schema.
  - It's read through one resolver (task → model → provider → config).
  - No literals, no `System/getenv` outside the loader.
  - Ports too: each checkout has its own (`bin/ports`).
- **One store, many branches,** as in production. Tests, checks and runs take a branch of one seeded store. A separate store is a last resort, and says why where it is made.
- **Contracts on every function,** armed at definition, and every contract must generate (`mg/check` in `bin/check`).
- **No guards or fences.** Every outside-world failure point is named in `DRAGONS.md`, with its one edge function and declared error. Nothing else catches.
- **Effects go through their owner** (`my.fx/call`, `my.fx/send`). The asker records the outcome on its own branch.
- **Durability is continuous group commit.** A write is answered when committed in memory. One followed by an outside effect waits for the sync that covers it.
- **Every definition happens once, where it is defined.**
  - Behaviour varies through the environment (a forked process holding exactly its definitions) or through data.
  - `intern`, `alter-var-root` and `with-redefs` are code smells (owner, 2026-10-21: "intern isn't banned it's a code smell"). A legitimate use builds vars programmatically in a namespace you own, where no definition form exists (a REPL harness, a test environment), and its docstring says why. Filling a gap in another namespace (a library's, Jolt's stdlib) is fixed in that namespace's owner instead. Stub-by-rebinding is replaced by a test environment that holds the stub's definition.
- **Results are reused by input digest.** A test's result is keyed by the digest of everything it reaches, a check step's by its build's source, a build's by its sources. Unchanged inputs are never run again.
  - Content-keyed reuse is allowed for units measured as expensive; no other caches, and never one keyed by time.
- **Estimates are proofs.** A function whose input grows carries a cost proof. An overrun, or growth steeper than the work needs, is red.
- **Good tests only:** real assertions, sensitive to mutation, never timing-only, never a placebo a stub would pass.
- **Use the library's words and mechanisms:** Clojure, core.async and Flow (`docs/core-async.md`, `docs/flow.md`), Datahike, Malli, git, nREPL.
- **Procs follow the step-fn contract:**
  - a Var with four arities, whose `describe` declares ins, outs, an honest `:workload` and a ping-map;
  - `transform` returns `[state' outputs]`, with data outputs;
  - a step never blocks on another proc (request/answer with credit, `{:id :reply}`, exactly one answer);
  - state is plain data under its schema, and a host object lives only in its owning proc;
  - buffers are bounded by their loss semantics;
  - no thread, future or atom outside procs, except an edge reader for a blocking read.

## 4. How we work

The same method serves our lanes and Seon's own agents: small changes, integrated often, through one queue that tests the merged result, running only what the change reaches.

### Work is tasks
All work is a task in `resources/seon/tasks/<owning-ns>.edn`, loaded with `my.root/load-tasks!`:
- `:seon.task/plan`: what exists (file:line), what v1 did, the cause, what it replaces and deletes;
- `:seon.task/proofs`: the deftests and cost proofs that say it is done;
- `:seon.task/needs`: what must land first;
- references, derived, never hand-kept: the plan's own citations (links to wiki sections, backticked names, line citations), read by the one parser (v1's `seon.ref`) and stored on the task, as v1's `:seon.task/references` were. They pull those sections and that code into the task's context, and the names it changes are its write set.

**A lane's brief is its task** (owner, 2026-10-21: process, not whim). Root writes everything a lane needs into the task entry (the plan with its citations, the proofs, the needs) and into the wiki sections it cites; the brief only names the task, its worktree, and how to read its context. What a lane learns goes back into the task or the section it concerns, never only into a report, so the next agent's context carries it without anyone choosing to read it.

The order is **delete first, then foundations, then fixes**. Anything shared by several tasks (an interface, a schema, a pillar's API) lands first as its own small contract task.

### Every ready task runs now, in parallel
Owner, 2026-10-21 ("get rid of all of it"): no lane cap, no locks, no waiting.
- Root launches every ready task whose write set doesn't overlap a running lane's, at once; the only limit is file overlap. Live agents get real tasks at the same time, never held until prerequisites land: a failure is data, and its fix lands in parallel.
- A lane writes its own proofs first from the task's plan; root does not write them for it. Plan-before-code is only for design questions (a new mechanism, a format, a cross-owner change); a clear fix is just done.
- Root rebuilds main as part of every landing, so a worktree made from main never reads config with a stale binary.
- Each lane works in its own worktree (`bin/worktree NAME`: `../next-NAME` on `jolt/NAME`, submodules at their pins, `target/` cloned).
- Lanes waiting on a dependency read and plan; they run nothing heavy and keep nothing running.

### Learn, then plan, then act
1. **What exists, written into the plan.** Find the relevant wiki sections (`docs/README.md` is the map; the section where your problem lives states its pattern and links the others like it), the task files and the code, so nothing is built twice. Then:
   - cite them in the plan (a link to each section, the vars in backticks), so the parser makes them the task's references and every later step's context pulls exactly them;
   - state the differences in the plan: where a section and the code disagree, and what this task changes relative to what the sections say. Those differences are the wiki edits this task owes.
2. **What v1 did.** Read `~/src/seon` for the same problem: code, decisions, research, reverts, read-only. Port the best parts onto our pillars, and name the mistakes you are not repeating.
3. **The field's best known way.** Don't trust a design made in five minutes. Research it: the web, and Gemini Flash through `../bin/agy-agent` as a searcher. Verify what it says.
4. **Prove it in a REPL** at n ≤ 100.
5. **Plan in a few lines:**
   - the primitive you'll use;
   - your write set;
   - what it deletes;
   - the tests;
   - its wiki section.

   A change to a dependency (Jolt, Chez, Datahike, Flow) or a design rule waits for root's go. No new layers, runners or wrappers; if the primitive doesn't fit, say so in two lines.
6. **Estimate,** as a cost proof for anything whose input grows.
7. **Write the proofs first and watch them fail** for the right reason.
8. **Write the simplest code** that makes them pass, at the cause, in the owner of the bad value.
9. **Work it as a Seon agent would,** through the door (§5): load the task, attach, read the context before each step, evaluate through the agent, finish with `seon_done`. Where the context lacked something, a tool was missing or the judge was wrong, that is a task.
10. **If the owner sees it** (the dashboard, a render, the context), have a Haiku or Sonnet subagent with the browser compare the page against the facts read through the door, and fix what it finds.

### Integrate small and often
- **Branches live hours.** Merge main into yours after every landing.
- **While iterating, run only what your change reaches:** single check modes and the tests that touch it. Never a full check per edit.
- **To land:** merge main into your branch, then prove the merged result. Report the commits, proofs red → green, numbers against estimates, what you deleted, the check's `target/check/<time>/` path, and that nothing of yours is running.
- **Root lands** by merging the branch, never by copying files over newer changes, and reads main's check to its last line before landing or starting anything else.
  - Every stage's exit status decides the next: no pipe hides a failure (`setopt pipefail`, output to a log), and a check counts only if the build before it succeeded and the binary's time moved.
  - **A landing runs what the change reaches, not every check** (owner, 2026-10-21: "I want faster iteration not a matrix with every run permutation"). The landing proof is: the tests of the changed namespaces and of their callers (found with the compiler's facts, or grep until reach selection lands), the `bin/check` steps whose code changed, and `bin/check smoke`. The full `bin/check 100` and `bin/test` run once a day in the background, and before a change to boot, the writer, the reconciler or the build; a red there becomes a task, not a blocked landing. Target: tests selected by reach digest and results reused (v1's `seon.test.cache`), so an unchanged test never runs again.
  - **Root lands ready branches in batches** (owner, 2026-10-21: "this is taking forever"; three branches landed one at a time cost ~40 minutes of serial full checks, each ~10 min, plus re-proving after every main move). Branches that are ready and file-disjoint (or whose overlaps are both-sides additions) merge onto one integration branch from main and are proven together by one `bin/check 100` and one `bin/test`; then each lands. A lane stops at its own single-namespace tests and its red-to-green proofs, reports, and lets root run the full proof in the batch.
  - **Root reads every landing's `src` diff stat before merging** (owner, 2026-10-21: "If we are building shit we don't need to build and not using the existing systems or extending them for minimal code we are doing the wrong thing"; `src` grew 14.1k -> 15.9k lines in one day against the ~10k goal). Every function a landing adds names the existing system it extends, or why none could carry it; a new namespace or mechanism where an existing one could be extended is sent back, not landed; the landing's commit message states its net `src` lines, and a landing that grows `src` says what it deletes or plans to delete.
  - A conflict is resolved by copying each side's lines, never retyping; the lines after `>>>>>>>` may belong to both sides; each resolved file's forms must balance as its parents' do.
- **Root's gate, before approving any plan, format, key or rule** (owner, 2026-10-21, after root approved several inventions it should have caught). Root answers from evidence and shows the owner:
  1. Which existing mechanism does this: ours, v1's or the dependency's?
  2. What did v1 do here?
  3. What is the field's best known way?
  4. Does it add a format, key, layer or rule? If so, why can't the existing one do it?

  No evidence, no approval. One decision at a time.
- **Submodule pin bumps** happen only in root's landings, never in a lane's merge.
- **Where this is going:** a landing queue on the one root that merges main into each candidate, runs what it reaches on the merged result, and batches disjoint candidates. All builds, tests and checks become tasks on that root, admitted by workload, with results reused by digest. Seon's own merges get the same gate, and `:behind` fires only when main's change touches what the branch changes or reaches.

### Learn as we go, so we never start from zero
- **Each landing's lesson goes into the wiki in the same commit**, written by root: it rewrites the section where the problem lives (the idiom, its "Not this", the vars it concerns) and links every other section with the same problem to it. The next task touching any of them gets it in its context automatically.
- **A failed attempt's cause stays on its task** for the next attempt.
- **A problem outside your task is filed as a task**, never fixed in passing, never just mentioned.
  - Its plan states what you saw exactly, the cause (file:line), what correct looks like, and what it replaces.
  - Its proofs name the deftests and what each asserts.
  - Its needs say what must land first.

## 5. The running system, as a Seon agent sees it

Each checkout runs its own standing root with `bin/root start` (also `status`, `stop`, and `next`: stop root and start the newest generation's binary, the one the `:build` effect made from the last merges commit, on the same store).
- It runs detached, on the checkout's ports (`bin/ports`; the owner's main tree uses 7888, with the dashboard on 7777).
- Its store is kept under `runs/`.

Reach it through the `seon` MCP server (`bin/mcp`):
- `seon_agents`;
- `seon_eval` (an agent's own REPL, recorded `:outside`, or `root`);
- `seon_context` (what the model reads now);
- `seon_attach` and `seon_done` (work a task as a Seon agent).

Without MCP, one line works:
```
printf '{:op :eval :agent "root" :code "(my.root/problems sys)"}\n' | nc -w 10 127.0.0.1 PORT
```

## 6. The docs are a wiki, and the system's memory

- **Pages:** `docs/<topic>.md`, one concept per page. `docs/README.md` is a map of the topics, not a file list, so a research step can find what's relevant. A section is addressed `topic#slug`. The docs are mirrored in the database, their references resolved by the parser.
- **References are what the text cites, read by one parser** (v1's `seon.ref`, no declared lists): a link reaches a section, a backticked name reaches a var, a `file.clj:263` citation reaches its file and is marked `:line-reference` (v1's shape: line numbers go stale, so prefer citing the var). A docstring's citations are its definition's references, a plan's are its task's, a section's are its own; the context pulls them in automatically, and a section's backlinks say who cites it.
- **Depth follows the delimiter** (v1's `seon.ref`):
  - a bare name pulls its summary;
  - `` `name` `` or a link pulls its doc;
  - ` ```name``` ` pulls its code.
- **A healthy wiki** (owner, 2026-10-21: "a healthy wiki with good separation of topics and well linked so agents can get refs and know if they want to read more"):
  - **Patterns live in the topic sections, linked, never in a catch-all file** (owner, 2026-10-21: "patterns embedded into the wiki in different sections and linked so that we pull together and understand disparate problems that can be unified in a fix"). A pattern's home is the section where its problem lives: the idiom, its "Not this", its one implementation cited at code depth. Every section with the same problem links to that home with an ordinary link; its backlinks are its instances, so pulling one shows the others, and one fix can unify them.
  - **One home per concept.** A var or mechanism is explained in one section, the one that cites it at code depth (` ```name``` `); others cite it at summary or doc depth and link there instead of re-explaining it.
  - **Every section leads with one sentence that stands alone.** At summary depth an agent sees a section's heading and that sentence (as in v1), and decides by it whether to read on; then the body; then links to related sections.
  - **Everything is linked.** Every section is reachable from the map, every page links its neighbours, and nothing is orphaned or duplicated.
  - **Sections state the current truth by topic.** A change rewrites the section it concerns; nothing is a dated log.
- **Landing a change updates its section in the same commit,** and a pillar's owner writes the section where its pillar lives.
- **Before landing, read your task's context through the door** and confirm it pulls what the task needs. The check that goes red on a reference that doesn't resolve, or a section older than the code it cites (`bin/check docs`), is on the unmerged `jolt/patterns-wiki-proof` branch: until it lands, read the context yourself.

## 7. The machine

- **Builds:** `nice -n 15 bin/build`, in parallel, no lock (owner, 2026-10-21: "WTF is there a build lock?"; one machine-wide lock queued every lane's build single-file on an 18-core, 128 GB machine where a build peaks under ~2 GB; each worktree builds into its own `target/`, so builds share no mutable state). Every process on a store must use the same LMDB build (`target/lib/liblmdb.dylib`, robust locks); stop a store's processes before a build that changes it.
- **Full checks:** one at a time, niced (there is no lock: the owner removed it). Iterate with single modes.
- **Never flood it.** Anything that can make threads or processes starts at n ≤ 100 under `nice -n 15`. Watch `ps -M -p PID | wc -l`, and kill your own process past 200 threads. One experiment at a time.
- **A process that must outlive your tool call** is double-forked, which `bin/root` does. The tool host kills a background task's whole process tree when the task stops or its agent exits.
- **Disk stays bounded** (owner, 2026-10-21: "cleanup shit we aren't using anymore and build in rules so this doesn't get out of control"; the tree had reached 261 GB):
  - a lane's worktree is removed in the landing that merges it (`bin/worktree --remove NAME`, run by root after the merge; the branch stays in git);
  - a lane's scratch lives inside its worktree (`target/`, `runs/`) and goes with it; nobody creates a new top-level directory in `~/src/seon-jolt` (the fork repositories `docs/forks.md` lists, `notes/`, `bin/`, `test-results/` (test results by reach digest, shared by every checkout) and the worktrees are the only ones);
  - what is kept is what git and the store record (commits, task facts, notes); build output, compile caches and finished experiments' stores are regenerable and are deleted when their checkout or experiment ends;
  - root prunes each checkout's `target/check/` to its last three runs at each landing, and a run's old generations (each a tree and a binary, ~200 MB) to the one running and the one before it.
- **Leave nothing running when you finish,** and say so in your report. Kill only PIDs you started; never `pkill` or `killall`. Delete only paths you created.
- **Your scratch:** under your worktree (`target/`, `runs/`) or a folder named for your lane. The session scratch folder is shared.
- **Git in a worktree:**
  - `git -C` on your own checkout only;
  - never `git submodule` in a worktree: it rewrites the module config every checkout shares (it happened twice). After merging main, `bin/worktree --sync NAME` (run from the main tree) moves your submodules to their pins;
  - never bare `git stash`;
  - never `checkout`, `restore` or `reset` on paths you don't own;
  - commit explicit paths;
  - never push.
- **Secrets are never printed.** Check the context before any paid model call; paid runs need the owner's go. Install nothing system-wide. Never write in `~/src/seon`.

## 8. Mistakes we have made, and do not repeat

- **A clock, timer, sleep, poll, deadline, ping or fixed slot** standing in for a dependency or a workload. "Never came" is a recorded fact (stuck, stalled, dead).
- **A swallowed error, an ignored answer, a fake default.** Unknown is not `{}`, `0`, `nil` or `:ok`, and a proc answers every request it took.
- **Routing around a pillar:**
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
- **A half-baked plan that got built,** or too many lanes at once on overlapping work.
- **A clipping function** (`clip`, `subs`, `take n` for output). Output improves only through the one printer, or a render declared on the value's schema. Every cut says what it left and how to read the rest.
- **A check that cannot fail,** or that only prints. Every check mode ends on `X OK` / `X FAILED` and an exit status. When a rule changes, read every step it touches.
- **Landing on a check nobody read,** or copying whole files over newer changes.
- **Re-running everything on every change.**
- **Deleting a record** (a store, a branch, history). Runs are kept, and reclaiming space is the owner's act.
- **Flooding the machine,** killing by pattern, or leaving things running.

## 9. Done

It runs end to end through the path agents use; the merged result is green; its numbers are in the commit; its wiki section is true; you can watch it work; and nothing of yours is still running.
