# Decisions

The owner's standing rulings, one entry each: his meaning in correct terms (never quoted; asked first when unclear), its date, and where the rule is stated now. The mechanism lives in `AGENTS.md` and the wiki; the reasoning and the history around each ruling are in git. A ruling a later one reverses keeps a one-line pointer to it.

## 2026-11-16 rulings

The owner's ruling at the end of the 2026-11-15 night, when the weekly budget ran out.

- **The next sessions' focus is the test system, fully implemented inside Seon, with no more hacks** (owner, 2026-11-16 morning, after the night's pilot merged once through `seon_done`). Proving and landing go through the system's own `:prove` service and `seon_done` for every session and agent. The fast lane (`bin/land --fast`), `bin/land`, `bin/prove` and the worktree path are deleted once the inside path does their jobs, each Deletes line naming what does the job now. No interim mechanism is added beside them; feature work waits until this holds. Where it stands and its order: [plan](plan.md#where-things-stand).
- **Seon runs on rails, so the rules agents follow shrink** (owner, 2026-11-16, after the account change). Nothing merges into main unless the tests its change reaches pass and it has no conflict, and those tests run at the cost of what moved. A rule in `AGENTS.md` that a rail enforces is deleted in the landing that makes the rail enforce it, and that landing names the rule. Finishing the test system inside Seon is the first goal; the other areas work their own items but give way to it.
- **Agents write only into the system's own homes** (owner, 2026-11-16). What any agent learns goes into the wiki, schemas, code, tasks and config, edited in place where the fact belongs and linked to what it concerns, so the context an agent pulls at run time holds it. No append-only log, notes folder or memory file holds a fact; an agent that needs a place of its own gets one wiki page, kept current. Where: `AGENTS.md` §7.

## 2026-11-15 rulings

The owner's rulings on the morning after the first night run on its own.

### The self-merge path goes first, so the system stops needing hands (evening)

The owner wants the plan's self-merge path finished as soon as possible, so the system is reliable and fast and nobody orders landings by hand. Three choices, made with root at about 17:00:
1. **Landings:** a landing on the path goes first, always. The path is main proving in the system, `bin/test` and `bin/prove` as thin clients of `:prove` (E), admission and the update owner (D), one `:merge`, the `seon_done` pilot, and `bin/land` deleted (plan items 10, 11, 13, 14, 17, 18 and the Stage 1 safety list). A green feature landing goes only when no path landing is ready, so finished work does not drift from main.
2. **Areas off the path move onto it:** web, flow and context land what is already green, then each owns a path item. Web makes the MCP relay answer each call on its own. Flow makes proving run as Flow procs admitted by backpressure, and reviews runtime's D. Context does plan items 8 and 9. Web's person view waits behind that.
3. **The first real `seon_done`** is tried as soon as main's run proves green in the system and plan items 1, 2 and 4 are on main, not after the whole safety list. A failure is filed as a task. Until D lands, the three-land rule holds the load.
4. **Drivers first, right after deps' pin:** the context session runs its `seon-driver` Claude agents (Sonnet, only the `seon_*` tools) on real tasks as soon as deps' pin restarts root, before plan items 8 and 9. First a parity check of a driver's reply against a model agent's request, then two drivers beside the DeepSeek rounds. What a driver can't do through `seon_*` is filed as a task.

### Once running, Seon cannot crash or hang; a wait inside it is a design defect (evening)

The owner's rule: once a process is past boot and running, nothing inside it can crash it or hang it. Everything that can block, fail or crash goes to an edge with one owner (the OS, files, the network, the model, native code, git), and the edge turns it into a value or a typed error. Inside, everything is Flow procs and immutable values, so a block, a lock or an unbounded wait found inside the runtime is a design problem to fix in the design, never to guard or retry. The core areas split it:
- **flow** reviews, fixes and implements Flow and Seon's use of it, and owns the list of state and waits outside Flow (health's `:stateful-outside-flow`);
- **runtime** owns reactions (`seon.ratom`, the reactive core), and moves each derive or track onto it;
- **deps** owns the interfaces to the libraries and native code (Jolt, Chez, Datahike, foreign calls), so a failure there reaches Seon as data at the edge.
Each area works the others through agreed interfaces, recorded in the task they concern.

### Sessions switch to REPL-driven work through the MCP tools once every part works, on one owner's plan (evening)

The goal is that every session works as a Seon agent does: attached to its agent, it edits with `seon.repl/edit!` through `seon_eval`, proves through `:prove` and merges through `seon_done`, so the separate systems (worktrees, per-checkout systems, `bin/land`) can go. Sessions keep the Edit tool and the import hook until every part of that path is shown working. One owner plans the switch ahead and pushes it: the context session, since its `seon-driver` agents already work only through the `seon_*` tools. Its plan lists each kind of edit a session makes and whether the path serves it, with what a driver lacked filed against the shared tools, and the gate that says everyone switches.

Every edit goes through the branch: `edit!` writes onto the agent's branch, and the change reaches main only through the merge's conflict check, the tests its reach selects, and the merge itself. Nobody writes a file directly. So `edit!` must address every kind of entry the system is built from as a branch-held place: definitions (functions, vars, macros, tests), namespaces (created, changed, deleted), schema entries, config keys, task entries, doc sections and pages, test fixtures, scripts, and build inputs (pins, `deps.edn`). Its raw disk write is refused for anything in the program's tree.

### Tonight: rounds may spend $25; a new binary takes over in parallel, so no restart ends live work (evening)

Before going out, the owner set three things:
- The rounds and drivers may spend up to $25 of the model budget tonight (that night only; the weekly budget ran out on 2026-11-16).
- Nothing is held for his review: root reads every landed diff, and the reviewer runs every two hours.
- A land that needs a new binary must not end live agents. The plan in `seon.system/an-update-loses-nothing` is finished first: the new binary is built, boots beside the standing system on the same store, runs every test against itself, catches up main's program by evaluating the forms that changed (`seon.program/load-diff!`), and takes the system over only if it passes. A red leaves the standing system running as it was.
- State lives in one of two places, each built to start and stop properly: the db, which a clean close leaves byte-identical, and Flow procs, which drain and start under Flow's lifecycle. A switch-over is the db closed cleanly plus every proc drained. State anywhere else (a process-global atom, a forked process outside a Flow graph, a cache with no owner) is the design defect to move into one of the two, never special-cased at switch-over.

Runtime builds this right after the update owner, which it extends. Until it lands, a path land that must restart root goes ahead, and context re-releases its round agents on the new root, told before the land starts.

### Sessions land as soon as a land is ready (night)

The owner wants sessions to merge and update as soon as everything is ready, with no wait for root's word. Ready means: main merged in; laws, health and the reach tests green; `runtime-for` says the land keeps root running; and no other land running at that moment. A land that would restart root still waits for root's word, because restarts are batched until flow's drain lands. A session reports each landing to root through Seon messaging. This supersedes the rule that every land starts on root's word.

### Three priorities tonight: tests inside the system, the core infrastructure in use, messaging (late evening)

The owner named three priorities, all at once, and this supersedes the entry below where they differ:
1. **Tests run inside the system:** one runner, `:prove`, through E. The old runner goes.
2. **The system uses its core infrastructure:** every hand-built derived value and incremental update moves onto reactions and ratoms (`seon.ratom`), and each move deletes the duplicated code it replaces. Runtime fixes the core first (the review's five changes). Then each area's reader lands as soon as its REPL prototype measures well, without waiting for D.
3. **Messaging:** sessions and agents talk through Seon's messages (`seon_message`, `seon_inbox`, attached session agents).

### The reactive switch-over: the design gate now, reader landings after the self-merge path (evening)

The reactive switch-over's design gate runs now, beside the self-merge path. All area entries `seon.ratom/<area>-reads-on-the-core` reach main, an independent reviewer judges the core against Reagent and re-frame, and each area measures its reader against its engine as a REPL prototype. The reader landings that delete each hand-built engine go after runtime's update owner and D, and take no landing slot from the path.

### Iterate on real agents; nothing lands on them unproved (evening)

The owner wants the slots, gaps and hand-ordering gone, with agents working inside a system that adapts and is fast, and that end reached by iterating, never by pushing untried mechanisms onto agents. A change on the path agents use is done only when a real agent has used it: a round of DeepSeek agents or a `seon-driver` works a real task through it, and its turns-to-merged are compared before and after. A change that adds turns or confuses agents is reworked or reverted, not defended. Rounds and drivers run continuously once deps' pin restarts root, and what they lose re-ranks the work (`my.root/run-losses`).

### Sessions' tools are thin over the system, and honest where they are not (evening)

The owner wants Claude sessions' tools integrated with the running system. Every `bin/` script and MCP tool is a thin wrapper over a `seon.server` op, carrying no logic, state or decision of its own. Where one still does its work outside the runtime, its header says so plainly: what it does outside, and the task that moves it inside. Sessions and Seon agents feel the same problems, so pain a session hits with a tool is fixed in the shared mechanism (the op, the proc, the namespace function), so both gain, never in the script alone. Root keeps the list of scripts and how each deviates, and each gap is a task in its owning area. A script's outside logic is never ported into the runtime as it is: its task asks what job is left once the runtime's own mechanisms exist, and builds the runtime's design for that job, often better, or deletes the script outright.

### Tests always have helpers making them millisecond tests of the goal

"Tests always has subagents running (Sonnet is fine) that refactor tests to prove 90% of their goal in milliseconds, and drop tests that are low quality and slow" (from his phone; root's rewrite, his meaning). The tests session keeps Sonnet helpers working at all times, in its one worktree, each on test files nobody else edits, slowest first ("Have the agents run those conversions slowest first"); a test is rewritten to prove the most important 90% of its goal in milliseconds, or dropped when it is low quality and slow, its `Deletes:` line naming what now proves its goal (`AGENTS.md` §2, helpers; §3, no functionality removed).

### Everyone switches over once a change is verified

"I want everyone to merge main and switch over immediately, once it's tested and verified" (written from his phone; root's rewrite, his meaning). A verified change to shared machinery is the one way from its landing: every session and every running agent merges main at once (an agent loads main's changes in place at its turn boundary), and no transitional second way waits for a later landing. For landing 12 this replaces root's ruling that `bin/test` keep its file cache until E: its next slice moves `bin/test`'s verdicts onto the store and deletes the file cache. Stated in [the plan](plan.md#the-order).

### Jolt's instrumentation, in deps' order

Told that ticks count Scheme work only (no foreign calls, GC copying, parked time or interrupts-off time: chez `s/cpnanopass.ss:2966`, `s/event.ss:47`) and that the 161M/300M swing is unexplained, he chose deps' order: dumps that need no event check; CPU time and bytes per fiber per dispatch; steps scoped by the existing nesting measure `ticks-of`, with no new marker; a counter of foreign lookups; then one experiment that settles the swing before ticks can fail a test. Plan in `deps-ticks-soundness-2026-11-15` (deps' task).

### Debug inspects what was rendered; it never interprets again

He ruled that debugging shows the rendered versions that exist, exactly as they were sent, never a new interpretation of the records, which would defeat its purpose (2026-11-15). A debug view joins the components a turn actually assembled, each stored once by content digest, and nothing is re-rendered; its result's digest equals the turn's `:seon.model-call/sent-hash`. Rendering itself is driven by the pull and the schemas the one walk detects on each entity as it recurses; a render function may also query the db.

### An agent offers the person a place; it never moves their page

With `/` always root's chat, he first retired `my.user/focus!`, then revised it the same day (2026-11-15): keep and adapt it. The person's web state stays data any agent may update; an agent never moves the page under the person, but offers a button in the chat that takes them to the right place when they tap it, its action the one function that updates their web state. Root's prompt says to offer such a button.

### Messaging is flow's: a wake that never blocks, the person included

He ruled that the inbox and the rest of messaging belong to flow, not web: it must be done right from Flow's side, waking an agent without blocking, and it must work for the user, who is also an agent driving a session through the page's input box (2026-11-15). One path serves a Claude session's inbox, a Seon agent's wake and a person's message; flow asks web and runtime for what it needs, and web draws what the path delivers.

### Claude agents are driven through Seon's own turns

He asked that Claude agents be driven through the Seon MCP turns, with context launching Sonnet agents on real tasks to learn what works and what confuses, using tools as close as possible to a Seon agent's (2026-11-15). A driver is a project agent type allowed only the `seon_*` tools (attach, context, eval, done, message), reading `seon_context` as its whole view and acting only through `seon_eval`; its runs sit beside model runs of the same tasks, and their difference is the finding. A driver gets each turn's changes exactly as a model agent does (what moved, in each `seon_eval` reply, from the one turn assembly), reading `seon_context` once at attach; where the outside path differs, it is fixed. Context runs it.

### A binary is built only when it is needed

He ruled that the binary is rebuilt only when a change needs it: when a build input changed and the new binary must be tested (the parallel system), or when the system is about to restart; never on every land (2026-11-15). A kept-running land builds nothing, since a boot catches up main's program in place. Runtime carries it in plan item 17; E removes the per-land build of the land's own checkout.

### Safe self-landing before the reactive switch-over continues

Asked to order runtime's three items after the health land, he chose (2026-11-15): one owner for updates first (lands never collide; a binary is built only when needed), then admission by weight (the system schedules proofs, replacing the hand gate and root's ordering), then the reactive switch-over's next land. The goal they serve: agents prove whenever they want, the system schedules, and agents land safely on their own.

### Every compaction leaves a principal engineer's prompt

He ruled that a session about to compact leaves its successor a prompt he can paste into a fresh session, casting it as the principal engineer leading its area on the company's most important project, turning a working prototype into a real product, with rigor, correctness and a sharpness that makes it a pleasure to work with (2026-11-15). Stated in `AGENTS.md` §3.

### Tests never hardcode defaults

He ruled that no test hardcodes a default (2026-11-15). A test reads a default through the one config resolver and asserts the behaviour around it, never a copy of its value, so a changed default cannot leave a stale assertion red on main (two did that day). Tests sweeps the existing ones. A default itself lives in the schema: a `:default` property on the key's malli schema entry, applied by the one resolver (`malli.transform/default-value-transformer`); data plans it with the config scope.

### A new binary runs in parallel, is tested there, then takes over

"We could run a system in parallel; it doubles as the test harness, and if it passes we switch over. Drift is matched by replaying the program's evals since then" (from his phone; root's rewrite, his meaning). The new binary boots beside the standing system on the same store, the binary tests run against it, and green it catches up main's program (`seon.program/load-diff!`) and takes the writer; red, the standing system never stopped. Plan item 17 (runtime) and E's binary tests (tests).

### The user talks to root; root manages the agents for them

"I want the average user to never know that there are sub agents. Root is responsible for knowing what their agents are doing and managing them; if I ask a question it either looks it up quickly or messages the right agent to retrieve the data, then displays it. Root is a principal product and project manager working for the user, meant to understand what they want done and translate it to the agents' specialties. All agents have the same chat abilities, so we aren't building separate systems; on `/` the user goes through root, and that is root's primary task" (from his phone; root's rewrite, his meaning). Web plans the person view on it; the system view moves to debug.

### An agent runs while it learns; refine and restart when it stops

"Measure while the agent is working, and if it's learning, let it go longer. Set up the refinement task: if we can trigger it at points, the refine agent's task is to edit the task and its evals, and we restart the agent from a better point" (from his phone; root's rewrite, his meaning). A run's progress is measured each turn; while it learns it runs past the turn cap, and when it stops learning its refine (`seon.task/refine-tx`) edits the task's plan and proofs and `my.root/restart!` starts a new agent from the refined task. Then, making it concrete: "Have proofs that, if by turn X (configurable) the agent hasn't updated the plan or written any schemas, functions or tests, we trigger the refinement and then restart the agent. Make the system capable of it, and adapt it to be data oriented, not process oriented: derive process state rather than encoding it. The refinement agent's task pulls in different context designed to help a lost agent get focused; we learn from both sessions what agents need in their context, a mix of static knowledge (like the system prompt and skills) and dynamically pulled relevant context, so agents aren't guessing" (from his phone; root's rewrite, his meaning). Progress is a query over the run's branch (its plan, schema entries, definitions, tests), the trigger one reconciler rule with a spec'd turn key; context plans it, first checking it on round 3's runs. Shown that writes-by-X also triggered two runs that were investigating well, he chose "different nets": an early net at a short turn with progress widened to anything productive, to catch looping; a later net with the strict definition and more time, for a well fleshed-out task; and the metrics of a good, well-documented task mined from what agents have written, against turns to merged. Stated in [tasks](tasks.md#a-refine-curates-its-own-task) once built.

### Landing 12 lands with its file cache one land longer

Asked whether landing 12 waits for the file cache's deletion, he chose to land now; the cache is the named transitional way, deleted in the next land.

### No landing past a red; a bad test is deleted, then backfilled

Asked whether a land may merge when its only red is a known test already red on main, he ruled that no land merges past a red: a precedent of merging over a red is not set. If the test is bad, it is deleted now and backfilled later when it was worth having. A test that asserts what principles §6 rules out (wall time, load) is deleted and its goal filed as a backfill task, named in the Deletes line. First case: `seon.system-test/a-root-stops-in-a-second` (wall time), backfilled as a step's declared ticks.

### A land proves a copy of its commit

Asked how to free a session's worktree during a land (1,007 session-minutes locked on 2026-11-15 over 103 lands [M], and one land proved files still being edited), he chose: `bin/land` proves a throwaway checkout of the exact commit it lands, so the worktree is free at once and a proof always covers what merges; deleted with E. Stated in `AGENTS.md` §3.

### Flaky correctness first; load is not a cause

Asked whether load explains a flaky result, he ruled that load has no bearing on correctness: a loaded machine only makes work slower, so a result that is wrong or flaky under load is a correctness defect, and flaky correctness issues are fixed first. Every overrun on 2026-11-15 stopped at its 300 s bound at loads from 6.5 to 41: a hang, found and fixed at its wait. Stated in `AGENTS.md` §3.

### Verdict reuse lands first

"I'm pissed this isn't done yet. Find out what is holding up the tests agent from getting this merged, and prioritize it" (from his phone; root's rewrite, his meaning). Landing 12 (verdicts as entities with their read keys) is the first item of every session after the Jolt pin; its reviews preempt the reviewers' own work. Stated in [the plan](plan.md#the-order).

### No clock guesses when our own work finishes; the world outside gets declared timeouts

He ruled that `:db/txInstant` stays as the ordering of facts, and that his earlier wording of the principle be made more accurate: no clock is used to guess when work will finish. A wait on our own system, where the result is certain to arrive but its time is unknown, blocks on its Flow channel until the producer's value, typed error or death; a wait on the world outside (retrieving data from the web, for example) has a spec'd timeout config entry and ends with a typed error. Ordering facts by `:db/txInstant` (the merge's `overtaken`) is not a clock deciding when work finishes, and stays. It refines the 2026-11-14 rulings on timers below. Stated in `AGENTS.md` §4 and [clocks](clocks.md#two-kinds-of-wait).

### Ticks do not fail a test until they are measured reliably

With the reds as the focus, he ruled that the tick-counting test be disabled until ticks can be measured reliably. The same test at one digest measured 161M and 300M ticks, so a tick count does not make a verdict red until the same test at one digest measures within 10% across runs; ticks are still recorded. A named exception to the no-switching-off rule, made by the owner with its end condition. He then ruled that the tick measurement itself be fixed so that exceeding a limit is a failing condition, and that until then the check is temporarily disabled. Fixing the measurement is the tests area's first item, and the land that fixes it turns failing on ticks back on. Stated in [cost](cost.md#what-is-measured).

### Encode what was learned into the wiki

After the night's run, he asked that the instructions given to root and to its agents, how they fared, and what was learned be reviewed, and that the lessons be encoded in the wiki as documentation and task updates, not only as changes to the plan. Each lesson goes to the section that owns it: [clocks](clocks.md#two-kinds-of-wait), [merging](merging.md#a-merge-change-is-proved-on-a-real-branch), [cost](cost.md#what-is-measured), [sessions](sessions.md#the-managing-session), `AGENTS.md` §9; each problem left open is a task with its proof.

### Work from inside the runtime

He ruled that Claude sessions move away from building infrastructure that works only from outside the system. The focus is the system as seen from the runtime: Seon agents work inside it, and Claude sessions use its internals through the MCP REPL and thin wrapper scripts. The command-line tools and the traditional offline build-and-test approach are being left behind; everything moves to a runtime that forks and checks itself, can build a replacement binary and switch over to it, and for the most part evaluates the latest code in place while the runtime tracks main. Stated in `AGENTS.md` §0 (five working rules) and §6.

### The store is the source of truth; a clean, green branch merges by itself

He ruled that the Datahike store, not git, is the source of truth; the store's internal updates are merged out to git, and hooks remain so that Claude sessions can update code on their branches. He approved automatic merging when there are no conflicts; asked what a branch needs, he chose: no conflict and the tests its change reaches green, then it merges with no release. Stated in `AGENTS.md` §0 and [the plan](plan.md#the-order) (M5).

### The test system queues with Flow; no hacks

He ruled that the test system is one where every agent can request tests whenever it wants and the requests queue through Flow, and that the work goes into building that, not into workarounds. Asked about a load gate in the frozen scripts, he chose this instead. Stated in [the plan](plan.md#the-order) (M3).

### Every waiver goes; calls are no cost dimension

He ruled that every waiver be removed: each limit is either owned and tuned until it holds, or removed if it cannot be measured reliably. On `:calls`, which counts every thread: drop it. Stated in `AGENTS.md` §3 step 2 and §3a.9.

### No "shard": Flow's words and Flow's way

He ruled that the word shard stop being used; the code and docs use Flow's terms and do the work the way Flow does it. Its words are worker, item and batch ([vocabulary](vocabulary.md#words-we-do-not-use)); its shape is M3.

### Estimating is automatic, from what is built

He found that agents keep falling back to writing code as fast as possible instead of working out the algorithmic estimate first, and that they must do the estimate automatically, without being prompted. Most operations come out 100x or more too inefficient, which takes no heroics, only REPL-driven development, measurement and common sense. Much of this is already built, so the work may be refactoring and wiring: every agent starts by looking for existing code instead of writing another implementation, and collapses duplicate implementations whenever it finds them. He chose all four: the estimate as task data, a live cost canary, bounds on hot paths, and cost beside bound in every eval's answer. Stated in [the plan](plan.md#the-order) (Track E).

### History stores what was sent as its components; a switch resumes at the turn

On storing each turn's request, he ruled that what was sent and what the model sent are stored, but not full copies of rendered data: only the components that are assembled. A turn keeps its assembly (components at their content digest, in order) and the model's output; a component's output is stored once by content. On a binary switch: in-flight evals end and resume at their turn boundary, flights and requests are facts, and nothing of ours is killed on wall time but one spec'd stall dial for native code (he chose the recommended answer). On the merge's time, he recalled being told there was a reason a replay was needed, and asked whether the history is inconsistent between what happened and what was recorded as separate transactions; he asked for this to be dug into and for a proper solution that is also efficient. This is open, with data and deps; it gates landing 3. Stated in [the plan](plan.md#the-order) (landings 3, 5, 12).

### Long work is a message, never a blocking command

After seeing a session block for minutes on a run and hold up all updates, he ruled that no command blocks its caller: long-running work is a Flow system, and sending it a message triggers the task and the sender is notified when it ends. On complexity, he ruled that when a design turns complicated the work steps back and takes a fresh approach, since exactly what has changed is always known and there are efficient solutions to everything in the design; and that things are always fixed at the data model first. Stated in `AGENTS.md` §4.

### The overhaul halves the code

He ruled that the codebase be half its size when the overhaul is done, so de-duplicating ideas is essential, and that all duplicate systems collapse into good tools and systems used everywhere. `src` is 43,312 lines on 2026-11-15; the target is about 21,600, by collapsing duplicate ideas, never by removing functionality. Stated in [the plan](plan.md#the-order).

### An agent loads main's changes in place

Round 1's transcript showed agents re-forked into a cold process every turn: each turn merged main whenever main moved, and a merge that brought code ended the run. Asked how an agent should take main's changes, he chose loading in place: at each turn boundary the agent merges main and evaluates main's changed forms into its running process, as root does on a landing, re-forking only when a build input changed. Stated in [the plan](plan.md#the-order).

### A branch merges whole; no list of what merges

He ruled that the hand-maintained list of what merges be removed: everything merges, and no process produces conflicts. The partial merges agents were doing go away; an agent either works on its branch, which is always merging to main anyway, or it is main. The mechanism is branch-based, so branches of branches use the same system. A branch's net change since its merge base merges whole into the branch it came from, by one function; a conflict is only a fact both sides changed differently. Run records are written only by root on main; derived values are recomputed after the merge. Stated in [the plan](plan.md#the-order) (landing 6).

### DeepSeek is the default provider

He ruled that DeepSeek is the default provider. A root started with no `--profile` had no provider, and a restart without `--profile deepseek` left round 2's agents on `:seon.model/no-provider`. With no profile, agents now run on DeepSeek; `:none` is set explicitly, and tests run under `--profile scripted`. It replaces root's ruling of 2026-10-22 (no default model). Stated in [model](model.md#the-default-model).

### Leave the temporary mode first; only Seon-first designs

He asked how much longer the work stays in the temporary mode while the last pieces of the real test system land, and ruled that this be prioritized so testing is fast and agents merge inside the system, not through scripts. He ruled that designs which are external command-line tools or external infrastructure are not accepted: only thin wrappers and Seon-first designs. Design from a Seon agent's standpoint, assuming the runtime, Flow, the reactive core and the db work; where they don't, making them work is the task. The path, in order: main proved on every landing, `d/changes` then one `:merge`, verdicts by read keys, one admission, thin `bin/test` and `bin/prove`, the `seon_done` pilot, updates that lose nothing, `bin/land` deleted. Stated in [the plan](plan.md#the-order) and `AGENTS.md` §0.

### Prior work first, every time

He ruled that the thing in question was already built, and that every agent starts all work by looking for existing or previous solutions. An agent reports that it searched and found no prior work when there is none to quote; one that repeatedly fails to do this is terminated and restarted. He further ruled that when an agent stops following instructions, the cause is found by an external audit, the documentation cleaned up, and a fresh session started. Stated in `AGENTS.md` §3 (a plan's first part).

### One root, an ordinary agent; the system is not an agent

He ruled that root is an ordinary agent that happens to be first, with all its children and their children under it, so the name root remains accurate. Every instance of what an agent is must be one instance: the env and the agent were to become one schema, with no similar system built elsewhere. There are not two roots: a system process exists, but it is not an agent and does not hold internal process state. Where concepts conflict, Flow is asked what should be separated out; agents each keep their own standardized setup and that is not broken. The design is task `seon.system/the-system-entity-and-root-as-an-agent`.

### A session's REPL is a connection to an agent, recorded as any agent's

He ruled that a session's REPL works like a REPL connecting to separate systems: a session can attach a remote REPL to any existing agent, or make its own agent. Evals sent from outside are treated exactly the same, so one mechanism records the turns the same way, with no duplicate systems anywhere; duplicates seen are reported to him, and a clear fix is simply made. Stated in [server](server.md#ops) once the change lands.

### Config has scopes; the db holds it after boot

He ruled that files exist only for boot, to land config into the system db, and from then on the db is the source of truth. Config has scope: a global default that is either a real value or a Malli schema default, overridable locally per agent or wherever needed, so three agents can run on different model providers because their configs are on their entities. Who drives an agent, its model or a Claude session through the REPL, is one such config entry, standard or a config option, and root is not special: it is just an agent's name. Stated in task `seon.system/the-system-entity-and-root-as-an-agent` until [config](config.md) carries it.

### A merge is proved where it will run; no separate main run

He ruled that a merge needs no separate run: a branch merged with main gets the entire proof on a fork of the running system. A version that does a full build of the binary from the branched code is also wanted; it does not depend on files on disk, which are merged last, only once every check has passed and the change merges to main, with proper mappings to git so the branches align. Then: only digests that moved re-run; a binary is built only for tests that can't run in a fork, and only when what they read moved. He wants the minimum set of tests that accurately tests the system, with agents put on rails so the wrong thing is impossible, not guarded against; a store branch is a git branch of the same name. And every solution is incremental: a proof that fails because a single doc is updated does not start over. Until test digests come from one source, a main run stays as the net. Stated in [the plan](plan.md#the-order).

### An agent's REPL is standard and live

He ruled that an agent's REPL is standardized so that a live connection can issue REPL commands that no other thread on the agent blocks, and the REPL parks and does nothing when unused; Flow designs it. The system process is read as data and changed through ops; it is not an agent.

### Restarts wait for turns; caches move with the reactive core; the erase keeps what the import holds

A pin or native change waits until no agent is mid-turn. The system process's caches (reconciler index, text index, vector rows) move when the reactive core lands. The one erase keeps what the files import plus replayed run history.

### Dependency source is indexed through the same system

He ruled that all code we depend on is indexed, for the situations where code must be detected and pulled in as reference; it is scheduled so this is cheap and goes through the same system, when the time is right: on change, in the idle queue, never at boot. Its attributes are its own (`:seon.library/*`), decided on evidence (the program digest hashes every `:file/text`). Task `seon.vector/grep-reads-every-dependencys-source`.

### Messages reach whoever drives an agent

The messaging audit's parts 1-4 approved: a reply carries the messages new since the driver's last read; a parked `seon_inbox` op; who drives is data; a session's message is from its agent. Sessions move their own messages onto Seon after 1-4 are proved on the standing system. Task `seon.server/a-message-reaches-the-session-that-drives-its-agent`.

### Names follow the code; one entity may match several schemas

Asked whether new attribute names are good, he ruled how to judge them: find where the code predominantly runs the functions with those schemas and prefer those names, unless that code is the conflicting root code, in which case the specialist agents are asked before choosing. Names and schemas matter greatly: data is not duplicated and the concepts hold. A single entity may match several schemas when they are different ideas or extensions of an idea. On the erase, it is allowed once any important data is known to be written to disk, by checking where data is imported from into a fresh store. Stated in [data modeling](data-modeling.md) and the task above.

### Root reviews after the merge

He ruled that root may review merged changes after the fact, since a merge is easily undone or fixed when a problem is found. Sessions land themselves (`bin/land --fast`); root reviews each landed merge afterwards, and a problem found is reverted or fixed forward by its owner. Stated in `AGENTS.md` §3 (the fast lane).

### Root fixes errors anywhere; the plan is A to B

He ruled that root can and should fix errors anywhere in the system, distributing the work to expert agents as needed, and that root's job is the plan from where the system is now (A) to where it should go (B). The order he approved is in [the plan](plan.md#the-order).

### An agent changes the program at its REPL; no fence around the checkout

He ruled that no guards or fences are put up: an agent can do anything its REPL can evaluate, and good defaults and instructions steer it. Then: fix the systems so the class of problem cannot occur, or accept the risk of giving agents power and expecting responsible action for now; simplicity and de-duplication come before safety, guards and fences. Round 2's agents wrote their changes as files into the main tree through `SEON_CHECKOUT` and merged scratch definitions. Decided (runtime and context, root confirmed): the checkout stays main's tree for reads, config, git and the shell; the program changes only by evaluating definitions on the agent's branch; the prompt says so. No per-agent tree unless a round shows a need beyond editing `bin/` shell, which no task asks for any more (curation). It replaces the earlier "the branch's own tree". Stated in [prompt](prompt.md).

### Forks are pushed before main

On deps' finding that none of main's pins of Jolt, Datahike, konserve or persistent-sorted-set was on GitHub, asked whether to push the forks, he ruled that the forks are pushed first, then main (relayed via root). Every pin's fork branch is pushed after the pin, and root pushes main only once each gitlink on it is in a remote branch (`git branch -r --contains`). Stated in [forks](forks.md#branch-scheme).

## 2026-11-14 rulings

The owner's rulings of this day and the night before, each stated as his meaning where recorded and with where it is now stated.

### Main is kept clean

Asked whether an agent's merge should be judged only on what it changed, since the first backlog task's merge was refused for main's own stray reds, he ruled that main must be clean. A red or open problem on main is its owner's first item. Stated in [the plan](plan.md#the-order-of-work-now).

### One watched agent now; the reasoning budget raised

Context runs one watched task now, random rounds after the model call's bound. On `:seon.config.ai/max-tokens`, he ruled that it be raised to whatever it needs to be, with a report to him only if the cost starts getting out of hand; if it is cheap he does not mind. Set to 65,536 in `resources/seon/config/default.edn`.

### One reaction over the store; dedupe and upgrade everyone

He ruled that root's job is to see waste, which is how it finds what to delete: unify the concept at the root of the problems and the other problems stop being possible. On the survey, he asked what the best way is where there are many, and whether there is already a way of auto-tracking which reads have changed, in ratoms and in Datahike reads; he wants the optimal solution, not the first one each agent came up with. On recording's switch-offs, he told root to assume agents made poor choices, and that easily fixable bad ideas are not accepted. On cost, a few milliseconds do not matter: the focus is the mechanism agents can use that is simple, the most expressive and the most powerful, optimized later; for now everyone is deduplicated and upgraded. Then he asked whether the ratom can do the reaction work in the way agents expect when they write hiccup components: most agents are trained on Reagent and he does not want to teach a foreign concept; two interfaces are acceptable if they are one system computing efficiently. Agreed by every area: one reaction over the store, one tracker, one stored index. Stated in [the plan](plan.md#the-root-concept-one-reaction-over-the-store).

### Store or derive; render the system from itself

He ruled a general rule for deciding whether to store data on an agent's record, or anywhere, or instead to render it from another trigger matching the pull pattern through a render schema: ask what must be stored and what can be derived. If it need not be stored and can be derived, deriving it must be cheap, or some additional data is stored to make it cheap. He wants a balance, and above all that the system is rendered from itself, as a data-driven system. Any departure seen, including data stored that has no consumer, is evaluated and its performance looked at from both sides. Stated in `AGENTS.md` §4.

### Estimate first; 90% for 10%

He ruled that for all work agents create algorithmic estimates for their solutions before implementation and reject inefficient designs. The balance is between simple to express and right: full efficiency is not desirable when 90% is reachable with 10% of the effort and the codebase is simpler, smaller and has less code. That is a rough metric to follow. Stated in `AGENTS.md` §3.

### One way, made optimal

He ruled that all hot paths are made optimal and that everything that can be is made a hot path: one way per job, and even two ways to do one job must justify themselves. Every job routes through its one path, each path has one specialist who holds it to its lower bound, and a second way is a problem until justified. Stated in `AGENTS.md` §4.

He added that perf readings are mostly a canary: a number like 10 seconds to write a transaction that should take milliseconds is not allowed. A hot path's API is made so easy to use correctly that agents want to use it. A cost far over its lower bound stops the work (AGENTS §3, 10x); a hot path's API is the easiest way to do its job.

His reasoning: Clojure is like this. It is very hard to write Clojure that is stateful or imperative; the tools always refocus on a few solid ways to thread through them, so one builds with tools optimized for their execution instead of supporting everything. Seon adds constraints to the system in the same way, to optimize: one best way of doing things, reused everywhere. To be folded into [principles](principles.md#4-one-mechanism-per-job) at the next retrospective.

### No timeouts in tests

He ruled that tests have no timeouts. A test ends by its result or by a deadlock event (Jolt's quiescence, with every fiber's dump), never by a clock; an effect a test does not provide answers its typed error at once.

### Writes through Flow, for sameness

On whether one writer per branch was fine, he ruled that he likes Flow processing the writes, for monitoring and for keeping the interface to long-running stateful processes the same, and that he will pay a reasonable overhead for that sameness. On flow's measurement (the LMDB write lock is ~2% of a commit, one writer would serialize the rest; a per-branch writer proc would be a second queue beside the :repl in-port), he ruled that if it is a pain, direct writers are left. Each agent keeps writing its own branch; pause and visibility come from Flow's pause relayed to every graph and the ping's in-port fill and park site.

### Sessions land themselves under load 18; a resolved conflict re-conflicts if main moves

Asked at 20:55 who starts a land, he ruled that sessions self-land while the load is under 18. A session lands when green without a release while the 1-minute load is under 18; root orders priority only when several are ready. Asked what happens when main changes a key again after an agent resolved it, he ruled that it conflicts again: a resolution holds only while main's side is what the agent saw. On tests reading other tests' facts, he found that hashing and test reuse are poor because of this, to be fixed as soon as possible, and he wants as much sharing as possible, learning also from the reactive work. Tests keep the shared world; a verdict is reused only by what it read, recorded as the reaction core records reads. Stated in `AGENTS.md` §3 and [merging](merging.md).

### Tests work on branches of the standing system; rounds start after Stage 0; a peer wait ends by the peer's answer

At 21:00 the owner chose: tests that use a root work on their own branches of the standing system and fork their agents from its fork server, with no root booted per worker (never its main); and the random rounds start as soon as the first task merges through Seon. Asked how a test's wait on a root should end, he said neither option is clear and asked for more research. Root found at the REPL that the wait is inside the root (its api waiting on a proc stuck in one step), so a step that burns past its declared ticks ends as a typed error the asker receives: work, not a clock, and no deadline at the test's edge. Stated in [flow](flow.md) and [testing](testing.md).

### Everyone moves onto the reactive core; it is stress-tested before victory

At 21:00 he ruled that if the reactive core is built, everyone moves onto it, and it is stress-tested and tuned before victory is claimed. It must make incremental rendering cheap and easy to use. The core is done when every area's copy is deleted onto it (plan item 5), its cost per change is proved O(d) and constant in the store, the number of tracks and the context size, and an incremental render is plain hiccup that derefs tracks, with no invalidation or bookkeeping written by its author. Then he asked for a learning phase in which ideas for using it are tested at the REPL to find the best solutions, confirmed with agents to make sure it works, and iterated if needed. Then (21:40) he ruled that components can be rebuilt to take proper advantage of the architecture and minimal rerunning. A component is rewritten for the core, not wrapped: it reads exactly the attributes it shows, a list renders one child per entity keyed by id, and shared derivations are tracks; the measure is that a commit reruns only the components that show what it moved. Each move onto the core is first tried at the REPL on real data, measured beside its estimate, and confirmed by a Seon agent using it unaided before it is built. Stated in [the plan](plan.md#the-order) and [render](render.md).

### Algorithmic analysis first; no large overruns in ticks, memory or time

At 21:45 he ruled that all agents do algorithmic analysis first: he wants clear eyes going into projects, and large overruns in ticks, memory or time are not accepted. Every plan states n, the big-O, and the ticks, bytes and seconds expected before any code; every proof carries all three budgets; a measured overrun in any of them stops the work until its cause is found (§3a). Root checks each land message for the three numbers beside their estimates. Stated in `AGENTS.md` §3 and [cost](cost.md#what-is-measured).

### Docs as you go; bad docs cleaned; replaced code removed

At 21:38 he ruled that agents are told to write docs as they go, to clean up bad docs, and to remove code once its replacement is done. Each landing updates the wiki section of what it changed, fixes or deletes any wrong text it finds there, and deletes the code its replacement made redundant, named on its `Deletes:` line. Stated in `AGENTS.md` §3 and §7.

### Restart when needed; fix what made an update impossible

At 21:50, on root's render step stuck since boot on old code, he ruled against dogmatism: if a mistake means a restart is needed, restart; warn the agents and fix the problem that made updating the environment impossible. A Lisp is a live runtime, and evaluating and syncing code to bring it up to speed is not wrong as long as it is structured safely. A restart is allowed, with the agents warned first; the cause of needing it (here, a step that never ends cannot take a redefinition) becomes a task: every long step ends at its declared tick budget, so the next step runs the new code. Stated in [flow](flow.md).

### Overnight: nobody waits for the owner

At 21:40 he ruled that root goes autonomous and neither it nor any agent waits for him: whatever is known to be the work is done, there being a lot of it, learning as it goes, and revisiting ideas is fine once better symmetries are found. Root decides what would have gone to him and lists each ruling for him to reverse; sessions ask root. Stated in [sessions](sessions.md#the-managing-session).

### Runtime owns the reactive core; context drives agents end to end

At 21:20, asked whether the reactive work should go to the area expert, he ruled that it fits runtime, and that context resumes its experiments with agents and gets tasks finished and merged end to end. Runtime owns `seon.ratom` and every move onto it (the feed from commits, the stress proofs, the API's ease, the learning phase's trials), with flow reviewing the Flow side; context owns what an agent reads, Stage 0 and the random rounds. Then (21:35): context builds the whole context pipeline, html and model outputs, on the reactive render with runtime; context finishes Stage 0, and root drives the random rounds; web-2 builds its render pieces (proc, walk entry, health tracks, server and pages) to context's design. Clarified: web remains fully involved, and the point is only that someone familiar with the system writes the first pass: context writes the first pass, web reviews and builds alongside. Stated in [sessions](sessions.md#sessions-own-their-areas).

### After boot, only Flow; one specialist ports every holdout

He ruled that there are no timers: anything that needs a timer is ported to work with Flow. Then he narrowed it to no timers once the system has booted: once the Flow system is up and the core platform is there, only Flow-based systems are used for stateful processes, and any holdouts are ported; since enough implementations have been done, one agent specializes in their use and handles all related work requests. Boot is the one exception; after it, every stateful process and every wait is a Flow proc, its deadline a message from the one clock (`seon.step/put-after!`). Flow, the Flow specialist, owns every port. Stated in [the plan](plan.md#the-order). Later (20:45) he ruled that once the system is booted and Flow is running seriously, timer use is questioned: waiting is done on channels, for proper backpressure and flow control. A deadline, even `put-after!` at an edge, must show why no channel can say the outcome; a wait on a process of our own (a peer root, a child, a proc) ends by that process's message, its answer or its own deadlock, never a deadline. Stated in [flow](flow.md).

### Hand-made lists: case by case

He ruled that hand-made lists are judged case by case: whether to keep the list or fix at its root the problem it solves. Dials such as max-workers and the other config settings are there for a reason. A dial is kept as spec'd config; a list patching a missing mechanism is fixed at its root; a list restating held data is derived. Stated in `AGENTS.md` §5.

### No functionality removed; dependencies defined as data and validated

He ruled that no functionality is removed through these changes: the goal is to make all dependencies clearly defined as data and validated, not to break the system. Every `Deletes:` line names, per item, what now does its job. Stated in `AGENTS.md` §3.

### Explicit, not deleted

Seeing the max-workers, max-load and load-average keys being removed, he said he wants them: he is not asking for features to be deleted, but for them to be explicit instead of implicit in the code. Deduplication deletes the second mechanism, never a feature or a knob the owner uses; a knob becomes a spec'd config entry the one mechanism reads. Any key or feature a change removes is listed to root (and to the owner, as an audit) before it goes. Stated in `AGENTS.md` §3.

### No hand-made lists; a list that must stay is spec'ed config

He ruled that all hand-made lists go, as a decree: every hand-made list must justify itself, and each must be a clearly spec'd config option. Stated in `AGENTS.md` §5.

### A merge takes everything that does not conflict

On `seon.merge/carried-platform-attrs`, the list of what a merge carries, he asked why everything that does not conflict with main is not merged, since everything else is hand-written lists and data that is not true should not be recorded: facts are facts at a certain tx time. The one merge takes every datom main wrote that the branch did not also change at the same entity and attribute; only that conflict stops it; no attribute list decides. Stated in [the plan](plan.md#the-order).

### Seon-centric only; Claude sessions are not special

He ruled that all solutions are Seon-centric from now on, and that agents do not work in git worktrees as the model. Data is imported into Seon's db when an update is written to a file, so a simple wrapper around the hooks inserts updates into the database, and Seon procedures with Seon designs handle it from there. The db is the source of truth and is queried for truth, never reading git as a secondary truth. Fixes are written to disk and checked into git without ceremony or complex machinery. The focus is designs that work within Seon, with no solutions outside it, and he is to be told whenever one is seen. Then: Claude agents are not special and fit into this system with the same tools Seon agents have. Claude agents may keep using worktrees, but each has at most one branch, mirrored in Seon, and works more within the REPL and through thin scripts that translate its file updates when it cannot work efficiently in the REPL like native Seon agents. On bin/land: finish what is in flight, then freeze. On the existing system: the plan is to replace these wrong solutions, which have plagued the project since the start, not to throw out the existing system. Stated in `AGENTS.md` §0 and [the plan](plan.md#the-order-of-work-now).

### A fix deletes the other implementations

He ruled that a fix deletes the other implementations; that is how the codebase shrinks and becomes better, faster and more correct. Stated in `AGENTS.md` §3.

### Reference code needs no asking

He ruled that checking out reference code into reference-code/ never needs asking, and that reading good Clojure code is always encouraged, since it helps correct mistakes in our own code. Stated in `AGENTS.md` §3 (Learn).

### No new process before the existing one is found

He ruled that no new process is created until it has been verified that no existing solution needs to be updated or deleted by the implementation, and that this is a standing rule for all agents. Stated in `AGENTS.md` §3 (the gate).

### A session lands when green; agents merge themselves

He ruled that each agent has one worktree and one branch, with sub-helpers sharing the same branch: no more hundreds of tiny branches queued for hours so that nothing merges. Agents work things out on their own and ideally self-merge, through the Seon system, so some more components may be waited on for the full flow tests to work, which are prioritized. Chosen: a session runs `bin/land` when its check is green, with no release from root; root reads each landed diff. The components that let `seon_done` carry it (proving as Flow) go first. Stated in `AGENTS.md` §3 and [the plan](plan.md#the-order-of-work-now).

### Overruns: 2x audit, 3x reset

Chosen 2026-11-14 after a root session went badly and was restarted to audit the true state of things: each item a session works has an expected finish; past 2x root reads the session's transcript and corrects it; past 3x, or on a repeated wrong turn, the session writes its state and a fresh one starts with an audit brief. Stated in `AGENTS.md` §2.

### Bounds are worked out, never waived or restated from a run

On a cost proof whose estimate was restated from measurements three times and then waived, he ruled that the reason be found and the authors pressed to justify the bound algorithmically and to think about the problem differently, since not knowing the bounds is not acceptable. A cost proof's estimate is the lower bound as arithmetic; no `:overrun` waiver; past 2x the design is what changes, or the estimate's missed work is named. Stated in `AGENTS.md` §3a.

### Config changes reach running procs live

He ruled that Flow processes can be updated live and a config update reaches all relevant branches live. Where branches have their own overrides, the agents are messaged and instructed to merge the config changes into their branch. Stated in [the plan](plan.md#the-order-of-work-now).

### Growth: one shape, processed once

He ruled that no arbitrary amounts are set. Instead, look for duplication in the data model and then for duplication in processing the data model, and fix the two so everything is processed once and done well. Stated in `AGENTS.md` §3 and [the plan](plan.md#the-order-of-work-now).

### The landed change enters the store through the one import

He chose the one import path, as the Seon-focused solution. `bin/land` tells root main's new commit; root imports it with its one import and answers `:restart` for a pin or binary change; the bridge's code string goes. Stated in [the plan](plan.md#the-order-of-work-now).

### The backlog is curated before a random round

Chosen 2026-11-14: duplicates (same evidence or proof) merged and superseded tasks deleted before the next round. Stated in [the plan](plan.md#the-order-of-work-now).

### One merge: everything that doesn't conflict, both directions

On why a merge of main kept main's task lifecycle off a branch, he asked why everything that is not a conflict with main is not merged, since everything else is hand-written lists and data that is not true should not be recorded: facts are facts at a certain tx time. On merging a branch's records into main: if there are no conflicts there is no reason not to merge everything. Then he ruled that the merge does not happen while there are conflicts, and that merging costs are incremental. Every fact merges by its key in both directions, a conflict stops the merge, and the merge reads only what moved since its base. It supersedes "an agent's own records never merge" (merging.md's reading of the 2026-11-04 ruling; branches are still kept and queried). Stated in [merging](merging.md#a-branch-merges-main-in).

### The priority: Claude sessions work through the system

He ruled that the work optimizes for fixing the broken processes that slow things down and for moving more of the system off the file-based, git-focused standpoint; Claude agents work through the system, and the focus collapses to that one view. He then added that useful parts of the plan are not deleted: the instruction was to prioritize. Stated in [the plan](plan.md#stage-1-claude-sessions-work-through-the-system-the-priority).

### A rule is removed only with its reasons

He ruled that more engineering rigor is encoded into the processes, and that flattening AGENTS.md should rarely be proposed without clear reasons: the same problems are solved again and again because institutional knowledge is wiped. A rule goes only with the incident or ruling behind it, what holds it now, and why it no longer earns its place. Stated in [the plan](plan.md#throughout).

### Tests: the harness does the thinking

He ruled that every test needs a justification for why it cannot fork main or a specific branch, and that only tests that boot the system to the running state can even be considered for a non-online test. The configuration being forked from does not matter: part of an agent's environment setup is updating the runtime, so this is routing, made so that it cannot be got wrong. Test fixtures handle all the thought, so that agents cannot write poor tests. A test is an assertion; the harness gives it a fork on its branch, set to that branch's environment at start, warm fixtures before timing, a budget from its declared estimate, writes to its own branch only, and a mutation check; booting a system is allowed only to a test of booting, with its reason. Stated in [the plan](plan.md#stage-1-claude-sessions-work-through-the-system-the-priority).

### The system operates via Flow; no gates or fences

He ruled that there are no gates or fences except at boot or at an edge where the reason they are required is clearly understood; the system operates via Flow, which should fix the load and processing problems. After a merge gate outside Flow (a git worktree, a rebuild, a hand-set worker count) booted ~74 test roots at once. Work, proving included, flows through Flow procs and the effects owner, admitted by bounded channels; a check exists only at boot or at an edge, with its reason stated. Stated in [the plan](plan.md#stage-1-claude-sessions-work-through-the-system-the-priority) and [principles](principles.md#5-boring-by-construction).

### Bootstrap checks once; runtime guarantees

He ruled that there is a bootstrap phase and then a runtime phase with a set of guarantees that the core system is running properly: proper startup checks are welcome, layers of guards and fences are not. A process proves at its one start that it holds what its branch names; past startup nothing re-checks it. Stated in [principles](principles.md#5-boring-by-construction) and [the plan](plan.md#stage-0-one-happy-path).

### A leaked environment is made impossible, not checked

On the test child that inherited root's state three times, he said this is a root cause that keeps being patched, and asked how it is even possible and how to make it impossible rather than adding another check. A test child forks only from a process already on its branch's environment. Stated in [the plan](plan.md#stage-0-one-happy-path).

### Store what will be read back: pull first

He asked why enough data is not stored to query for what is needed later, and ruled pull first: data is stored optimized in the way it will be read back. A result holds what its run read, where it ran and its status, so reuse and diagnosis are queries. Stated in [the plan](plan.md#stage-1-claude-sessions-work-through-the-system-the-priority).

### Any random task, however unspecified

He ruled that the goal is for agents to handle any random task even when it is unspecified: context has reactive renders that tell the agent the task is not specified, so the specification is the first of the proofs. If an agent does not solve a task but improves it, that improvement is synced to disk if one of the Claude agents reviews it. With enough iterations all tasks can be taken from beginning to end. Stated in [the plan](plan.md#the-goal).

### Approved designs are tasks

An approved, unbuilt design becomes one task whose plan holds it, one task for each design he approved. Stated in [the plan](plan.md#stage-1-claude-sessions-work-through-the-system-the-priority).

### A reasoning overrun is an error the next turn reads

He ruled that a reasoning overrun is an effect that triggers an error, which the system interprets for the agent on its next turn, and asked whether a render function can display all recent errors and their causes. Stated in [the plan](plan.md#stage-0-one-happy-path).

### Failure causes are verifiable tags

He ruled that failures start being classified, with schemas as sets of truths: several classes can be true at the same time, like verifiable tags. Stated in [the plan](plan.md#stage-1-claude-sessions-work-through-the-system-the-priority).

### Blockers are tasks; sessions message the owning session directly

Set the night of 2026-11-13 (his words not recorded), after many back-and-forth messages; it replaces "sessions talk to root only" (2026-11-12). On 2026-11-14 he ruled that the board, the ledgers and however root communicates with its agents are folded into Seon from a db-centric standpoint: agents acting within Seon need to get work done, with the wiki and tasks first. A blocker or a bug in another area is a task in the owning area's namespace, named in the blocked task's `:seon.task/needs`; a message goes to the owning session only to block, unblock or agree an interface, in 6 lines or fewer, with evidence and one ask, one thread per problem, no acknowledgements. Root keeps releases and the owner's decisions. Stated in `AGENTS.md` §2 and [the plan](plan.md#stage-1-claude-sessions-work-through-the-system-the-priority), which folds the board, the ledgers, the landing queue and the turns ledger into tasks, queries and this page.

### Main is pushed to GitHub

He ruled that main is pushed now so nothing is lost, after README.md says plainly that a major refinement is under way (2026-11-13, as recorded by root). Root pushes main to `github.com/seantempesta/seon` after landings; no other branch of this repository is pushed; a pull request upstream is a draft, opened only with his yes. Stated in `AGENTS.md` §3 and §8.

### Worktrees are temporary; Claude sessions become Seon agents on branches

He said (2026-11-13) that the worktrees and the rest are a hack for Claude agents, and proposed that agents work on branches, so the merge and testing mechanisms are deepened just as real agents will use them. Sessions move onto branches of the one system after 2 or 3 real tasks have merged end to end, one area first. Stated in `AGENTS.md` §3.

### Docs changes are proved by reach

He ruled (2026-11-14; earlier, to prove by reach and narrow later) that the earlier exemption was his mistake: docs are indexed and trigger checks. A docs-and-tasks change reruns every test that reads the changed docs, as any change does; a docs landing broke main (4af7a1f2ae) when `seon.repl-test/the-template-instructions-and-the-help-page-run`, which reads the help page, did not run; narrowing to the tests that read the changed section follows. It replaces "a docs-and-tasks change is never proved". Stated in `AGENTS.md` §3 and [tests run by reach](source.md#tests-run-by-reach).

### Process fixes from the retrospectives

Approved with the retrospectives' working theory: every claim is marked measured or hypothesis; `bin/land --check` before any release ask; a landing's `Deletes:` line names what it deletes (a proof that the old path is gone, per the plan's Stage 1), and a replacement lands with its old path deleted; a session updates its resume note before any landing or long run. Quote not recorded. Stated in `AGENTS.md` §2 and §3, and in [principles](principles.md#7-say-what-you-know-and-say-it-when-it-is-wrong).

## 2026-11-13 rulings

The owner's rulings of this day, each as his meaning and where it is now stated.

### The number one metric: turns from a roughly specified task to merged code

He ruled that the number one metric is minimizing the number of turns it takes to go from a roughly specified task to completed, merged code. Before that, he had explained that landings per hour was meant to pick up velocity, but that he does not want it as a general measure, since it rewards low quality and many merges instead of smart merges. Landings per hour (root's measure from 11-12 16:30) is retired: it rewarded many small merges of machinery while one task merged in 36 hours. Stated in `AGENTS.md` §1: progress is the turns an agent takes from a roughly specified task to merged code; a task that never merges is a failure, not a low count; work is ranked by the turns it removes.

How it is measured, the same day: tasks are selected at random from the backlog, and if the solution is good (Claude agents review all code until the system can be trusted to review itself) it is fully merged to disk and into main. He ruled emphatically that tasks are not repeated: they are to be fixed, and there are over 400 of them. So the measure is never a fixed set of tasks run again: each run takes a task at random from the backlog that no run has taken, a good solution merges to disk and to main and its task is done, and Claude sessions review every agent's change until the system's own review is shown trustworthy.

Refined the same day: a single randomly selected task can be iterated on until it is done, and need not be a fresh random choice for every test. Tasks are expected to be badly written or superseded, and invalid ones are removed; Claude agents curate the tasks until a general system works well. The randomness is what prevents teaching to the test. He proposed perhaps 2 or 3 tasks, with agents running in parallel on different tasks in different branches, learning from the groups. So a round draws 2 or 3 backlog tasks at random, runs agents on them in parallel on their own branches, iterates on each until it is done, and learns from what the group shares; Claude sessions curate the backlog as they go, rewriting a badly written task and deleting one that is superseded or no longer valid.

## 2026-11-12 rulings

The owner's rulings of this day, each as his meaning and where it is now stated.

### Plan forward: research and a written plan before a build

At 22:20 he ruled that the broken ones mean there is no clear research or plan in place, so more forward planning is done. That night a `bin/land` change, built before its reach was mapped, broke every landing three ways in turn, and fixes went out for causes nobody had read in code. Stated in `AGENTS.md` §3 (work is tasks): a plan names every path that runs the change; shared machinery runs end to end through each before anything relies on it; a cause is named only after both ends of its path are read.

### Root is the first agent; a child agent is a Flow proc in its parent's graph

Set with runtime, 2026-11-12: root is not special; it is the first agent, and everyone descends from it. A child agent is a remote Flow proc in its parent's graph, linked at the fork; its results are values, and its end is `::flow/closed` and its exit event. Resources are made in a proc's init and released at its stop. The tree of agents is data (parent refs), and the fork server is root's template child, declared as an option. Errors travel only on Flow's error channel; stderr is a dead process's last words. One writer proc owns the store, and every agent's writes are messages to it. Each family of duplicated machinery (process links, tracking, endings, request and answer, waits on main, run loops, socket readers, writers) has one form. Being built: schedule items fork-one, one-ask, one-wait-on-main and one-writer.

### One branch and one worktree per session, never more

He ruled, repeating that he was serious before, that there is only one worktree per agent. Hundreds of tiny change branches queued to run the same tests over and over for trivial changes are not allowed: the same agent working on the same files can build and debug several solutions in the same branch. Helpers work in their session's worktree; nothing branches per change, fix, helper, experiment, bisect or landing; a second branch needs his yes. Stated in [AGENTS.md §3](../AGENTS.md) and [sessions](sessions.md#helpers).

### One unit: everything stateful that runs is a Flow agent

He ruled that anything stateful that is a running process to be kept track of is a separate Flow proc, properly wired into the system for communication, the REPL for debugging, errors, topology and so on. It is the Docker rule: if everything is made the same, and that unit (the container for Docker, the Flow agent for us) is made fast, finely tuned and extensible, it is a building block to standardize on. A test child and a clone are agents too, since tests should have the agent environment as well. A difference is an option of the one unit, never a second path. Atoms seen in use likely need to be ported to Flow. The only processes that are not agents are the fork server, which makes them, and exec'd programs, which are effects through their owner. Stated in [AGENTS.md §3](../AGENTS.md), [flow](flow.md#the-step-fn-contract-every-proc-follows) and [processes](processes.md#the-agent-process).

### Reasoning is on at low effort for work tasks

Asked whether reasoning `:low` should be the default for work tasks, he agreed. It replaces thinking off (2026-10-21). Stated in [model](model.md#thinking-is-on-at-low-effort).

### Dead history-on-scroll page code is deleted

Asked whether to delete `seon.render.ui/more`, the web's /more route and the `:more` ops, which no page used, he agreed. A list holds a window and says how many it left out ([ctx](ctx.md#the-pattern)).

### Design to the lower bound

He ruled that all agents share rules going forward, are methodical, and question how long things should take if they were optimal in a mathematical sense; systems that inefficient are never to be designed again. Stated in [AGENTS.md §3a](../AGENTS.md) and [cost](cost.md#what-things-cost-here).

### AGENTS.md is not an append log

He ruled that AGENTS.md is not a long append log of instructions, since they would not be evergreen. Stated in [sessions](sessions.md#the-way-improves).

## 2026-11-11 rulings

The owner's rulings of this day, each as his meaning and where it is now stated.

### The system goal: the codebase improves itself, by refinement

He ruled that the goal is not radical change but refinement over time. His picks: the first loop closes at the end of a task; a system change is approved by a review task; a review files tasks and never edits what it reviews. Stated in [the plan](plan.md#the-plan).

### One agent mechanism for every role, root included

He ruled that there is one system everywhere, and that different systems are not built for different agents. Stated in [the plan](plan.md#the-plan).

### A layout is a render over the same pulled data

He ruled that the data-driven UI rendering is kept, with no hand-rolled systems, as a design goal. Stated in [the plan](plan.md#stage-5-the-users-system); not built.

### An agent reads what it can read; a question goes to its parent

He ruled that contacting root for things an agent can access on its own stops. Questions go to the parent in the hierarchy. Stated in [users](users.md#what-each-thing-is).

### Design the data for the pull

He ruled that the data model and design are ours to control, so they are designed for the pull. Stated in [data modeling](data-modeling.md#before-you-add-something).

### An agent may hold a set of tasks

He ruled that the fork and improve sessions reuse the same task system, and that tasks may need to differentiate between starting now and starting when the system is free. Stated in [the plan](plan.md#stage-4-agents-finish-in-few-turns), stages 4 and 5; not built.

### Tasks chain by needs

He ruled that nothing named "example" or the like is hard-coded when tasks are turned into the OpenAI-compatible formats. Stated in [the plan](plan.md#stage-4-agents-finish-in-few-turns), and [tasks](tasks.md#a-task-names-examples).

### The data model is worked through live

He asked whether a session is just an agent, in which case session-open and example-open are not needed, and asked what the next step is that will strain the data model. Stated in [the plan](plan.md#stage-5-the-users-system).

### A message's turn is not asked

He ruled that, in general, a message should not depend on which turn it arrived on. Stated in [the plan](plan.md#stage-5-the-users-system).

### Ids are unique across branches; order by time, not by transaction id (owner, 2026-11-11)

He ruled that all IDs are unique among all the branches and that time, not transaction ids, is used for ordering. He withdrew unique across stores, and a transaction's time is not made its id. Order by `[:db/txInstant, tx id]`. Stated in [storage](storage.md#history) and [data modeling](data-modeling.md#a-ref-or-a-value).

### A new session starts at the commit at or before a point; a read is exact at any transaction (owner, 2026-11-11)

He approved the approach, to be built on. A read at a branch and transaction is exact; a new session forks the commit at or before it, derived, never stored. Stated in [turn](turn.md#an-agent-goes-back-to-an-eval).

### Root is the agent env at the top

He ruled that nothing the system does without an active env is called root. Root is the agent env at the top of the hierarchy. Stated in [vocabulary](vocabulary.md#words-we-do-not-use).

### A sync merges everything that does not conflict

He ruled that there is no hand-made list of attributes to sync: all non-conflicting data merges, and conflicts are fixed before anything else is merged. Stated in [the plan](plan.md#stage-5-the-users-system); not built.

### An unread attribute gets a render or goes

He ruled that an attribute with no purpose is removed. Stated in [data modeling](data-modeling.md#before-you-add-something).

### Messages are ordered by when they arrived, from the pull

He approved landing it. Stated in [ctx](ctx.md#the-pattern).

### Root's names change with the erase

He ruled that the rename comes later, with the erase. `bin/root`, `seon.root`, `my.root` and `:seon.root/*` are renamed together at the erase.

### A merge replays the source's transactions

He ruled that two transaction times are never recorded as the same. Each source transaction becomes a new, later transaction of the receiving branch. Stated in [the plan](plan.md#stage-5-the-users-system); not built.

### No derived value is stored

He ruled that derived values are not stored, and clarified that he meant the all-good verdict being run on the db state. A measured observation is a fact under an identity that names its observer. Stated in [data modeling](data-modeling.md#derived-data-is-a-cache-never-relied-on).

### Work, then refine, then review

He ruled that refinements can then be tested without losing the previous one, because the db keeps history. Stated in [the plan](plan.md#stage-4-agents-finish-in-few-turns); not built.

### Reviewed sessions are kept in the repository

He ruled that reviewed sessions are kept as a general import area, like tasks, in the repository. Stated in [the plan](plan.md#stage-4-agents-finish-in-few-turns); not built.

### Measures accumulate from use; a measurement is one call

He ruled that results accumulate and become more precise over time, naturally, as they are exercised in the system. Stated in [cost](cost.md#how-we-measure) and [the plan](plan.md#stage-4-agents-finish-in-few-turns).

### Before a compaction a session reports up

He ruled that before they compact, all agents reflect on the work they did and report to their parent, so that drift stops over time. Stated in AGENTS.md §2 and [sessions](sessions.md#the-way-improves).

### Live agent runs harvest problems

He ruled that the system does not teach to the test, recognizes systemic failure and adapts over time; and that if a Claude agent reviews the work and finds the additions good, they are synced to disk. Stated in [sessions](sessions.md#live-agent-runs-find-the-systems-problems).

### Embeddings stay in the store

(Owner's answer, 2026-11-15.) He withdrew his earlier request to move embeddings out of the store; the store holds them. Stated in [vector](vector.md#a-vector-is-its-own-entity).

### Check-step bounds are signals, not hard constraints

He ruled that step bounds are signals, not hard constraints. Stated in [merging](merging.md#a-merge-runs-what-the-change-reaches). For a test or step ENDED past its bound, overturned on 2026-11-12: a test killed for running too long is a bug, and is recorded as an error and fixed; it must not take down the system, and doing nothing and recording nothing is not acceptable. Stated in [clocks](clocks.md#time-decides-the-owners-exceptions). A bound still never decides when an item starts or waits ([config](config.md)).

### `bin/root` becomes `bin/system`

He ruled that `bin/root` is renamed to `bin/system`. Stated in [vocabulary](vocabulary.md).

### When lines of work collide: proving, the product loop, renders as data, Jolt fiber safety (owner, 2026-11-11)

He ordered the lines of work: proving through the system, then the product loop, then renders as data, then Jolt fiber safety. Stated in [the plan](plan.md#the-plan).

### A stuck agent is stopped and analyzed

He ruled that a stuck agent is stopped, analyzed and fixed, since repetition is a problem with our context and instructions. To be stated in [agent](agent.md).

### The system message is only for what every agent is told

He ruled that the system context is only for universal explanations that every agent in the system should get, and that any begging or pleading is removed. To be stated in [model](model.md).

### The Data model session is in charge of schemas and pull patterns

He ruled that solutions are based on data, so the Data model session is consulted. Stated in [sessions](sessions.md#sessions-own-their-areas).

### The page reaches the model as evals, at commit points

He ruled that the `@ctx` and the hiccup are shown in full at some point in an agent's evals. To be stated in [ctx](ctx.md).

### The model reads data, and the render's output as hiccup data

He ruled that the model is shown data, not rendered html (the model gets the version with CSS stripped out and the hiccup as data). To be stated in [render](render.md).

### The website renders the hiccup by default

He asked whether the website can render the hiccup by default. To be stated in [render](render.md).

### A diff is an editscript, and a delete is simple

He ruled for editscript diffs and, he thinks, simpler deletes, since the agent already has the data in its previous context. To be stated in [render](render.md).

### A pull pattern is stored with the agent

He ruled that the pull pattern is stored with the agent, and the agent renders it so that it knows about it. To be stated in [ctx](ctx.md#the-pattern).

### A render may read the previous point's tree

He ruled that a render may read the previous point's tree. To be stated in [render](render.md#what-a-render-is-handed), which says "never" today.

### A render's output has rules, but its content is not constrained

He ruled that the page hiccup can have rules, but its content is not constrained. To be stated in [render](render.md).

### A text field is Markdown

He ruled that, in general, a text field is assumed to be Markdown. To be stated in [render](render.md).

### Break the agent's physics and the agent breaks; keep auditing

He ruled that auditing continues: failures are found and fixed. To be stated in [agent](agent.md).

### Reasoning is a development microscope

He ruled that the goal of reading the reasoning is to understand what the model finds confusing, then to fix that and demo it with no reasoning. To be stated in [model](model.md).

### Disregard an LLM's estimates of time

He ruled that all LLM estimates of time are disregarded: they are always based on human output, and the work will be reached much sooner. Stated in [sessions](sessions.md#the-managing-session).

### Fibers take the safe paths; a guard is a finding

Of 11 ticks an element instead of 8, he said to pay it. On the guard, he ruled that it may be used to find the issue, but the classes of problem are eliminated so that the guard is unnecessary. Of the tip's +18% bytes, he agreed. Stated in [forks](forks.md#jolt).

### Runs are throttled by load

He ruled that runs are load-based and throttled through the Flow channels. To be stated in [machine](machine.md) and [config](config.md).

### Health checks watch the queues and write to health/problems

He ruled that good checks are scheduled to be added when the health watch is deployed, and that it writes to health/problems. To be stated in [health](health.md).

### The standing system and its store are for use; erasing it stays his

Hearing that the database was treated as read-only, he ruled that it is not: it is used. Stated in AGENTS.md §0 and [sessions](sessions.md#sessions-own-their-areas).

### The test suite is for the online version, in the db, and the workflow is the agents' (owner, 2026-11-11)

He ruled that the system is designed for Seon agents, or Claude agents using the MCP tools. Not yet stated in the wiki.

### Worktrees stay for real work

He ruled that a session keeps being pushed through the system and used to mine problems, without slowing down real work. Not yet stated in the wiki.

### Seon's own calls carry an intent and a good model message

He ruled that Seon's own calls carry an intent too. The tool is named "repl". Stated in [render](render.md) and [model](model.md#one-tool-repl).

### Seon's calls are stored evals, and deterministic

He ruled that Seon's calls, being evals, are stored with their results like any other eval, and that they are deterministic and work. Stated in [ctx](ctx.md); the calls as stored evals are not built.

### A render shows that context exists and how to pull more

He ruled that agents must know that context exists, with explanations and short instructions they can use to pull in more of it. Not yet stated in the wiki.

### An agent's entity holds what is present; a render derives the rest

He ruled that the Data model session audits which data must be present on an agent's entity and which can be derived in a render function. Not yet stated in the wiki.

### The first point shows `describe` of the context

He ruled that agents are taught a general skill, learning to fish on any other value, instead of being handed each case. The function is `describe`. Not yet stated in the wiki.

### An agent works in its task's namespace

He ruled that it must be clearer that agents are set up in their task's namespace, since context is automatically pulled in from that namespace. Not yet stated in the wiki.

### Every state converts to a completed task

He ruled that every state is convertible to a completed task. Stated in part in [tasks](tasks.md#a-refine-curates-its-own-task).

### Proving is one call on a reference: fork main, load that point's program whole, run its proofs (owner, 2026-11-11)

He ruled that forking main and loading all the code in the system in the right order in one eval gives something close to the full build without building it from scratch, and that both are built so both exist. Stated in [the plan](plan.md), part 5; not built.

### The whole load while the owner is away

He ruled that, while he is not using his machine, the target is the full load of all cores, and that once the test system lands the config is updated to run on load. The key is `:seon.config.proofs/max-load`.

### Spec more work than fits, and reshape what does not fit

He ruled that more work is specified than fits and that the work keeps being refined with agents: a piece that does not fit, a square peg for a round hole, is reshaped, not accepted as it is. Stated in [sessions](sessions.md#the-managing-session).

## 2026-11-10 rulings

The owner's rulings of this day, each as his meaning and where it is now stated.

### The store may lose its last commits, never corrupt or exhaust the machine

He ruled that losing data is acceptable, but exhausting semaphores and corrupt storage are not. Fixed in our LMDB fork. Stated in [failure points](failure-points.md#lmdb-locks), [failure points](failure-points.md#lmdb-crash-safety), [storage](storage.md#durability-is-continuous-group-commit) and [forks](forks.md#lmdb).

### A run far past its measured time is ended, after its evidence (owner, 2026-11-10)

The fourth clock exception: a script may end a run far past its measured time, after capturing its evidence, reported red with a typed reason and never retried. Stated in [clocks](clocks.md#time-decides-the-owners-exceptions) (`:seon.config.check/step-end-factor`, STEP-OVERRAN, `bin/evidence`).

### A write to main is answered after the store holds it (owner, 2026-11-10)

He chose answering after the store commit over answering at apply: answered means every process can read it. Stated in [storage](storage.md#durability-is-continuous-group-commit).

### The best known way, improved

He ruled that pain points can become well polished and intentional features. Stated in AGENTS.md §1 and [sessions](sessions.md#the-way-improves).

### Small changes land in batches, proved once

He ruled that completely separated work, each change running all the tests separately, is not wanted. Stated in AGENTS.md §3 and [merging](merging.md#the-managing-session-lands-a-batch).

### A task a Seon agent finished reaches main by its owner's review

He ruled that if a task is almost done right, it is simply fixed, synced to disk and the task removed. Stated in [source](source.md#a-merged-task-reaches-mains-files).

### A root catches up with its checkout at start

(Owner's answer, 2026-11-15.) This concerned system tests that need a replacement binary: build it, test it, switch over, and catch up by replaying the program's diff in one eval (see 2026-11-15, "A new binary runs in parallel, is tested there, then takes over"). Stated in [source](source.md#a-boot-catches-up-with-its-checkout).

### Pages are built from data by one walk (owner, 2026-11-10)

He ruled that hand-drawn lists and custom code stop: root, the user and normal agents differ by their data and their connections, not by separate special code. He added that nothing is flattened, since the location in the pull pattern has meaning. With it, root's agent is made at boot and an example session reaches the model as real messages. Stated in [render](render.md).

### The forks follow upstream: theirs when almost as good, ours written to be offered (owner, 2026-11-10)

He ruled that where there are two solutions and theirs is almost as good as ours, theirs is chosen. Stated in [forks](forks.md#following-upstream).

### Every session proves its branch before handing it over

He ruled that sessions ideally make the entire suite run very fast, so every agent can fully check its work. The command is `bin/prove`. Stated in AGENTS.md §3.

### A test that starts a root takes what root runs (owner, 2026-11-10)

He reversed 2026-10-27's rule that a root's boot is no entry: a process test's digest takes what root runs. Stated in [source](source.md#tests-run-by-reach).

### The test system runs inside Seon; the outside is thin wrappers (owner, 2026-11-10)

He ruled that the test system is focused on Seon agents running it from the inside. A digest takes the code of the forms a test reaches, without docstrings. Stated in [source](source.md#what-a-test-takes-of-a-forms-text) and [merging](merging.md#a-merge-runs-what-the-change-reaches).

### The test service runs what the cores hold

He ruled that shards are no longer used (see the word's entry above), and that timers are never used where blocking channels can be set up. Stated in [merging](merging.md#the-test-service).

### A change that moves every verdict is run whole once

He ruled that the next time a change invalidates all the tests, it is run once, so the other runs do not duplicate all the tests. Stated in [sessions](sessions.md#the-managing-session).

### No agent on the costliest model without the owner's yes

He ruled that no Fable agent is launched without his approval, since he cannot afford it. Stated in [sessions](sessions.md#no-fan-out).

### A test earns its place or is cut

He ruled that all low-value tests are cut. Stated in [testing](testing.md#a-proof-is-focused).

### Load is judged by cores and free memory

He pointed out that the machine has 18 cores and its load was 8, so there was headroom. Heavy runs go niced and in parallel while nothing is starved; the 16 GB MacBook stays the target. Stated in AGENTS.md §8 and [machine](machine.md#builds-and-checks).

### The agent's history is native, and what is looked up goes through the hiccup (owner, 2026-11-10)

He ruled that the agent gets raw information, its messages and eval calls, in their native format, and that anything processed or dynamically looked up goes in the hiccup render. Stated in [render](render.md#what-the-agent-reads-is-its-native-history-and-everything-else-through-the-hiccup).

### Private helpers: tests only (owner, 2026-11-10)

He ruled that private helpers are checked in tests only. A private function's contract is checked at its definition, in tests and in the gate, not on each production call. Stated in [checks](checks.md#contracts-armed-at-definition).

### A read never writes

He ruled that a read never writes. Stated in [turn](turn.md#an-outside-agent).

### Three request cost proofs land waived (owner, 2026-11-10)

He ruled that the change lands now, with three waivers. Each carries an `:overrun` naming `seon.render/a-model-render-draws-each-turns-diff`; no bound moved. Stated in [cost](cost.md#an-accepted-overrun-and-its-ratchet).

### An entity is held at every path the pull reaches it

He judged the pain point to be self-inflicted, with a simple solution. `seon.ctx/build` holds a joined entity whole at every path its pattern pulls it, as `d/pull` does. Stated in [ctx](ctx.md#the-pattern).

## 2026-11-09 rulings

The design of one system of agents agreed that day is stated in [users](users.md#one-system-of-agents-the-design-agreed-2026-11-09) and staged in [users](users.md#the-stages-agreed-2026-11-09).

### No hacks: adjust the system

He ruled that hacks are not wanted; the system is adjusted so it works properly (wording unverified). Stated in AGENTS.md §3, the method, step 4.

### Understand before writing

He ruled that data is not written until it is understood. Stated in AGENTS.md §7.

### Questions go through the question tool

He ruled that questions an agent is awaiting answers for are asked with the question tool (wording unverified). Stated in AGENTS.md §2 and [sessions](sessions.md#asking-the-owner).

### A red verdict is never deleted or moved

A fixed tree gets a new digest; a red stuck at a digest that cannot move is a gap in the digest. Stated in AGENTS.md §3 and [merging](merging.md#verdicts-are-a-cache).

### One system of agents, and no new concepts

He ruled that one system is designed for agents and reused everywhere, and asked that the building blocks reused everywhere be explained, along with how the data and the pull make each part different. Stated in [users](users.md#one-system-of-agents-the-design-agreed-2026-11-09).

### A page is the pull drawn recursively; no layouts

He ruled that there is no layout-of and no `seon.render/layout`: all the manual layout work is removed. A hiccup render can override anywhere the walk is rendering recursively, and entities have renders if and only if they are to be rendered. Stated in [users](users.md#one-system-of-agents-the-design-agreed-2026-11-09).

### Separate definitions, linked by refs; accretion

He ruled that Clojure and Datomic are designed for accretion: if the data is there, it is validated. There are no rows. Stated in [data modeling](data-modeling.md#things-relate-by-refs).

### An agent renders its own context; the whole render each time

He ruled that since everything is sent every time, intermediate renders may be dropped, and that render functions are efficient, smart and always focused on showing the right data. Stated in [users](users.md#one-system-of-agents-the-design-agreed-2026-11-09).

### The user is an agent with a branch

He ruled that the user has a process regardless, and a branch just like the other agents. Stated in [users](users.md#one-system-of-agents-the-design-agreed-2026-11-09).

### A running system runs off the db; boot resumes from it and reads no file (owner, 2026-11-09)

He ruled that from that point on data is synced to files, not read from them, and that re-indexing is of course possible if wanted. Stated in [source](source.md#a-boot-resumes-from-the-db) and [source](source.md#indexing-a-tree).

### Claude sessions' landings enter the db as data, for now

He ruled that Seon agents write updates inside the system and push them to disk. Stated in [users](users.md#the-stages-agreed-2026-11-09).

### Docs link to code, never copy it

He ruled that code is not put into docs when the docs can link to it. Stated in AGENTS.md §7.

### Audit before merging; the code gets smaller; no heroics

He ruled that an agent finding itself needing heroics and a great deal of code is doing the wrong thing, and the design is fixed. Stated in AGENTS.md §3.

### One pass over the pull renders the page and the model's context

He ruled that once it is known what something is, finding its render function is just a db query. Stated in [users](users.md#one-system-of-agents-the-design-agreed-2026-11-09).

### What an entity is: the best match wins

He ruled that when an entity is several things, the best-matching one wins. Stated in [users](users.md#one-system-of-agents-the-design-agreed-2026-11-09).

### Render functions take what they read; the cache is never relied on

He ruled that there are no magic functions, and asked whether this works even without the cache, which he expects to be fast. Stated in [users](users.md#one-system-of-agents-the-design-agreed-2026-11-09), item 4.

### A session syncs by default

He ruled that agents can be private (experimental, not syncing with main the whole time) and that there is an auto-sync feature, likely the default behavior. Stated in [users](users.md#the-stages-agreed-2026-11-09).

### The user agent generates the page; root relays and renders nothing

He asked what root has to do with anything if the user is the one generating the html. Stated in [users](users.md#the-stages-agreed-2026-11-09), stages 1b and 4.

### A message is delivered when it is on the recipient's branch

He proposed that a message is simply delivered, and that its being on the agent's branch is the proof of delivery. Stated in [users](users.md#the-stages-agreed-2026-11-09).

### A cite is a ref to what exists; an opened eval reaches the model

He ruled that logically it does not make sense to cite something that is not there, so a cite is a ref to what exists; and of an opened eval, that the model reads it too. Stated in [users](users.md#the-stages-agreed-2026-11-09).

### A private session takes only what main held when it branched

He ruled that a private session takes from main only what is on main at the time it is branched. Stated in task seon.merge/a-session-syncs-with-main-unless-private.

## 2026-11-08 rulings

The owner's rulings of this day, each as his meaning and where it is now stated.

### A session knows its whole area; review is never delegated

He ruled that well-specified tasks can be handed to Sonnet, but the Opus agents are responsible for reviewing the code. Stated in AGENTS.md §2.

### The Web session owns servers and demos

He ruled that the Web session takes over all responsibility for servers and demos for him. Its read-only rule for others was reversed on 2026-11-11 (the standing system and its store are for use). Stated in AGENTS.md §0 and [sessions](sessions.md#sessions-own-their-areas).

### What a new design replaces goes with it

He ruled that what a new design replaces is removed so that it does not keep coming back (wording unverified). Stated in AGENTS.md §3 and [merging](merging.md#a-landing-message-carries-its-audit).

### An estimate that is exceeded stops the work

He ruled that when an estimate is exceeded, the work stops and the estimate is fixed. Stated in AGENTS.md §3, the method, step 2.

### Entity schemas are sets of keys written together

He ruled that accretion is wanted, that duplicate definitions are removed, and that one good definition should catch problems. Stated in [data modeling](data-modeling.md#a-schema-is-a-set-of-keys-written-together) and [storage](storage.md#the-write-checks-every-entity-it-touches).

### The main tree stays on main; each session works in its own worktree

He ruled that the agents file is updated, since the earlier rule was wrong. It reverses 2026-10-28's rule to stop doing separate worktrees for small updates. Stated in AGENTS.md §3.

### In-flight output is one general stream

He ruled that in-flight streaming data can be lost without serious consequence, even if the system is restarted or restored to a point in time. Stated in [render](render.md#in-flight-output-streams).

### One walk per view, the smallest question each step, output streamed live

He ruled that the system does not do a million things when it only needs to check one or two to answer the question, and that the cache is not to be relied on to save inefficient work. Its task was retired for pages built from data (2026-11-10).

### In active development, the store is erased whenever a change needs it

He ruled that the store may be erased whenever wanted, with the other agents warned, and that in production the whole store is of course not dropped. Stated in AGENTS.md §9 and [machine](machine.md#disk-stays-bounded).

### An id is the content of the facts that make it (owner, 2026-11-08)

He ruled that where an ID must be the same for the same content, content-based ID generation is used; where it must always be unique, a UUID, or a content hash based on a UUID. He also ruled one live process per agent. Stated in ```seon.lib/id``` and ```seon.lib/unique-id```.

### A reply is its text and its calls; the message is written when there is something to say (owner, 2026-11-08)

He ruled that if an agent knows what it is doing and updates less frequently, that is fine. Each eval is its own call `{intent, code}`; the reply's text is its optional message. Stated in [one tool](model.md#one-tool-repl) and [the prompt](prompt.md).

### The model's request holds no CSS; an agent reads it where it lives

He ruled that agents are told about their CSS and how they can search it. Stated in [style](render.md#style).

### Every eval of a reply runs (owner, 2026-11-08)

He ruled that every eval of a reply runs, because turns are precious and one failure should not punish the other evals; the evals still run sequentially, but all of them are executed. Stated in ```seon.repl/run-calls``` and [the prompt](prompt.md).

## 2026-11-07 rulings

The owner's rulings of this day, each as his meaning and where it is now stated.

### The db is the source of truth; a disk edit imports only when asked

He ruled that the db is the source of truth and syncing to disk is done from within Seon. A disk edit acts on nothing until a person re-indexes. Stated in [health](health.md#every-writer-the-db-is-written-files-follow) and [source](source.md#indexing-a-tree).

### No magic: maintenance is Clojure data on ctx

He ruled that maintenance is fewer special functions and more Clojure data manipulation on the ctx atom, with no magic in the system. Stated in [users](users.md#messages).

### A wake allowance controls the loop

He ruled that a wake allowance is configured that increases the max turns, and that it is the main mechanism controlling the agentic loop. Stated in [users](users.md#messages).

### Messages have no read state

He ruled that there is no concept of read and unread, which was buggy: messages are delivered to the agent's entity and the agent can remove them. Stated in [users](users.md#messages).

### What's on screen is a reflection of the database

He ruled that the UI state is a reflection of the DB: the db is updated, and that causes the re-render. Stated in [users](users.md#whats-on-screen).

### Only Clojure's, our libraries' and agreed words

He ruled that only Clojure terms, the libraries we integrate with, or concepts we agree on are used, such as git terms for Datahike branching. Stated in [vocabulary](vocabulary.md).

### The system is a function of state in the database

He ruled that this is not object-oriented programming: state is always in the database and the system is a function of that state. Stated in [users](users.md).

### Think in data structures, and never pass a database

He ruled that things are thought of as data structures, that databases are never passed around because there is a data format and schema for them, and that there are no tables in the database. Stated in [users](users.md#what-each-thing-is).

### Immutable first: same value, same id

He ruled that data structures can be reused, that immutable data is thought of whenever possible, and that everything is a fork of a shared base that can be pointed to. Stated in [users](users.md#where-each-fact-lives).

### Fences mean the design is off

He ruled that fences are always a sign the design is off, and that the question is what to simplify to remove the need for guards and fences. Stated in [users](users.md#four-interfaces).

### Lazy, and dead branches cost nothing

He ruled that, in general, the system runs lazily and realizes and does only the work needed at the time it is needed. Stated in [users](users.md#the-merges).

### Git's words

He ruled that git terms are used whenever possible, so agents have an intuitive understanding. Stated in [vocabulary](vocabulary.md).

### A user is an agent, in worlds within worlds

He ruled that a user has refs to all the agents it has started by default, and that merges can fail if main moves under them, which is fine, since the error lets the agent fix it. Stated in [users](users.md).

### A contract checks a collection whole

He ruled that full checks are kept. Stated in [cost](cost.md#what-a-contract-costs).

### The default agent chats, and more comes by threads it pulls

He ruled that agents are given not giant data dumps but data progressively over time, as they need it. Stated in [working for a person](agent.md#working-for-a-person).

### No kinds; a keyword may name a template

He ruled that in general there are no kinds, and that they are generally code smells; a keyword that maps to a template is fine. Stated in [tasks](tasks.md#a-request-is-one-transaction).

### A merge writes itself out while its tests pass, and approval comes later

He ruled that this behavior is configurable and that the user or root approves it. Stated in [source](source.md#commit-the-merges-branch).

### Seon's key files are facts, on one mechanism

He asked whether the indexing is a shared system or another parallel system being built: the schema files' entity and merge are generalised, not copied. Task `seon.source/the-trees-commit-merges-three-way`.

### A user's branch tracks main like an agent's

He ruled that a user's branch tracks main just like the agent branches being launched. Stated in [the plan](plan.md).

### A proof is focused

He ruled that more is not always better: a proof is focused and good at helping determine correctness and that the state is done. Stated in [testing](testing.md#a-proof-is-focused).

### A proof starts simple and evolves

He ruled that a proof generally starts simple, such as responding to the user, and builds up, or merges with templates, to give more correct testing conditions for what success looks like. It is always evolving.

## 2026-11-06 rulings

The owner's rulings of this day, each as his meaning and where it is now stated.

### A well-scoped task is done without asking

He ruled that a well-scoped task is done without asking. Stated in AGENTS.md §2 and [sessions](sessions.md#asking-the-owner).

### Old work retired, not left halfway

He ruled that things are merged, not abandoned halfway. The retired branches and their commits are in git.

### A person's view of an eval's result, and what a repeat is

He ruled that an identical result has the same result id. Stated in [render](render.md) and [turn](turn.md).

### The model's context is a render of the same tree as the page

He ruled that the model's context is a render of the same tree as the page, since separate model and user html renders always drifted. He disliked the name part; it is a component. Stated in [render](render.md#the-models-context-a-cached-prefix-then-the-turn).

### What the model reads is precise data, teaching included

He ruled that what the model reads is presented as data and strings. Diffs are editscript, since he wants the gains.

### Seon's own files move by branch, and an agent has the whole disk

He ruled that file operations will be required for other work, like editing non-Seon code or doing computer maintenance, and that he does not care who fixes the problems on main.

### An eval has an intent

Superseded on the message by 2026-11-08, a reply is its text and its calls; the intent per eval stands: he ruled that each eval has an intent, which forces the agent to think about what it is doing before it does it. Stated in [prompt](prompt.md).

### Design for classes of problems, and look for root causes

He ruled for an elegant design that prevents entire classes of problems, so that finding ourselves hacking things means the design is wrong, and that an agent repeating itself is not waste but a bug. Stated in AGENTS.md §3.

### Sessions work together with no fixed ceremony

He ruled that work is not done twice: if it is done well, it is merged; and that the aim is 90% of the benefit for 10% of the effort. Stated in AGENTS.md §§2, 3 and 8.

### A slow test runs last

He ruled that the slow tests are simply the last to run, with the focus on short-circuiting instead of writing out full reports, and that they are excluded by default until they can run fast. Stated in [merging](merging.md#a-slow-test-runs-last).

### A reply with no calls pauses the agent, whatever its proofs say

He ruled that no more turns run even if the proofs are red. Stated in [prompt](prompt.md), [turn](turn.md#parked) and `seon.turn/act!`.

### A parked agent stays in memory, and replay is deliberate

He ruled that replay is intentional, and perhaps even selective. A parked agent is a blocking wait. Stated in [turn](turn.md#parked).

### Edits go to the database, then to disk when asked

He ruled that edits go into the database, which is the source of truth, and are then explicitly synced to disk. Task `seon.render/an-edit-reaches-disk-when-asked`.

### No regex or text parsing; function composition

He ruled that there is no regex or text parsing: function composition is used. Stated in [source](source.md#a-forms-account-is-the-programs-facts) and [turn](turn.md#an-eval-can-be-run-again).

### An eval run again replaces itself

He ruled that an eval run again replaces (upserts) the existing eval. Stated in [turn](turn.md#an-eval-can-be-run-again).

### The debug page is the wire; the agent's page is where it is friendly

He ruled that the wire format types are shown as they are on the debug page, and that expand expands instead of repeating. Stated in [render](render.md#the-debug-page-is-the-wire-request).

### Styling is a render the agent can read and change

He ruled that the relevant CSS is pulled in as one of the render functions, so the agent can separate styling with context and update it too. Stated in [render](render.md#style).

## 2026-11-05 rulings

The owner's rulings of this day, each as his meaning and where it is now stated.

### Look around before building; no page of doctrine

He ruled that there is no single one way: look around and do not rebuild what is already built, and think and plan instead of just acting. Stated in AGENTS.md §1.

### An agent experiments with a render of its own

He ruled that when agents are experimenting, it is better that they declare their own render. Stated in [ctx](ctx.md#adding-a-datum-and-its-render).

### An agent's entity, pull and renders make most of its context

He ruled that there is no special case for citing: a cite is just data and a render function. Stated in [ctx](ctx.md#what-the-model-reads-and-what-the-page-renders).

### The debug page draws history with today's renders, and says when that differs

He agreed, and asked that it be tried. Stated in [the debug page](render.md#the-debug-page-is-the-wire-request).

### The render is an eval, and what changed is another

He ruled that the rendering of the hiccup is a normal eval injected into turn 0, with diffs when it changes. Stated in [render](render.md#the-render-is-an-eval).

### Large or changing output is the render's bug

He ruled that nothing rendered should be huge or change dramatically over and over: that is a failure of the render functions and of the system, which generates noise.

### Data is entities with attributes, and nothing is quadratic

He ruled that nothing should require N^2, since things are built progressively; if they are not, the design is wrong again. Stated in [data modeling](data-modeling.md).

## 2026-11-04 rulings

The owner's rulings of this day, each as his meaning and where it is now stated.

### No parallel systems (owner, 2026-11-04)

He ruled that agents do not keep building parallel systems. Before adding a second layout, cache, path to the model or store, look at what already does it ([a bug is a class](#a-bug-is-a-class-remove-what-allowed-it-owner-2026-11-03)). Stated in AGENTS.md §9.

### Data is stored as data; the one printer is for context

He ruled that the only value printer is the one value printer, and that it is for assembling context for agents. Stated in [plain data](storage.md#plain-data).

### The standing store is erased when the new schemas land

He ruled that runs/store may be erased when the new schemas are in place. Done 2026-11-05.

### Agent branches are kept and queried (owner, 2026-11-04)

He ruled that agent branches are in general not deleted, and are queried when information is needed on what happened for an agent. Stated in [merging](merging.md#seons-merge-is-a-three-way-merge-of-the-program).

### An agent's data makes its page and its context

He ruled that there is no more hand-crafting: the system operates and works out how to have everything declare itself. Stated in [render](render.md#one-rendering-system-drawn-from-the-data).

### The data's shape is ours, batteries included

He ruled that we have full control, so a system is made that works generally and optimally in one way, and that it is made easier to build things the optimal way. Stated in [data modeling](data-modeling.md).

### Store data for how it is used; a turn is a number

He asked why turns and evals, which an agent has, are organized on top of other entities. Stated in [turn](turn.md#the-turn-is-a-number).

### A render asks for the db and queries what it wants

He ruled that render functions get the current db if they ask for it, so any query can be done and the data need not be reachable from the agent directly. Stated in [components](render.md#components).

### Re-render when the data or its function changes

He ruled that a re-render happens when the data changes or when the function that renders it changes. Stated in [reactive renders](render.md#reactive-renders).

### Delete, compact and reset are data

He ruled for the ability to reset to any point and to delete arbitrary data. Stated in [ctx](ctx.md#deleting-compacting-and-resetting-are-changes-of-data).

### The page is latest only

Replaced on 2026-11-05: the render is an eval, and what changed is another.

### Every agent improves the renders

He ruled that the task of writing rendering functions for data is distributed across the whole system. Stated in [ctx](ctx.md#adding-a-datum-and-its-render).

## 2026-11-03 rulings

The owner's rulings of this day, each as his meaning and where it is now stated.

### A bug is a class: remove what allowed it (owner, 2026-11-03)

He ruled that one is always suspicious about bugs: fixing them directly is usually the wrong solution; find the root cause and see whether something should be done that eliminates the bug class. Stated in AGENTS.md §3, the method, step 4.

### A contract costs nothing on its one interface; the same data is checked once

He ruled that contracts do not count against anything as long as they are used through the optimal interface and the same data is not checked multiple times for no reason.

## 2026-11-01 rulings

The owner's rulings of this day, each as his meaning and where it is now stated.

### Benefits are general; nothing leads an agent to its answer

He ruled that models are not led just to score a win: all benefits are general. Stated in [cost](cost.md#an-accepted-overrun-and-its-ratchet).

### Past sessions are shown as curated sessions

He agreed to the name: curated sessions. A curated session reaches the model as the same tool-call turns its own evals do, never as a text blob. Stated in [the plan](plan.md).

### Context is data a render draws from the db

He ruled that no parallel systems to context generation and rendering are created.

## 2026-10-31 rulings

The owner's rulings of this day, each as his meaning and where it is now stated.

### A test shows its claim, not a smaller copy of its old input

He ruled that what is being tested is questioned, rather than how fast the test can be made; algorithmic improvements can often be found by rethinking the problem.

### A flaky check step is merged past and filed, not rerun into a verdict

He ruled: merge and file.

## 2026-10-30 rulings

The owner's rulings of this day, each as his meaning and where it is now stated.

### Limits fail loudly

He ruled that limits always fail loudly. Stated in AGENTS.md §5 and [config](config.md#a-bound-fails-loudly).

### A timed wait means the wrong design

He ruled that timeouts mean, most of the time, that the design is wrong (wording unverified). Stated in AGENTS.md §4 and [clocks](clocks.md).

### There are no runs: an agent has an env, a branch and its evals

He ruled that there is no concept of a run: agents have an env, a branch and are doing evals, and data is attached only to real things. A message wake raises the turn limit by a config amount.

### Errors reach the agent grouped, once

He ruled that errors render grouped by signature with counts, with the newest first line and a handle; root records all; the context sends what changed. Stated in `seon.render.ui/problems-model`.

### The context sends changes

Approved: a changed part sends `clojure.data/diff` of its value; a cited var at preview depth; the turn counter on each eval's outcome line. Task seon.ctx/the-context-is-the-agents-evals-and-seons.

### Light tests while an agent develops, the full suite near merge

He ruled that the test suite is kept minimal and run for the most likely impact. Stated in [merging](merging.md#a-merge-runs-what-the-change-reaches).

### A process test's cache gap stays; the full run catches it

He ruled to leave it, and asked whether tests known to be uncacheable can be marked so that they always rerun when touched. Superseded by 2026-11-10, a test that starts a root takes what root runs.

### Main keeps recording live data; agents react only when main's program moves

He ruled that a shared main moving forward with data remains, and, of whether main's agents should react only when main's program moves, that he thinks so. Stated in [the plan](plan.md).

### Eval budgets come from measured costs

`:seon.config.agent/form-ticks` and form-bytes are derived from measured eval costs, not fixed; an overrun is mostly a case worth intervening.

### Simplify by constraint; the test suite must be trusted

He ruled that constraints are added to the system to simplify solutions, and that all correctness issues are solved with the test suite, which must be trusted as much as possible. Stated in [the plan](plan.md#the-plan).

## 2026-10-29 rulings

The owner's rulings of this day, each as his meaning and where it is now stated.

### A link the owner is given survives a restart

He ruled that he is always given links, or a stable way to find links, on a stable server. Stated in [sessions](sessions.md#stable-links).

### The page is a friendly chat; what the model was sent is shown whole

He asked whether the page looks like a friendly chat window his mother could use, and said he wants nothing hidden. Stated in [render](render.md#the-page) and [the debug page](render.md#the-debug-page-is-the-wire-request).

2026-11-15, to web: the site is for end users, and the owner's 75-year-old mother must be able to use it. He mostly reads it in a portrait window. The person's view is kind, plain and non-technical, with good typography and a short, clear focus: messages by default, and what happened under the hood one tap away. The debug and @ctx views stay technical, for an expert. The model still gets everything it needs. Nothing is hidden: the person's view folds the detail, and @ctx and debug show it whole. Task: `seon.render.ui/the-persons-chat-is-kind-plain-and-portrait-first`.

The owner's choices, the same day:
- `/` is always the person's chat, never the agent a tab last opened.
- The system's own status (task counts, root causes, problems, root's process, evals sent to root) is on the debug tab only.
- Under each answer, one fold ("how I did this") opens its steps.
- The person's view has no list of sessions (it was a menu on portrait, until root works for the person, below).
- The theme follows the device's light or dark setting.
- A failure reads as one kind sentence, its reason folded under Details.
- Root's session answers a person in plain words.
- Body type is 19-20px.
- The message box is like the best AI tools': it grows taller with long text, has a clear Send button, and Enter sends.
- Opening the page shows a fresh greeting with a clear link back to the previous conversation.
- While Seon works, it shows "Thinking"; expanded, the progress streams in, then the answer streams in. Streaming needs a plan that checks the reactive core and Datastar.
- Root's session never ends, so what its model reads should be a sliding window kept short by real compaction (refinement keeps editing it down), never a blind cut.
- Root, and any agent talking with a person, answers the person promptly; the person still drives their agent, and agents can act for them too.
- The messaging plans include the person.

Root works for the person (the owner, 2026-11-15, through root and to web): an ordinary user never learns that other agents exist; they talk to root at `/`, in one conversation. Root acts for them as a product and project manager: it understands what they want, turns it into work for the agent whose area fits, manages those agents, and answers, either looking it up itself or asking the right agent for the data. Every agent has the same chat and the same messaging; root differs only by its task. So `/` is root's chat in the person's view, with no list of sessions; the agents' pages, the system's status and the checkout line are on the debug view.
- While work runs, the agents doing it send short progress updates back, and the waiting line shows the newest one live, with every update one tap away. No bare "thinking" with nothing behind it.
- One user for now: the signed-in user (`user`) is the person; several users come later.

### Counts are tokens; what an agent writes is Markdown

He ruled that only tokens are reported (estimates are fine), not characters. Agent text is Markdown. Stated in [prompt](prompt.md).

### References at their depth, previews first

He ruled that what is shown is ours to control, so previews can be shown and the agent can dig in for full details. Stated in `seon.ctx/cited`.

### Parts answer data: an html render and a model render, the value named status

He ruled that everything stays data until the print boundary. How an agent's work stands is named status, git's word. Stated in [render](render.md#a-component-answers-hiccup) and [render](render.md#how-the-work-stands).

### The standing store is test data: wipe it when a change needs it

He ruled that nothing in the store is needed and it is wiped whenever needed. A renamed or retyped stored attribute erases the store, never migrates it. Stated in [machine](machine.md#disk-stays-bounded).

### The verdict cache holds 512 MB, least recently used out first

He ruled that the verdict cache evicts automatically, least recently used first, when its maximum capacity is reached, and that cache work other agents may need is not dropped. Stated in `:seon.config.proofs/results-max-bytes`.

### An eval call holds one or more forms

He ruled that the docs' statement that only one form runs is removed. `:value` is the vector of the forms' values, and he agreed. Stated in [prompt](prompt.md) and `seon.repl/response`.

### The model's context is the render of the agent's record, by the one walk

He ruled that both the context render and the web UI render are kept in mind so that neither gets out of sync with the other. Stated in [render](render.md#the-models-context-a-cached-prefix-then-the-turn).

### Nothing derived is held; everything is derived from the db at t

He ruled that deletion is not feared: the system works with the data that is there. Derived state is not held in the system; it is derived from the current state. Stated in AGENTS.md §4, [ctx](ctx.md) and [render](render.md).

### The agent's record is facts about its work

He ruled that the agent's record is facts about its work, and that telemetry and derived data live on their own entities, pointing at what they describe. Stated in [ctx](ctx.md#the-agents-entity-holds-its-work) and [ctx](ctx.md#the-pattern).

### The agent is the unit, not the run

He ruled that runs are gone, since the concept does not really exist, and that more git merging terms and fewer made-up things are used. See 2026-10-30, there are no runs.

### An agent's test results are facts on its branch

(Owner's answer, 2026-11-15.) Runs and sessions are not concepts of their own; they collapse into the agent's schema. Only validated facts that were true at their time are stored, and what is needed to act is derived. So an agent's test results are validated facts on its branch, with no run or session entity; `bin/test` and `bin/prove` keep the shared verdict cache.

### A value is rendered by the newest render of its schema

He ruled that when it is not known what something is, a warning or error is returned so agents are encouraged to fix it. Not built.

### Seon runs on an ordinary MacBook

He ruled that this runs on an average user's MacBook, for reference, not only on servers or on machines with 128 GB of RAM like his. Stated in [machine](machine.md#ten-agents-at-once).

### The image keeps each frame's file:line and drops the expanded source

He chose V2b: about -210 MB a root, every eval error unchanged. Task seon.main/the-image-keeps-no-inspector-information.

### The embedding model is a Flow proc

He directed that the embedding model's handling be investigated properly, not guessed at. Stated in [vector](vector.md#the-model).

### Config at every level, no env var switches

He ruled that config options are formalized so that no env-var magic happens. Levels are built as keys need them (below). Stated in [config](config.md).

### Env vars are fine when aero captures them into config entries

He ruled that env vars are not bad, but they must be captured by aero and translated into config entries. Stated in [config](config.md).

### Stop the garbage at its source

He ruled that the garbage is stopped at its source. Stated in [machine](machine.md#roots-heap).

### Use the machine while nothing is starved

He ruled that the machine can be used as long as nothing is starved, since only starvation slows everything down. See [load is judged by cores and free memory](#load-is-judged-by-cores-and-free-memory).

### No artificial limits or cuts on an agent's context or evals (owner, 2026-10-29)

He ruled that no artificial limits or cuts are put on an agent's context or evals. Stated in [errors](errors.md) and [agent](agent.md).

### Ten to twenty agents on a small machine

He ruled that 10 to 20 agents should be fine for users with small machines, and that the sky is the limit for servers and big iron. Stated in `:seon.config.agent/max-running`.

### Fix every major optimization issue; memory is the thread to pull

He ruled that the job is to prioritize fixing all the major optimization issues seen.

### Config levels are built as keys need them

He ruled that config has only the levels in use. Stated in `resources/seon/tasks/seon.config.edn`.

### Seon fits 8 GB

(Owner's answer, 2026-11-15.) Seon uses about 8 GB, so it fits on the 16 GB M1 target. Stated in [machine](machine.md#ten-agents-at-once).

### The standing root follows main's ref, never the checkout's HEAD

He ruled that root follows main's ref. Task seon.fx/the-standing-root-follows-main-not-the-checkouts-head.

### Old worktrees' run data is not kept

He ruled that no run data is important right now.

## 2026-10-28 rulings

The owner's rulings of this day, each as his meaning and where it is now stated.

### An eval's result is data with Clojure's value

He chose to match Clojure for the eval output. One form per call is superseded by 2026-10-29.

### The model is sent the whole conversation; no history budget

He asked who made a 32k history budget, and ruled that it be removed, since he never approved it. Stated in `seon.ctx.wire/request` and [render](render.md).

### The eval protocol: tool calls by id

He ruled to match Clojure: a top-level `do` is unrolled so its definitions are program. Stopping at the first throw is superseded by [every eval of a reply runs](#every-eval-of-a-reply-runs-owner-2026-11-08). Stated in [prompt](prompt.md).

### Nothing crashes and nothing hangs; failures are data to the agent

He ruled that the system is robust and never crashes or hangs. Stated in [errors](errors.md#nothing-crashes-and-nothing-hangs).

### Every eval's answer says how main differs from the branch

He ruled that agents can ignore how main differs and work, then merge at the end, but there are never any surprises. Stated in [merging](merging.md#main-moved-is-said-at-every-eval).

### Derived data may be cached; it is always regenerable from the db at t

He ruled that derived data may be cached on the understanding that it may be evicted at any time. Stated in [data modeling](data-modeling.md#derived-data-is-a-cache-never-relied-on).

### Focused sessions; no fan-out

He ruled that launching 10 or more agents at once burned his tokens and was not productive, so sessions are focused with no fan-out. Stated in AGENTS.md §2 and [sessions](sessions.md#no-fan-out).

### Build the product, not infrastructure (owner, 2026-10-28)

He ruled that a lot of infrastructure was built in the belief that it would speed things up, and it has not. Before building tooling, ask whether it moves an agent finishing a real task. Stated in AGENTS.md §9 and [sessions](sessions.md#no-fan-out).

### Test results are a few facts per test and per function, with no history

He ruled that things in the db can be queried, functions, tests and schemas, to understand which tests are failing and their timings, so not everything is removed from the db. Task seon.proofs/a-verdict-is-an-entity-that-names-its-environment.

### A kept db value is a reference, {:branch :commit :t}

He ruled that large values are not carried around for no reason: at those points a db is turned into whatever facts are needed to re-create that point in time. Stated in [storage](storage.md#a-kept-db-value-is-a-reference) and AGENTS.md §4.

### Context is structure first; ranking is judged by turns to green

He ruled that proof that something works is 90% relevance, the agent actually using it, and a shorter number of turns to achieve a goal; he agreed to start with structure first. Stated in [ctx](ctx.md#what-the-scorer-reads-from-a-pulled-node).

### The cron stays, written well

He ruled that a cron is needed and fine if it is well written.

### The store is erased outright

He ruled that the store is erased outright, with no archive copy.

## 2026-10-27 day rulings

The owner's rulings of this day, each as his meaning and where it is now stated.

### An inventory before a plan

He ruled that a new agent already having a plan cannot be well thought out (wording unverified). Stated in AGENTS.md §3, the method, step 1.

### A merge runs the process tests its change reaches, automatically

A merge runs every test and step the change reaches with no green at its digest, process tests included. Stated in AGENTS.md §3 and [merging](merging.md#a-merge-runs-what-the-change-reaches).

### ctx stays seon.ctx/ctx

The name `ctx` stays.

### An agent changes a function by (in-ns) and defn

Clojure's own way, never a special edit function. Stated in AGENTS.md §4.

### No lock on main's verification (owner, 2026-10-27: "Remove it")

Supersedes "main first, as a fact" (2026-10-26 night). Stated in [merging](merging.md#main-goes-first) and AGENTS.md §8.

### A branch proves its in-process tests (owner: "Get rid of the double testing")

Stated in AGENTS.md §3 and [merging](merging.md#a-branch-proves-its-in-process-tests-mains-batch-runs-the-process-tests-once); see 2026-11-10, every session proves its branch.

### A known problem keeps its branch out; an unknown one takes its test out

He ruled that a test is removed temporarily if need be. Stated in [merging](merging.md).

### No test digests the whole tree; the daily full run is the net

He ruled that no tests include all of src. Amended by [a test that starts a root takes what root runs](#a-test-that-starts-a-root-takes-what-root-runs-owner-2026-11-10). Stated in [source](source.md#tests-run-by-reach).

### A lock-free system

He ruled for a lock-free system, the worktrees hack being temporary. Stated in AGENTS.md §8 and [merging](merging.md).

### The wiki is the one home

He ruled that the wiki is used for everything, so it gets better, because that is what agents pull into their context; this means what is in the wiki is not duplicated. Stated in AGENTS.md §7.

### Test results are a few facts through one writer

He ruled for one writer, and that the database never gets hundreds of thousands of new records per test run, only the essentials.

### An eval records; what is derived for the agent is a render

He ruled that information derived and made for the agent or the user goes into the hiccup, and that whether something was observed is not marked: the data is just recorded. Stated in [render](render.md) and [turn](turn.md).

### A relevance % means "worth reading"

He ruled that a ranking is needed, ideally with a relevancy percentage that allows cutoffs, and that accuracy matters more than speed. Stated in [vector](vector.md).

### What a Claude session sees through MCP is what a model is sent

He ruled that the results a Claude session sees match the agent's context being generated: the sessions see all the errors and problems, which are fixed at their source, not patched. Stated in [agent](agent.md).

### Unused is not dead

He ruled that code not being in use does not mean he has not worked hard on its design. Code with no caller is cut only with the owner's go. Stated in AGENTS.md §3.

## 2026-10-26 night rulings

The owner's rulings of this day, each as his meaning and where it is now stated.

### A request is one whole task by default (owner, 2026-10-26 night)

He chose one whole task over design-build and merge; design-build and stages stay experiment choices. Stated in [the plan](plan.md).

### Paid runs only on context whose known problems are solved; $30 overnight (owner, 2026-10-26 night)

He ruled that bad context in means bad results out, so paid runs wait until the known problems of the context are solved. The budget is now $50 ([paid model runs](#paid-model-runs-up-to-50-productive-runs-only-known-wrong-context-is-fixed-first)).

### Correctness and stability first, then efficiency to a stated bound; no regression without a reason (owner, 2026-10-26 night)

He ruled that correctness and stability come first and efficiency after, to a reasonable stated bound (it should take roughly this many ticks, this much memory, and so on), and that regressions are not accepted without a reason. Stated in AGENTS.md §3 and [merging](merging.md#a-landing-message-carries-its-audit).

### The agent's view is the render of ctx; "page" names a wiki page only (owner, 2026-10-26 night)

He agreed that the agent's view is just a render function for data, and that there are no separate systems. Stated in [vocabulary](vocabulary.md#words-we-do-not-use).

### The render of ctx never repeats what an eval shows (owner, 2026-10-26 night)

He ruled that the same eval data is not shown again in the render function. Where it touches an eval, the render carries the eval's handle.

### Flaky results are kept (owner, 2026-10-27)

He approved erasing test-results/ and runs/store at that merge (both are test data). Red and green at one digest is `:flaky`, judged red, never rerun. Stated in [merging](merging.md).

### A work task merges itself (owner, 2026-10-27)

He approved dropping with-merge from task files: a work task merges itself through the one gate, and design-build and stages stay as experiment templates. Task seon.merge/one-merge-system.

### All work happens inside Seon; worktrees are retired (owner, 2026-10-27)

He ruled that the goal is to stop using the worktrees and to do all work within Seon itself. Worktrees stay as the bridge until then: see 2026-11-08 and 2026-11-11 (worktrees stay for real work). Stated in [the plan](plan.md).

### Styling: compact hiccup, every element earns its spot (owner, 2026-10-26 night)

He ruled that the fully printed hiccup is compact and everything in it earns its spot. Stated in [render](render.md#style).

### Old branches: audit, then delete (owner, 2026-10-26 night)

Each old branch is read for anything not carried forward, which is filed; then it is deleted.

## 2026-10-25 rulings

The owner's rulings of this day, each with his words and where it is now stated.

### Message delivery: compare, then the better design or the simple one

He ruled that the choice is either a better design or the simple one that works. The comparison chose delivery on main first. Replaced by 2026-11-09, a message is delivered when it is on the recipient's branch.

### The related-section count is a config key; one problem-entry helper's bounds accepted

He approved one config key for how many related wiki sections are shown (0 turns it off), and raising 5 cost proofs' call bounds by one contracted call per problem for one shared helper.

### Dogfood rulings: design registers proofs; each form's value; stale means meaning changed; pilot budget $5

A design stage registers its red proofs; a section is stale when a cited var's contract or docstring changes; the pilot may spend $5. Stated in [tasks](tasks.md#the-done-rule) and [health](health.md#a-stale-section).

### No guards, least of all in Jolt or the db

He ruled that needing a guard is always a sign that the right solution is not in place, which makes the guard unnecessary. Stated in AGENTS.md §§4 and 9.

### An agent's spawn costs what a fork costs; no unnecessary complexity

He asked what is being done if a fork takes milliseconds.

### @ctx prints as an atom

He ruled that the agent has the value printer print `@ctx` like a normal atom with our structure, and that in the web UI for that eval it is rendered in a block.

### One-go tasks for every run; fix the bug, not the kind (owner, 2026-10-26)

He ruled that one-go is used for all tests until a better solution is found, and that one-go is not blamed for the bug that hung the agent: the bug is fixed. Stated in [the plan](plan.md).

### A stall is seen on the page and becomes root's task (owner, 2026-10-26)

He ruled that hangs are very bad, especially when nobody notices that an agent has been stuck. Stated in [the plan](plan.md).

### Not passing until the flow is bounded and within budget (owner, 2026-10-26)

He ruled that a solution does not pass until all functions are spec'ed, including with cost estimates, so that the roughly maximum total cost among the data flows referenced within the system can be budgeted. Stated in [the plan](plan.md).

### Bounds as the plan (owner, 2026-10-26)

He ruled that writing bounds is also sketching out the algorithm, which is ideal for a REPL interface. Stated in [the plan](plan.md).

### One rendering system: the data describes the page (owner, 2026-10-26)

He ruled that there is only one context rendering system, and it is built from the parts. Stated in [render](render.md).

### No invented words; a render is a function and its data (owner, 2026-10-26)

He ruled that a render is just a function and the data being rendered, with no story. Stated in [vocabulary](vocabulary.md).

### Everything the agent and the page show comes through ctx (owner, 2026-10-26)

He ruled that any change wanted in the agent's context and html goes through database updates that reach the same data the context pull gets, which keeps the ctx atom in sync. Stated in [the plan](plan.md).

### Values render when opened (owner, 2026-10-26)

He ruled that things not needed are not rendered until the user expands them, and that the agent knows the user is digging into it. Stated in [render](render.md#the-page).

### Try it in agents' REPLs first; design after; never another system (owner, 2026-10-26)

He ruled that yet another system is not built. Designs follow REPL experiments. Stated in AGENTS.md §3.

### Keep it simple, so the system is easy to modify (owner, 2026-10-26)

He ruled that things are kept simple so the system can easily be modified. Stated in AGENTS.md §4.

### Two outputs: an eval's data, and the page with its diffs (owner, 2026-10-26)

He ruled that there are only two outputs too. Its deletion of a model render is reversed by 2026-10-29.

### No derived data in the db or in ctx (owner, 2026-10-26)

He ruled that derived data is not stored in the db or in the ctx atom. Refined 2026-10-28 (derived data may be cached when regenerable from the db at t) and restated 2026-11-11 (no derived value is stored).

### Stability and correctness first (owner, 2026-10-26)

He ruled that the changes are got to main with stability and correctness prioritized.

### The REPL stays simple; every enrichment is a render (owner, 2026-10-26)

He ruled that the REPL and evals themselves are simple and stable. Stated in [render](render.md).

### The wire: our channels inside the provider's roles (owner, 2026-10-26)

The provider's roles stay: system, user, assistant, tool.

### The request is a pure function of the db at the session's t's (owner, 2026-10-26)

He ruled that the request is byte-stable from the database at T, not a stored set of messages sent to the agent. Stated in [render](render.md).

### Changes arrive as messages; the eval response says only what the eval caused (owner, 2026-10-26)

He proposed that explicit messages are probably needed for changes coming in, returning the diffs.

### Evals run once; the agent is told what changed (owner, 2026-10-26)

He proposed that evals perhaps never be re-run (unless a session is being resumed), and that the diffs that happen as things change are shown instead. See 2026-11-06, an eval run again replaces itself.

### Every change is told, as a data diff in ctx's shape (owner, 2026-10-26)

He ruled that all changes should be worth telling: weird naming, unnecessary updates and machinery instead of actual values mean the design is wrong.

### Evals are evals; both contexts always live (owner, 2026-10-26)

He ruled that the agent's context and the user's context are always live. Its replay is superseded (2026-11-06).

### Owner answers, 2026-10-26

The owner's answers to that day's questions.

1. He agreed that the joint ctx/UI design is built when it follows the rulings.
2. He agreed that the $30 paid budget continues.
3. He ruled that whether to re-execute or just pull the cached value is an explicit attribute, not inferred: replay is an explicit attribute.
4. He ruled that caching is deterministic: if we change the data halfway through we lose half the cache, but if everything up to the dynamic forms accretes, it should be cached at that rate.
5. He ruled that the branches are audited for merit with Sonnet and integrations suggested.
- Of the hiccup the model reads, he wants both a) a representation of the UI the user is actually seeing that is not giant and b) not to distort it.

### The web UI is the render of ctx

He asked why the same data is derived twice. Stated in [render](render.md).

### Fix causes, not symptoms: the repeat rule is under investigation

He ruled that the repeat behavior sounds like instructions or formatting, so it is fixed, not patched, and investigated; fewer hacks and more fixing of root causes. See 2026-11-11, a stuck agent is stopped, analyzed and fixed.

### Overnight budget $30; old branches triaged forward; ctx built render-first

He ruled that the good ideas from the unmerged branches are triaged and moved forward.

### Overnight: all cylinders

He approved highlight.js and ruled that all the fixes be launched. See 2026-11-10, load is judged by cores and free memory.

### The value view: highlight.js, agent hiccup unrestricted, built after the printer

Highlighting by vendored highlight.js; an agent's hiccup renders as HTML without restrictions. Stated in [render](render.md#a-value-on-the-page-is-its-structure).

### Platform stability first

He ruled that platform stability is very important, so the cause is found and fixed.

### Related things are found by measured search over every kind, through stored edges

He ruled that the best known related-context fixes are implemented. Stated in [vector](vector.md).

### Code is shown as code; the page shows values as structure

He ruled that when a function is referenced, the quote-escaped forms are returned, so agents see Clojure code as it is normally seen in files or the REPL. Stated in [render](render.md#a-value-on-the-page-is-its-structure).

### Noise is fixed at its producer

He ruled that the focus is not generating junk and then not rendering junk. See 2026-10-29, no artificial limits or cuts.

### A change to a schema is an explicit fact the eval shows, never a hidden digest

He ruled that explicit changes in the system are modeled accurately. A contract or docstring change is a transaction, and the eval that makes it shows before and after. Stated in [health](health.md#a-stale-section) and [agent](agent.md#your-turn).

### Starting and resuming are one mechanism

He ruled that there is no separate system for running agents. See 2026-11-06 on replay.

### No new linking attributes where :needs does it

He pointed to the `:needs` way of connecting the graph together; every agent of course has a stable key, which is essential, and everything should survive if it can be resumed.

### Messaging is one function, `my.agent/send-message`

One function for an agent to message others.

### No restrictions; any agent can be root; agents edit their own dynamic forms

He ruled that all agents can be root, as root is just a name given to them. See 2026-11-11.

### A request's stages are separate steps in one session

He ruled that the investigation, implementation and merging steps are separate steps. The default is one whole task (below).

### Every agent parks; resume replays pure forms and asks for effects

He ruled: every agent always parks, and on resume pure forms are replayed automatically while effects are asked. Automatic replay is tempered by 2026-11-06 ([a parked agent stays in memory](#a-parked-agent-stays-in-memory-and-replay-is-deliberate)). Stated in [processes](processes.md#a-parked-process).

### A session's context is generated from the db

He ruled that a session's context is generated from the db at a commit point and is immutable. Stated in [render](render.md).

### Root's special cases go, all four

Root is an agent on main, the dead merge path is deleted, root's work is tasks on main's current commit, and there is one prove path.

### Agents stay running, parked, between messages; any commit can be resumed

He proposed that the forks perhaps simply keep running, parked. Stated in [processes](processes.md#a-parked-process).

### Correctness and performance come before lines of code

He ruled that correctness and performance are prioritized over lines of code, which is not his main metric. Stated in AGENTS.md §1.

### Root is main; everything else is the same system with different config

He ruled that a separate system is not built for root. Stated in [users](users.md#the-entities).

### Agents restart from their record; the lifecycle is Flow's

He asked whether the Flow step function and lifecycle can handle the forking and killing.

### The screen is sent as diffs between turns

He ruled that the screen is sent as diffs between turns. Replaced by 2026-11-05, the render is an eval, and what changed is another.

### Merge the agent-view branch to main once its review is done

He ruled that 873 lines is not much if the change is good, so it is merged to main.

### A request is one whole task, for now

He ruled that until the problem is solved, the experiments continue with the agent doing everything in one task, until the idea is fully baked, and that the other tasks are not got rid of. Stated in [tasks](tasks.md#a-request-is-one-transaction).

## 2026-10-24 rulings

The owner's rulings of this day, each with his words and where it is now stated.

### A failed model call keeps what it sent and its typed error

He approved `:seon.turn/error`. Now `:seon.model-call/error`. Stated in [turn](turn.md#the-turn-is-a-number).

### A turn records what it sent as refs; texts are read as of the turn

He approved `:seon.turn/sent` only; the system prompt's wiki section and the task entity are referred to as of the turn's t, instead of new `:seon.text` attributes. Now `:seon.model-call/sent`. Stated in [ctx](ctx.md#the-agents-entity-holds-its-work).

### A config key does what it says; a provider's schema refuses a combination it forbids

He ruled that the config does what it says, and that where a combination is not allowed, a schema validation for DeepSeek confirms that the right settings are set. Stated in [config](config.md).

### The agent view and context design is approved

He approved the design as written, including the owner items in section 11. Its parts are restated by later rulings; stated in [render](render.md).

### Names tell a story; the printer shows every value well

He ruled that naming is beautiful and the render tree tells a story, not just a set of random words. Stated in [vocabulary](vocabulary.md).

### A prove run is keyed by the digest of what it reads

He agreed to try the digest. Stated in [merging](merging.md#the-test-service).

### Root merges verified fixes as they come in

He ruled that fixes are merged as they come in. Stated in [merging](merging.md#a-branch-lands-with-binland) and [merging](merging.md#a-landing-message-carries-its-audit).

### One render per schema, declared where the schema is registered

He agreed to try a single render function. Its single face is reversed by 2026-10-29. Stated in [render](render.md).

### The debug view shows exactly what was sent

He ruled yes in terms of content, with the view styled and clear to read. Stated in [the debug page](render.md#the-debug-page-is-the-wire-request).

## Tonight's rulings (2026-10-23, with the 2026-10-21/22 rulings they rest on)

The owner's rulings of this day, each as his meaning and where it is now stated.

### Learn from earlier work, then write it clean; remove a worktree once it holds nothing unsaved

He ruled that good work is not thrown away: agents learn from it if a clean implementation is wanted. Stated in AGENTS.md §9.

### Reuse every unique, immutable result that is proven the same and expensive to make

He ruled for reuse of all unique, immutable data that is sure to be the same and is expensive to render. Stated in [data modeling](data-modeling.md#derived-data-is-a-cache-never-relied-on).

### The turn's live state is REPL forms

He ruled that agents write functions in the REPL, get feedback for what has changed compared to main with clear diffs, and see the tests they need to pass listed clearly. See 2026-11-05, the render is an eval.

### Examples are real green sessions, exported to EDN on root's call

He agreed that the examples should be real and fixed correctly. See 2026-11-11 on reviewed sessions.

### The model's context is the standard chat format: a short system prompt, the task as the user message, one eval tool

He ruled that a clearer, more traditional template-style chat format with official tool-calling support is needed, or that the approach is validated against a known quantity. The tool is now `repl` ([model](model.md#one-tool-repl)). Stated in [render](render.md#the-models-context-a-cached-prefix-then-the-turn).

### The design stage looks around and writes the referenced plan

He ruled that this is why the design task comes first: it looks around and writes the referenced plan. The default request is one whole task (2026-10-26).

### src resists growth; no line-count target

He ruled that there is no hyper-fixation on lines of code; what is wanted is some resistance to growth, mostly so agents reuse what is there and improve the core parts instead of making one-offs. Stated in AGENTS.md §1.

### The context is data, made relevant at its producer; renders do not turn it into prose

He ruled that data is better, so the data is improved so that relevant information is there. Stated in [render](render.md).

### Sessions research on their own; a new session needs root's approval, unless the owner says otherwise

He ruled that he can instruct any agent to override that. Stated in AGENTS.md §2.

### The embedding work goes ahead, the model download included

He confirmed that he approved the embedding work. Stated in [vector](vector.md#the-model).

### No live agent runs until the context has a format agents respond well to

He said that he is not sure how helpful live agent runs are until there is a sane format that agents respond well to. Stated in [the plan](plan.md).

### The reuse design is approved: one memo keyed by what the process runs, for the walk's renders first

He approved building the revised reuse design, walk node renders only, with its proofs. Its branch was retired on 2026-11-06 ([old work retired](#old-work-retired-not-left-halfway)).

### bin/test branches a kept program db per git tree (option B); each worktree's root is the end state

He chose option B: a kept test store holds a branch per git tree. Replaced by 2026-11-10, the test system runs inside Seon.

### Reuse is memoization: `(= (f x) (lookup f x))`, so return it

He ruled that, since the two are literally the same, the value is just returned, and that nothing is cached on a key that needs to be updated. Stated in [data modeling](data-modeling.md#derived-data-is-a-cache-never-relied-on).

### Reuse is transparent and simple: a reused value is shown as it is, never marked

He ruled that a cached value is simply shown as it is. Stated in [cost](cost.md).

### Everything is fast; a render or a context whose inputs have not changed is never made again

He ruled that in general everything is fast, but that some things are foolish not to cache, like web rendering and even AI context, if the underlying data and functions have not changed. Stated in [data modeling](data-modeling.md#derived-data-is-a-cache-never-relied-on).

### Caching is never an excuse for slow code: the cold path is fast on its own, and a hit is visible

He ruled that caching is not an excuse for writing poor code, and that if the code is fast enough not to need caching, caching is not used. Its visible hit is superseded by the entry above. Stated in [cost](cost.md).

### Push pinned fork commits without asking

He ruled that he need not be asked any more before pushing public repos. Stated in AGENTS.md §8 and [forks](forks.md).

### Fork URLs are the GitHub forks

`.gitmodules` names the forks at `seantempesta/*` over https (quote not recorded). Stated in [forks](forks.md).

### The project root is `~/src/seon-next`

Quote not recorded. Stated in [machine](machine.md) and [merging](merging.md#a-worktree-session-works-in-a-checkout-of-its-own).

### Cost is work done: ticks and bytes, never wall time; calls are no cost (owner, 2026-11-15)

He ruled that `:calls` is dropped (2026-11-15, via root, after a calls bound went green and red at one digest: the counters are process-wide). A contracted call's count stays visible in profiles, never judged. Stated in [cost](cost.md).

### A session's prove runs the process tests it reaches (owner, 2026-11-08)

He agreed to run them early. Stated in [merging](merging.md#a-slow-test-runs-last).

### Process tests are not automatic: the reach walk classifies them

Superseded on 2026-10-27 by a merge runs the process tests its change reaches, automatically.

### Ids are derived from content; addressing is global

"content-derived ids; global addressing" (root's words). See 2026-11-08, an id is the content of the facts that make it, and 2026-11-11, ids are unique across branches.

### Tests run on change, through Seon

He ruled that testing happens on demand, when a change happens and when data flows through the system, with fewer useless runs. Stated in AGENTS.md §3 and [merging](merging.md#a-merge-runs-what-the-change-reaches).

### Renders are `(opts & children)` functions, Reagent's form, with auto-render

He guessed that renders take Reagent's shape, symbol, opts and children, as he was thinking of React. Stated in [render](render.md).

### Thinking is off by default

Reversed 2026-11-12: [reasoning is on at low effort](#reasoning-is-on-at-low-effort-for-work-tasks).

### No automatic retries: a refused or failed run is held for root

He ruled that there are no automatic retries: a refused or failed run is sent to root to decide whether it is retried. Stated in [tasks](tasks.md#a-refused-run-is-held-for-root).

### The standing store is test data: erase it, no migration; history within a store is kept

He ruled that migrating 6 facts is pointless, since it is just test data: the store is erased and the work moves on. Stated in AGENTS.md §9, [machine](machine.md#disk-stays-bounded) and [storage](storage.md#history).

### Agents share greens: a test green at its digest never runs again

He ruled that tests that are the same, especially when freshly branched, are not run again if they are already green. Stated in AGENTS.md §5 and [merging](merging.md#a-merge-runs-what-the-change-reaches).

### Output quality is decided by blind scoring on real data

(Owner's answer, 2026-11-15.) An agent decides freely within the existing systems (it may write new render functions, fix existing ones, and so on), but builds no new system without the owner's or root's approval.

### The context speaks Clojure, with the agent's thinking beside its forms

He ruled that the task is explained in terms of Clojure code executing and thinking comments, and that the agent is encouraged to do the same.

### waste stays (`:seon.run/waste`); stuck went with the budget

Quote not recorded. Stated in AGENTS.md §7a.

### The dashboard stays in `src`

Quote not recorded.

### Worktree sessions are truly independent

He ruled that each lane can do anything it wants to the codebase and fully test it, and that it can then be shut down and merged: truly independent work. Stated in [merging](merging.md#a-worktree-session-works-in-a-checkout-of-its-own).

### Claude sessions use worktrees until Seon agents work inside the system; the end is Seon agents and git only for write-back

He ruled that Claude agents use worktrees until they can work in Seon itself. Stated in AGENTS.md §3.

### Long runs only when the work is done; otherwise run only what a change reaches

He ruled for faster development: long tests are not run unless it is fairly certain the work is done. Stated in AGENTS.md §3 and [source](source.md#tests-run-by-reach).

## 2026-10-22

The owner's rulings of this day, each as his meaning and where it is now stated.

### Root runs autonomously while there is good work

He authorized root to run autonomously as long as there is good work to work on. Stated in AGENTS.md §2.

### One value printer, nothing cut before it; important data has declared `:ai` and `:html` renders

He ruled that there is not more than one value printer, and that no clipping happens before it. Stated in [render](render.md#one-printer) and [render](render.md#no-clipping).

### A request's whole linked task set is one transaction on main, before any agent forks

He ruled that it is just one transaction with several maps. Stated in [tasks](tasks.md#a-request-is-one-transaction).

### Names are Clojure's, its libraries' or Jolt's

He ruled that names that are not Clojure's, a Clojure library's, Jolt's or similarly related are no longer used. Stored names change by erasing the store. Stated in AGENTS.md §7a and [vocabulary](vocabulary.md).

### Compiled units are not stored now; the idea stays open

He ruled that the idea stays open, and that the aim is to reach the point where the work is productive and real Seon agents are fixing problems in the system. Stated in [storage](storage.md#compiled-code).

### A task edited on two live branches is a conflict for root (D4)

He ruled that this should never happen, and that if it does there should be a merge conflict that root has to resolve. Stated in [tasks](tasks.md#task-content-reaches-main-at-the-runs-end).

### A task's own checks live with the task, outside main's test namespaces (D5 (a))

He chose option (a); quote not recorded.

### Paid model runs: up to $50, productive runs only; known-wrong context is fixed first

He ruled that if the context is known to be bad, money and time are not wasted: what is known to be wrong is fixed first. Stated in AGENTS.md §8 and [model](model.md#paid-runs).

### One store holds the whole system; branches are experiments at every layer

Quote not recorded. Stated in AGENTS.md §§4 and 5 and [storage](storage.md).

### Our Datahike is Jolt-first, and storage is tuned for our workload

He ruled for one really good Datahike, with JVM-specific features only in a JVM branch and Jolt-specific features as the main version going forward. Stated in [forks](forks.md) and [storage](storage.md).

### Agents work at the REPL through tool calls: no git, no source-file editing

He ruled that the point is to use the REPL and not to edit files. Stated in [source](source.md#an-agent-changes-code-at-its-repl).

### Tasks are unrestricted; behaviour follows the data; a task's branch, model and config are derived along `:needs`

He ruled that tasks are not restricted in what they can do. Stated in AGENTS.md §4 and [tasks](tasks.md).

### A request becomes design, implement, merge; templates are held tasks in the task EDN; one proofs field; every agent gets `(help)` and its task

(Owner's answer, 2026-11-15.) Templates are base tasks in the db, cloned and modified by a function set programmatically, like a normal template. The default request is one whole task (2026-10-26). Stated in [tasks](tasks.md#a-request-is-one-transaction) and [references](references.md#task-references).

### Tasks are extensible, with no explicit kinds (`:seon.task/kind` goes)

Quote not recorded. See 2026-11-07, no kinds.

### One agent entity: the run folds into `:seon.agent/*`

Quote not recorded. See 2026-10-30, there are no runs.

### RELOAD goes: `require` and `load-file` load as in Clojure and record nothing

Quote not recorded. Stated in [source](source.md#an-agent-changes-code-at-its-repl).

### Agents run only on a sound platform

He ruled that agents are not run when there are known serious problems with the context, the task instructions, or the functionality of automatically running the tests. Stated in AGENTS.md §8.

## 2026-10-21

The owner's rulings of this day, each as his meaning and where it is now stated.

### No locks, no cap on worktree sessions, no waiting

He ruled that all of it is got rid of. Stated in [merging](merging.md#main-goes-first).

### Every live run is better than the last; new real tasks, no rerun matrix; DeepSeek works every stage, measured

He ruled that there is no matrix on the same run. Stated in AGENTS.md §1.

### The BUILD, SCHEMAS and PKG plans are approved; direct linking stays

He ruled that EDN is used offline, compiled into the program and registered at the REPL, and that the REPL can write to the EDN if it is merged to main. Stated in [source](source.md#schemas-at-the-repl) and [source](source.md#dependencies).

### Minimal code: extend the existing systems

He ruled that building what does not need to be built, and not using or extending the existing systems for minimal code, is the wrong thing. Stated in AGENTS.md §3.

### Disk stays bounded; regenerable output is deleted

He ruled that what is no longer used is cleaned up, and that rules are built in so this does not get out of control. Stated in [machine](machine.md#disk-stays-bounded).

### Files, namespaces and symbols are plain Markdown, read by one reader; only Seon's own docs are stored

He ruled that this works like Markdown syntax, understanding what are files, namespaces and symbols. Stated in [references](references.md#files-and-namespaces-on-demand) and [references](references.md#the-wiki-in-the-store).

### Line citations keep v1's shape (a `path:N` is a file reference marked stale-prone)

Quote not recorded. Stated in [references](references.md#the-delimiter-picks-the-depth).

### `bin/root` restarts the next generation; a process does not own its replacement

He chose option a; quote not recorded. Stated in [source](source.md#the-next-generation).

### How we work: process, not whim; research, then a simple plan; learn into the wiki

He ruled that the work is made so that it does not always start from zero and keep redoing the same mistakes. Stated in AGENTS.md §§3 and 7.

### The goal is the recursive self-improvement loop, toward thousands of agents accreting good changes

He ruled that the recursive code self-improvement loop is the goal, with thousands of running agents doing different tasks and each focusing on its task. Stated in AGENTS.md §1.

### Loading code is an effect, typed at the edge

He ruled that anything like this is made an effect and treated as strongly typed at the edges. Stated in [flow](flow.md#effects-one-owner-and-its-workers-per-kind).

### Every definition happens once, where it is defined

He asked why things are being redefined. Stated in AGENTS.md §5 and [resolution](resolution.md).

### Programs are known from the compiler, not from text

He ruled that regexes are not used: it must be known exactly what something is, so there are no hacks. Stated in AGENTS.md §9 and [source](source.md).

### Runs are kept, history and all; until there is real user data the standing store is test data (above)

He ruled that runs are kept, so there is a perfect record frozen in time. Stated in [storage](storage.md#history).

### Agents stay forked processes, each named by role, task and checkout

He ruled that the processes are named if they really have to be different. Stated in [processes](processes.md#the-agent-process).

### Work is admitted by its measured workload, through flows

He ruled that there are no slots: instead there are smarter systems that understand the workload and are Flow-based. Stated in AGENTS.md §4.

### Embeddings join the context: fused, small and measured

He ruled that if embeddings are not working well, they are left out for now, but they are not dropped, since they have not been fully explored. Stated in [vector](vector.md).

### Patterns live in the topic sections, linked

He ruled that the work learns how to detect patterns in our code and reuse these solutions. Stated in [the wiki's map](README.md#how-the-wiki-is-kept).

### If nothing has changed, we do nothing; no clock or timer decides (three exceptions)

He ruled that there are no clocks or timers. A fourth exception came on 2026-11-10. Stated in AGENTS.md §4 and [clocks](clocks.md).

### A failure goes to whoever can fix it

He ruled that a fatal error needs to be addressed, and that a runtime fix that can be applied needs the error to apply the fix. Stated in [errors](errors.md) and [failure points](failure-points.md).

### Only documented config

He ruled that the only things that stay are documented config schemas ported from Seon if they are relevant. Stated in AGENTS.md §5 and [config](config.md#the-file).

### Branches as in production; a separate store is a last resort

He ruled that separate stores are a last-ditch effort. Stated in AGENTS.md §5.

### A merge carries the program and the agents' data, never platform records

Root's ruling, not the owner's; replaced by his of 2026-11-11, a sync merges everything that does not conflict.

### Durability is continuous group commit

He asked why there is an arbitrary 10 ms collection point. Stated in [storage](storage.md#durability-is-continuous-group-commit) and AGENTS.md §5.

## 2026-10-20

The owner's rulings of this day, each as his meaning and where it is now stated.

### Estimates are proofs on tasks

(Owner's answer, 2026-11-15.) Agents set their expectations when they file or update a task; those become proofs held to account. A wrong estimate is updated, not fenced; agents are told never to game it and to correct mistakes instead. Stated in AGENTS.md §5 and [cost](cost.md).

### Scheduling is reconciliation over task attributes; no chain entity, no separate scheduler

Quote not recorded. Stated in AGENTS.md §4 and [tasks](tasks.md).

### Effects go over channels to their owner procs; whoever asked records the outcome

He ruled that IO and effects in general are likely to be sent over channels to dedicated processing flows. Stated in AGENTS.md §5.

### Flow is the one model: every component is a step-fn proc, declared in one file, ordered by dataflow

He ruled that there is no point in using Flow if it is used the wrong way. Stated in [flow](flow.md).

### Data-centric, always valid: contracts on every function

He ruled that contracts are the universal thing that is honed and fixed. Stated in [checks](checks.md#contracts-armed-at-definition).

### Each agent writes its own branch, in its own process; root writes only main

He ruled that agents on different branches take care of their own business. Stated in [storage](storage.md#who-writes-a-branch).

### It's a REPL into a real session; branches are true branches, merged per definition, never replayed

He ruled that new namespaces can be created, period, just like a REPL. Stated in [agent](agent.md#the-repl) and [source](source.md).

### Get out of the way: fixes at the producer, no guards; failure points are named

He ruled for thoughtful fixes, not fences and guards. Stated in AGENTS.md §§4, 5 and 9 and [failure points](failure-points.md).

### The transition goal: `` replaces Seon, consistent, contracted, about 10k lines

He ruled that everything is spec'ed and only good tests using our test harness are kept. Its line target is replaced by 2026-10-23, src resists growth. Stated in AGENTS.md §0.

### Everything on Jolt; libraries fixed properly, measured, with no extra caches

He asked, where not using a cache is faster, why cache at all. Stated in [forks](forks.md).

### Renders attach to schemas; the UI and the context are renders, ported not reinvented

He ruled that everything has a schema, so we say this schema renders this way. Stated in [render](render.md).

### Agent context comes from the plan's references

He ruled that if agents need more context, it is given in the plan. Stated in [references](references.md#task-references).

### One messaging system; the user is an agent

He ruled for one simple messaging system that works everywhere. Stated in [users](users.md#messages).

### Outside agents reach any agent or root through a thin MCP server

He ruled that outside agents need minimal MCP wrapping so they can use a REPL to run statements against any agent or as root. Stated in AGENTS.md §6 and [parity](parity.md#the-mcp-server).

## Open: does this still stand?

The owner's rulings of this day, each as his meaning and where it is now stated.

### Each agent is a proc in its own Flow graph, or agents are procs of the one root graph

His view is that the agent is its own process, so having the Flow controls hooked up directly seems ideal. Left to measurement; which stands?

## Open owner questions (pending rulings; the plan's blockers are also in plan.md)

Questions still waiting on the owner; THE PLAN's blockers are in [plan](plan.md).

- D6 names for the merge model: `:seon.task/carry`, `:seon.task/source`, `:seon.task/target`, `:seon.merge/kept`, `:seon.tx/changed`.
- D7 task config: keep the config keys as top-level task attributes or a real `:seon.task/config` attribute.
- What a merge carries and who wins a conflict (partly answered 2026-11-11: everything that does not conflict merges).
- Changed-since: a Datahike tree diff by node address, or a `:seon.tx/changed` stamp.
- Proofs along `:needs`: keep `seon.task/accumulate` or never carry proofs.
- No progress: is a byte-identical repeated reply a recorded fact only, or does it end the run (see 2026-11-11, a stuck agent is stopped).
- What is a stall once an eval is bounded by its work (`seon.root/a-stall-is-past-the-requests-own-bound`; `:seon.config.agent/stall-ms`).
- The turn cap: lower `:seon.config.agent/max-turns` to about 2x the measured p90 turns-to-green once 20 real tasks give one.
- A dropped model connection (`:seon.model/transport`): retry once after a wait, or end as now.
- File and shell roots: a config key with a permissive default, or none; whether `:git` `:build` `:deps` `:source` effects are root-only.
- A `jolt/<topic>` fork of `jolt-lang/ring-chez-adapter`.
- Upstream: when to push the `upstream/*` Jolt branches and open PRs, and whether to send the Chez 0006 report and a 0007 issue.
- Profiling tooling: which to build.
- Root's external channels (`:to-writer`, `:back`, `:ext`, `:events`) as declared Flow `:conns`?
- The render proc's render-once caches as units with a `:container`?
- Do `seon.cost` and `seon.profile` belong in the release binary or with the drivers?
