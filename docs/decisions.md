# Decisions

This file holds the owner's standing rulings, one entry each: the rule, the owner's words and date, and where the rule is stated now; current truth and mechanism live in `AGENTS.md` and the wiki (`docs/`), and superseded or done rulings are kept verbatim in git history.

## 2026-11-11 rulings

The owner's rulings of 2026-11-11, on the system goal and its first loop, on one agent mechanism for every role, on how the data is designed, on sessions, examples and their review, and on how a session leaves the system up to date.

### The system goal: the codebase improves itself, by refinement (owner, 2026-11-11)

"Your medium term (you work fast so maybe short term?) is to get the system ready for a recursive self improvment of the entire codebase. Correctness, perf, complexity (how many functions are interwoven? we have the graph...), number of steps for agents to solve their tasks ... I want to set this as a system goal so have context and web and whatever other agents implement this once we agree on the strategy." "The goal isn't radical change, but refinement over time. Think lots of base hits rather than swinging for homeruns." His four picks that morning, by the question tool: the first loop to close is the end of a task, where the agent hands up what would have saved it turns; a change to the system itself is approved by a review task, then merged, with a digest he can revert; a review judges and files tasks and never edits what it reviews; root starts with fixed focus areas. Stated in [the plan](plan.md#the-plan).

### One agent mechanism for every role, root included (owner, 2026-11-11)

"the goal is mostly root running the presentation and handing direction. I can even see "root" being just a set of different agents with different tasks maybe as subagents? So there is the same system everywhere don't start building different systems for different agents. Whatever capabilities we build into the humble agent allows it to take on any role in the system." A role is an agent's tasks and data, never a second kind of agent. Stated in [the plan](plan.md#the-plan), parts 1 and 3.

### A layout is a render over the same pulled data (owner, 2026-11-11)

"if we say the current design with the focused center chat and the tile of context on the right is a default but we can come up with any design because it's just hiccup and we can render anything we want and we think about it as a presentation stage are we setup to do so from a layout perspective? I don't want to lose our data driven UI rendering and don't want any hand rolled systems. This a design goal." And of the same page shown another way: "Under the hood it's the same system so we can have a button where the user can just render it as the default view." Stated in [the plan](plan.md#the-plan), part 3; not built.

### An agent reads what it can read; a question goes to its parent (owner, 2026-11-11)

"we need to stop contacting root for things the agent can access on their own. Every agent can access every branch since it's just metadata connecting to the database so why are we asking root anything? Secondly make sure we aren't hard coding in any root connections and we start thinking of a graph where every agent can have subagents that have subagents and we direct questions to the parent in the hierarchy. How do we represent this all in the graph? just with agents ref'ed to other agents?" The hierarchy is the agents an agent started, at any depth ([users](users.md#what-each-thing-is), "who started what"). Stated in [the plan](plan.md#the-plan), parts 1 and 4.

### Design the data for the pull (owner, 2026-11-11)

"Remember we control the data model and the design and we should be designing it for the pull -- how do we want to get data so our fixed system of walking and rendering works for us not against us." When a design is hard to render, the data is what changes: the entities and refs are shaped so one pull from the entity that owns a page or a request brings what it needs, in the order it is shown. Stated in [data modeling](data-modeling.md#before-you-add-something) and [the plan](plan.md#the-plan), part 1.

### An agent may hold a set of tasks; a filed task runs now or when the system is free (owner, 2026-11-11)

"We also need to practice with an agent having multiple active tasks assigned. These fork and improve sessions should reuse the same task system. ... start a new session with the assigned task(s) (a set I think all must pass if we put in multiple) ... Again no hard coding. We need system based rules." Of the tasks a review files: "I'd love it if they can just run directly, and maybe we start with that but maybe we need to differentiate between start now and start when the system is free. If we design the chains right we can schedule work based on load right?" Stated in [the plan](plan.md#the-plan), parts 1 and 2; not built.

### Tasks chain by needs; a session pulled in never hard-codes what it is (owner, 2026-11-11)

"I want to go back to our idea of chaining tasks together with the :needs dependencies so we can automatically pass things along and reset the context and focus the agents. Pulling in the previous session in a safe way where agents don't get confused about what they are reviewing is going to be important so make sure we aren't hard coding in "example" or whatever when they get turned into openai-compat formats." And of examples: "In some contexts we want to say "this is a potentially relevant example" let's say we match the curated example sessions via the embedding and we dynamically include them if we think it's relevant." The words over a conversation shown to an agent come from data. Stated in [the plan](plan.md#the-plan), part 2; today's hard-coded framing is in [tasks](tasks.md#a-task-names-example-sessions).

### Is a session just an agent? The data model is worked through live, a step at a time (owner, 2026-11-11)

Asked whether sessions are a thing of their own or the agent's id would do: "I'm okay with just picking one if they represent the same thing"; then, of the whole design written that day: "Still want to defer until we work this all through with the data model agent. We need a pull focused data model that works with our render system. Is a session just an agent if so then we don't need session-open/example-open. I want general systems that we can reliably understand how by putting this data in this place we get this sort of context and UI". And of its scope: "I want to focus on a system we can iterate on and refine and build up. Maybe even start smaller. What's the next step that will strain our data model and let's focus on refining the data model and keep building up from there." So no design is agreed on paper: each step is built small and seen in Seon. Stated in [the plan](plan.md#the-plan), part 1.

### A message's turn is not asked; the pull may carry when an attribute was written (owner, 2026-11-11)

"In general I think the message shouldn't matter what turn it arrived on and what we are missing is either a timestamp or the ability to pull in tx-data from any attribute in addition to the attibute?" He asked to see it in the system before ruling further ("I want to see this visualized ideally in the system itself so we can keep improving our own capabilities for rendering"). It is the step being built; "no model call names a message" ([users](users.md#messages)) stands. Stated in [the plan](plan.md#the-plan), part 1.

### Ids are unique across branches; order by time, not by transaction id (owner, 2026-11-11)

"I want all IDs to be unique among all the branches and for us to use time for ordering not TX-ids". Entity and transaction ids were already issued once across the whole store ([ids](storage.md#ids)); "unique across stores", which he had also asked for, he withdrew: "Okay ignore the 'unique across stores'". What orders things is a transaction's time, `[:db/txInstant, tx id]` ([history](storage.md#history)), never the id alone. Making the transaction's time its id (microseconds, issued from the ids page) was assessed and not done, on his word: "If we don't need this and it's going to cause chaos don't just let me wreck things". It would need Datahike's id bands redrawn (``datahike.constants``: entity ids `[1, 2^29)`, transaction ids `[2^29, 2^40)`, content ids `[2^40, 2^60)`; milliseconds do not fit the transaction band and nanoseconds do not fit a fixnum) and the store erased, while ordering by `[time id]` needs no change to a dependency. Stated in [storage](storage.md#history) and [data modeling](data-modeling.md#a-ref-or-a-value).

### A new session starts at the commit at or before a point; a read is exact at any transaction (owner, 2026-11-11)

Asked what "resume from any point in time" means in the store: "Okay tell Point that works and we can build on this" (relayed by the Deep fixes session through the managing session). What it rules: a point is a branch and a transaction; reading the state there is exact at any transaction, since history is kept; a new session there is a fork at the commit at or before the point, a separate session on its own branch that copies nothing; and nothing is stored on a transaction to find that commit ("We don't need to store data that we can derive"): each commit records its parents and its last transaction, so the commit at or before a point is derived from the branch's line, and any lookup made from it is held in memory, never stored (not built: `seon.ctx.wire/at-t` answers the earliest commit holding a `t`, at or after it). A fork needs a commit that still exists, and collection removes the commits nothing keeps ([collection](storage.md#collection)), so a point can expire while a read at it stays exact. Stated in [turn](turn.md#an-agent-goes-back-to-an-eval) for the one place it is wired today, a person's Back to here.

### Root is the agent env at the top of the hierarchy; the system is not "root" (owner, 2026-11-11)

"Let's be more clear and say that "root" refers to an agent env that is the top of the agent hierarchy and we use parents and subagents and keep this based on the literal data model that is stored in the db as refs and data"; and of everything else: "I want to stop calling anything that the system is doing without an active env as "root". The system is what a bunch of flow processes and static code that is called and it's on "main" which is just a branch like any other right?" (both relayed by the managing session).

- **The system** writes and reads on main: its writer, its reconciler, its fork server. Main is a branch like any other.
- **A sub-agent** of an agent is a member of its `:seon.agent/agents`. **The parent**, for a task, is that task's `:seon.task/started-by`: an agent can be in two agents' sets, so "its parent" is asked of a task, never of the agent alone.
- **Root** is the agent env at the top. The stored refs cannot say that yet: read on the standing store that day, the agent keyed `"root"` holds its key and a ref to the system entity and nothing else, no agent's `:seon.agent/agents` holds it, its task `request/root` names no starter, and the user (an agent entity with no env) holds one started agent that is not it. So the top is told by the reserved key today. The one-tree trial closes it: the user starts root's session, and root is then the agent with an env that an entity with none started.

Counted that day by a search for "root" as the subject of a system act: about 157 lines of the wiki and AGENTS.md, 201 of `src` and 27 of the schema files say root where they mean the system; none is swept by this entry. In the data it means the planned `:seon.root/*` to `seon.system/*` rename, `:seon.agent/root` going, and the reserved agent key. Stated in [vocabulary](vocabulary.md#words-we-do-not-use).

### Ids are unique across every branch; time orders, not transaction ids (owner, 2026-11-11)

"I want all IDs to be unique among all the branches and for us to use time for ordering not TX-ids" (relayed by the managing session). It replaces the managing session's answer of the same day (order by the transaction's id, its time for showing). A recorded time used to sort data is data, so it is no clock deciding ([clocks](clocks.md#time-measured-recorded-never-compared-to-decide)): a wait or a judgment by a clock stays ruled out.

What the standing store held that day, read at the REPL across main and three agents' branches: no transaction id, no entity id and no eval id names two different things on two branches (the store issues them from one counter); a transaction's time is `:db/txInstant`, a millisecond; along main's 400 transactions and a branch's 158 it never went backwards and no two neighbours shared a millisecond. Not yet answered: what breaks a tie when two do, and that an import must write the original time or an imported conversation orders by when it was loaded. Stated in [the plan](plan.md#the-plan), part 1, as the open step.

### A sync merges everything that does not conflict; no list of attributes decides (owner, 2026-11-11)

Offered a smaller sync shaped from lists of attributes: "I don't want a hand made list of attributes to sync. I want all non conflicting data to merge and conflicts to be fixed before we merge anything else. We can hold off merging anything until the conflicts are resolved too if that's safer." (by the question tool, relayed by the managing session). Everything a branch holds merges unless it conflicts; a conflict is resolved before anything else merges; holding a whole turn's merge until its conflicts are resolved is acceptable. Today's merge is decided by such lists (```seon.merge/carried-platform-attrs```, its platform-record test, an empty set of data attributes), which this rules out as the design. Not built; the unlanded `jolt/sync` is not its base. Stated in [the plan](plan.md#the-plan), part 1.

### An attribute nothing reads gets a purpose and a render, or is removed (owner, 2026-11-11)

Of a model call's time taken, stop reason, error count and model, which are written and read by nothing: "If by reader you mean include them in pulls and render them with well written render functions that are focused on why were we are even storing them (if there's no purpose remove them)" (by the question tool, relayed). So each such attribute has one line of purpose and a render that shows it, in a pattern, to whoever acts on it; or it is deleted. Stated in [data modeling](data-modeling.md#before-you-add-something), whose first question it is.

### Messages are ordered by when they arrived, from the pull (owner, 2026-11-11)

Of the branch that orders an agent's messages by the transaction the pull carries, with the recorded requests unchanged by a byte: "Land it" (by the question tool, relayed). Stated in [ctx](ctx.md#the-pattern) when it lands.

### The command and namespace names that say root change with the erase (owner, 2026-11-11)

Of `bin/root`, `seon.root`, `my.root` and the `:seon.root/*` keys, after the vocabulary ruling: "Later, with the erase" (by the question tool, relayed). The wiki's wording is corrected as pages are touched; the command, the namespaces and the keys are renamed together, at the erase.

### A merge replays the source's transactions on the branch that receives it: a fact is placed by when it arrived (owner, 2026-11-11)

A fact has two times: when it happened, and when a branch learned it. The model's request must keep the second, or a fact that arrives late is placed in the agent's past and every byte after it moves. The Optimization, Deep fixes and Data model sessions first agreed a merge as one new transaction of the receiving branch; the owner corrected it: "I disagree. We should never record two transaction times that are the same" (relayed by the managing session). So, agreed by the three after that:

- a merge writes the source's net change since the base grouped by the source's transactions, each group as its own transaction of the receiving branch, in source order, all in one commit or none;
- each such transaction takes a new id and a new time, strictly later than the one before (arrival), and keeps the source's own facts about itself (which branch wrote it, which eval made it);
- what it brings keeps its entity ids;
- so a conversation orders by the transaction a pull carries (`:at`) alone, on any branch;
- a conversation's own requests are made again on the branch that lived it, as of each call's basis commit.

Datahike's merge takes the transaction data its caller gives it, so this is the caller's choice and needs no change in the fork. Not built: it is the rule the one-merge trial is held to. Its cost against one collapsed transaction is being measured. Stated in [the plan](plan.md#the-plan), part 1.

### No derived value is stored; a judgment is run on the db state (owner, 2026-11-11)

Asked whether a merge may skip values that are derived: "But we aren't storing derived values so what's the problem? If we are storing derived values stop that. If the branch says it's all good it's because the underlying data that will be synced means it's okay and when it syncs it should still be okay". And of moving them to a cache beside the store: "This shouldn't be a cache fix btw. I'm talking about the 'all good' being run on the db state" (both relayed by the managing session). It restates his ruling of 2026-10-26. So a value derived from the db's data (a test's status, a total, a standing, a score) is computed on the db state where it is read and its write is deleted; the same data after a sync gives the same answer, so nothing is carried and nothing can be stale. An observation the data cannot give back (a measured cost) is a fact, written by one writer under an identity that names who observed it, so two branches' observations never meet in one attribute. The merge therefore needs no rule for derived data. The digest-keyed verdicts ([merging](merging.md#verdicts-are-a-cache)) stay what they are, how a judgment avoids running a test twice. Read that day: a test's status on its branch, a function's summed cost, a task's cost reports and waste, and a first move's score are each stored today. Stated in [data modeling](data-modeling.md#derived-data-is-a-cache-never-relied-on).

### Work, then refine, then review; a refinement is tried from the store's history (owner, 2026-11-11)

"For us this will be a task following their work step. Us doing a refine your previous session context and curate the task so it would have been done better. And then we can test those refinements without losing the previous one because we have db history." And of the fork that reflects mid-task: "What if we take advantage of our ability to fork sessions and make edits to the context? It's cheap from a tokens perspective since it's mostly cached data and we can inject a mini task at the end ... So lets say after 10 turns we do this reflect and improve step". Stated in [the plan](plan.md#the-plan), part 2; not built.

### Reviewed sessions are kept in the repository, in an import area like the tasks' (owner, 2026-11-11)

"Once we have a refined and reviewed session we need to persist those to disk so we don't lose them if we wipe our store." Asked where: "Make it a general import area like tasks and it's in the repo". Stated in [the plan](plan.md#the-plan), part 2; not built, and no curated session exists in a form the import reads.

### Measures accumulate from use; a measurement is one call (owner, 2026-11-11)

Of `seon.cost/measure` calling a function 17 to 60 times at each size: "oh god just remove this. I wanted results to accumulate and become more precise over time naturally as they are exercised in the system. Tests, normal evals, normal system usage." And: "if we know that it needs to be called twice ... do not render a clear number until that is ready". Stated in [cost](cost.md#how-we-measure) once its tip lands (jolt/tests-measure) and [the plan](plan.md#the-plan), part 6.

### Before a compaction a session reflects and reports up, and brings the system up to date (owner, 2026-11-11)

"all agents before they compact should reflect on the work they did ... and report to their parent". And on what it leaves behind: "instruct agents to update docs and tasks in particular and to grep around and find the parts that might be out of date and not just edit an append log.md. I want to move away from just putting all the information in one place and into distributing the updates to bring the system up to date before each session is concluded. Over time I want to stop drift." Stated in AGENTS.md §2 and [sessions](sessions.md#the-way-improves).

### Live agent runs harvest the system's problems; their good refinements reach disk (owner, 2026-11-11)

"So context is basically farming for problems while they are testing. System improvemetns they should integrate and tell specific agents what they are seeing. Not teach to the test, recognize systemic failure and adapt over time". And of the refine step: "During the refine page agents are refining the tasks and docs. If after our claude agent reviews the work it's good additions I want those synced to disk. This isn't a metric I want to actually solve the tasks and improve the system." Stated in [sessions](sessions.md#live-agent-runs-find-the-systems-problems).

### Embeddings stay in the store (owner, 2026-11-11)

Of a request to hold them beside the store, withdrawn: "Oh it's more robust than I thought ignore my previous request I think this is fine?" Stated in [vector](vector.md#a-vector-is-its-own-entity).

### Check-step bounds are signals, not hard constraints (owner, 2026-11-11)

"The step bounds are signals not hard constraints." Stated in [merging](merging.md#a-merge-runs-what-the-change-reaches).

### `bin/root` becomes `bin/system` (owner, 2026-11-11)

Asked by the question tool whether the command takes the system's name, his pick: "Rename to bin/system". To be stated in [vocabulary](vocabulary.md) by Optimization's rename tip, which follows the erase ruling above.

### When lines of work collide: proving, the product loop, renders as data, Jolt fiber safety (owner, 2026-11-11)

Asked by the question tool for an order, his pick: "B, A, D, C", that is B all proving through the system, A the product loop, D renders as data, C Jolt fiber safety. Stated in [the plan](plan.md#now).

### A stuck agent is stopped, analyzed and fixed; repeating is a breakdown in the model (owner, 2026-11-11)

"Stop it and analyze and fix. Repeating is a problem with our context and instructions." And: "Repeating is a breakdown in the model and it's not a hard vs easy problem issue". To be stated in [agent](agent.md) by the Context session.

### The system message is only for what every agent is told (owner, 2026-11-11)

"Most info about an agent should be pull pattern and render functions. The system context is literally only for universal explanations that every agent in the system should get." And: "Any begging or pleading ripped out. Less is more as it's just more rope to hang ourselves with." To be stated in [model](model.md) by the Context session.

### The Data model session is in charge of schemas and pull patterns (owner, 2026-11-11)

"I want them in charge of our data model updates for schemas, pull patterns and working with web and context (both focused on those outputs) to re-arrange data so the pull is simplified and the context gets more expressive"; and "I want the solutions to be based on data so make sure the data model agent is consulted." Stated in [sessions](sessions.md#sessions-own-their-areas).

### The page reaches the model as evals, at commit points (owner, 2026-11-11)

"I want the @ctx and the hiccup displayed in full at some point in the agents evals (usually first, but we can introduce it later if we want to do more caching and the model has already moved past that point) ... These are functions we are evaling at whatever commit points we want so we can correctly rebuild if I compact ... without busting it's cache unless we are intentionally busting the cache with a compaction." To be stated in [ctx](ctx.md) by the Context session.

### The model reads data, and the render's output as hiccup data (owner, 2026-11-11)

"I want data and not rendered html shown the model"; "the hiccup contains the output of the render functions and the model needs to see this for most of it's context"; "(the model of course gets the stripped out CSS and the hiccup as data version)". To be stated in [render](render.md) by the Web session.

### The website renders the hiccup by default (owner, 2026-11-11)

"The point of the website is to render this part ... Can we by default render the hiccup?" To be stated in [render](render.md) by the Web session.

### A diff is an editscript, and a delete is simple (owner, 2026-11-11)

"I want editscript diffs and I think simpler deletes since the agent already has the data in their previous context". To be stated in [render](render.md) by the Web and Context sessions.

### A pull pattern is stored with the agent, rendered by it, and different for each agent type (owner, 2026-11-11)

"the pull pattern should be stored with the agent and the agent should be rendering it so it knows about it and the pull pattern should have a concise explanation of what this means and the agent can literally swap their ctx atom and update it and we will re-render the UI and their context for the next turn"; "The agent can break their context. We include a warning and a good example of how to insert/update and not replace it and I want a way to detect that there are problems"; "we can make a schema for a functioning agent ... and that can include the pull pattern has required parts that we can check"; "The schema is different for different agents I think for the @ctx schema. It's the result of the pull so it should be defined for each agent type and that's what makes it that agent". To be stated in [ctx](ctx.md#the-pattern) by the Data model session.

### What a render may read of the page is the previous point's tree (owner, 2026-11-11)

Asked by the question tool what a render may read of the page, his pick: "The previous point's tree". To be stated in [render](render.md#what-a-render-is-handed) by the Web session; that section says "never" today, which this ruling replaces.

### A render's output has rules, but its content is not constrained (owner, 2026-11-11)

"The page hiccup can have rules I suppose, but we don't want to constrain the content". To be stated in [render](render.md) by the Data model and Web sessions.

### A text field is Markdown (owner, 2026-11-11)

"In general if it's a text field it's assumed to be markdown." To be stated in [render](render.md) by the Data model session.

### Break the agent's physics and the agent breaks; keep auditing (owner, 2026-11-11)

"If we break the \"physics\" of what the agent is expecting like the agent can't see it's context and we keep telling it to look at the rendered function it breaks agents. We need to keep auditing and finding our failures and fixing them". To be stated in [agent](agent.md) by the Context session.

### Reasoning is the development microscope; the system is built to work without it (owner, 2026-11-11)

"I want to keep tuning the model for deepseek flash without reasoning, but let's doing some tests with reasoning turned on ... The goal is to maybe understand via the reasoning what the model is finding confusing and then we fix it and demo it on no reasoning"; "it's gold for understanding how clear we are about the system. They can easily mine this and iterate with the prompt". To be stated in [model](model.md) by the Context session.

### Disregard an LLM's estimates of time (owner, 2026-11-11)

"Disregard all LLM estimates they are always based on human output and we'll reach it much sooner." Stated in [sessions](sessions.md#the-managing-session).

### An agent's fibers take the safe paths, and a guard is a finding, not a fix (owner, 2026-11-11)

Asked by the question tool whether an agent's fibers take the safe paths at 11 ticks an element for a lazy sequence instead of 8, his pick: "Pay it". And on guards: "This sounds like a guard. You can use this to find the issue, but i want the classes eliminated so the guard is unnecessary." To be stated in [forks](forks.md#jolt) by the Deep fixes session.

### The machine stays cool: runs are throttled by load, and worker limits combine (owner, 2026-11-11)

"My laptop is cooking my lap. I want load based runs that we are throttling via the flow channels"; of worker limits: "We need a config options for a fixed max number and a load max and core max I think and any combination has a solution". To be stated in [machine](machine.md) and [config](config.md) by the Tests session.

### Health checks watch the queues and write to health/problems (owner, 2026-11-11)

"We can monitor the system though and can detect when this happens ... let's schedule adding good checks in when we deploy it and we have it write to health/problems." To be stated in [health](health.md) by the Tests and Optimization sessions.

### The standing system and its store are for use; erasing it stays his (owner, 2026-11-11)

Asked whether agents may work on the standing store once the merge gate is right, since it was treated as read-only: "What? the database is read only? Why? No. Just use it!" Agents work and merge on the standing system, sessions attach and evaluate through the MCP tools, and the tree branches of a session live there; the rule of read-only evals is gone. What stays the owner's is erasing the store. Not yet stated in AGENTS.md §0 or the wiki (the managing session is to change them).

### The test suite is for the online version, in the db, and the workflow is the agents' (owner, 2026-11-11)

"Confirm we are building the test suite for the runtime version of seon not the offline test version that's processing files, but the online version that's processing our code in the db". And, of the worktree path a session had been proving through: "Nope. I don't want the worktrees version that's a temporary workaround for you and your agents, but we are designing this for seon agents or claude agents using the mcp tools". So the branch in the db is the program under test and an agent on it works through `seon_attach`, `seon_eval`, `seon_prove` and `seon_done`, its changes evals and facts, never files. Not yet stated in the wiki.

### Worktrees stay for real work; one session is pushed through the MCP tools to mine problems (owner, 2026-11-11)

"Yeah keep pushing a session through and use it to mine problems. Don't slow down real work though I know your agents prefer file editing. Just keep the worktrees and index it like I said into a branch and then you can run it from the mcp tools". A worktree is imported into a branch, then loaded and run from the MCP tools; every fall-back to a file is a finding mailed to the session that owns the cause. Not yet stated in the wiki.

### Seon's own calls carry an intent and a good model message (owner, 2026-11-11)

"Seon's own calls should carry an intent too. So when we have an openai-compat render we need them for all the parts. We should have a good model message too. Set a good example for the agent". And, of the tool's name, asked by the question tool: "repl" (he had asked "Isn't the model tool called eval? Update all of the old docs"). Stated in [render](render.md) (the page as a call Seon made) and [model](model.md#one-tool-repl).

### Seon's calls are stored evals, and deterministic (owner, 2026-11-11)

First, of the page and diff calls: "I just want them to be dynamically there always even if we wipe the context so it feels like rendered to me and we should be able to render all the way down into the model's generated tool calls and responses right?" Then, of whether they are stored: "if they are evals of course we store the results just like a normal eval"; "return useful data the agent can even reuse ... by referencing the result/id"; and "I just want it to be deterministic and to work" (relayed by the managing session), which made equal values print the same bytes. Stated in [ctx](ctx.md) (an eval of `@ctx` is kept as its basis); the system making the calls as stored evals is not yet built.

### A render shows that context exists and how to pull more (owner, 2026-11-11)

"Don't display the functions in full. Make sure the renders are all smart and adaptable. Agents need to know that context exists and we need explanations and short instructions that the agent can use to pull in more context." Not yet stated in the wiki (the managing session's reading: each render says what it shows, how much it left out, and the form that brings the rest).

### An agent's entity holds what is present; a render derives the rest (owner, 2026-11-11)

"If just displaying the agents ctx value is too huge then we are shoving entirely too much data in the agents record and we need to have the data model agent do an audit for data that needs to be present vs derived in a render function". The audit is the Data model session's. Not yet stated in the wiki as a rule.

### The first point shows `describe` of the agent's context, a general skill for any value (owner, 2026-11-11)

"Okay so we are going with this. Instead of dumping the whole @ctx value we are now doing this more metadata approach and context is to iterate on this until they get signal for an agent understanding what the fuck we are talking about and updating data in the @ctx atom". Before it, of how: "I mean we need to make a function to render this and we just call that instead of doing @ctx"; of its reach: "We explain that the @ctx atom output can be large, but here's how the agent can extract metadata about the value! How about that we teach them a general skill and learn how to fish on any other value."; and "Can we lookup schemas and docs through it too so it really is a good description of what the data is without displaying the data?" The function is `describe`, on any value. The default printer prints a value as the value. Not yet stated in the wiki.

### A tool's answer is the plain value, its instructions printed with it from its schema (owner, 2026-11-11)

Having read two forms side by side: "oh I like yours better already", of this one: "the value is the plain entries, and the instructions are printed with it from its schema." Not yet stated in the wiki.

### An agent is set up in its task's namespace on purpose, and the namespace says so (owner, 2026-11-11)

"I think we need to be clearer that we are setting them up in that namespace because we are auto pulling in context from that namespace. So we need a namespace render that pulls in useful data for the agent about their namespace and be clear that any def and defn defn- deftest schema register! and .. I forget what else it works as a real repl works." Not yet stated in the wiki.

### Every state converts to a completed task (owner, 2026-11-11)

"Why is the plan stale? Every state should be convertable to a completed task. Where are we with the context edit agent course correcting and restarting the agent with better context and then reviewing it (the chained tasks)?" Stated in part in [tasks](tasks.md#a-refine-curates-its-own-task); the rerun is not yet automatic.

### The fiber fix lands with its bytes named (owner, 2026-11-11)

The fiber tip costs +18% bytes (16 MB on a lazy-heavy eval) beside its +5% ticks, which he had already agreed to pay ("Pay it", above); asked about the bytes, he answered "sure" (relayed by the managing session; no further quote kept). It lands as a named regression with its owner. Stated in [forks](forks.md#jolt) by the Deep fixes session.

### AGENTS.md is not an append log (owner, 2026-11-12)

"You don't have to update AGENTS.md at the time. I do not want a long append log of instructions that won't be evergreen. Definitely check it for out of date information and don't put in things that aren't important that agents might not find in the wiki on their own". Stated in [sessions](sessions.md#the-way-improves).

## 2026-11-10 rulings

The owner's rulings of 2026-11-10, on how the work improves and lands, on what reaches main, on how pages are built, on how the machine's load is judged, on what an agent reads and on where a private helper's contract is checked.

### The store may lose its last commits, never corrupt or exhaust the machine (owner, 2026-11-10)

He first chose a safe copy of the store beside the fast write, then replaced that the same evening: "How about we just fix the corruption issue so we just deal with data loss in the worst case?" (to the session that owns the dependencies), and "I changed my mind and want to see if we can just fix the corruption issue with LMDB since we already own the dependency" (relayed by the managing session). After the machine ran out of semaphore sets that night: "Losing data is fine, but semaphore exhaustion and corrupt storage is not okay." So the fix is in our LMDB fork: its locks are record locks the kernel gives back, and a store opened ``MDB_SAFENOSYNC`` rolls back to its last sync after an OS crash, never past it. Stated in [failure points](failure-points.md#lmdb-locks), [failure points](failure-points.md#lmdb-crash-safety), [storage](storage.md#durability-is-continuous-group-commit) and [forks](forks.md#lmdb).

### A run far past its measured time is ended, after its evidence (owner, 2026-11-10)

The fourth clock exception, relayed by the managing session at 20:35: a script may end a run that goes far past its measured time, after capturing the evidence (sample, logs, process list and the Seon state: root's stall check, each agent proc's), reported red with a typed reason, never retried; the cause still gets its task. Before it, a stuck step ran until someone noticed. Stated in [clocks](clocks.md#time-decides-the-owners-exceptions): `bin/check` ends a step past `:seon.config.check/step-end-factor` times its median measured run (STEP-OVERRAN), after `bin/evidence`.

### A write to main is answered after the store holds it (owner, 2026-11-10)

Asked with the question tool (relayed by Point, 2026-11-10 ~19:40) between answering root's write at its apply, with root's api storing what ran ahead before any request reaches an agent, and answering after the store commit; the owner chose the second (in Point's relay: "answered means every process can read it"), one rule, no gate at root, no class of missed reads. Stated in [storage](storage.md#durability-is-continuous-group-commit).

### The best known way, improved (owner, 2026-11-10)

"You do things the best known way until someone comes up with a better way and that becomes the standard 'best known way' of doing things." "Pain points can become well polished and intentional features." "We can iterate and on each run the system gets a little better." The wiki and AGENTS.md are the way, written down; a pain is a finding whose fix changes the way itself (a script, a check, a wiki section) and deletes the old one. Stated in AGENTS.md §1 and docs/sessions.md#the-way-improves.

### Small changes land in batches, proved once (owner, 2026-11-10)

"Can we batch them together? ... Can we force merge the fixes into all the branches and have them at least run better code?"; "I don't want 100% separated work separately running all the tests for each little change." Sessions check their change and hand the tip over; the managing session merges the ready tips, proves once and lands. Stated in AGENTS.md §3 and docs/merging.md#the-managing-session-lands-a-batch.

### A task a Seon agent finished reaches main by its owner's review (owner, 2026-11-10)

"If the task is done well integrate it and merge to disk and remove the task." "I want the results reviewed by a competent agent in their speciality so they know the quality is good and if it's almost done right just fix it and sync it to disk and remove the task." A merged task whose files main does not hold is an `:unsynced` problem; the session owning its area reviews it and brings it to a branch with its entry removed; `bin/update` lands nothing of the standing root's own. Of the first six reviewed, three were not done as merged (a proof red by name, a tile drawing data nothing produced, a proof that could not fail). Stated in docs/source.md#a-merged-task-reaches-mains-files and AGENTS.md §3.

### A root catches up with its checkout at start (owner, 2026-11-10)

"I think I may have introduced chaos into the system with the boot resume from db and not re indexing from disk. While most development is happening with claude agents from the outside if it makes more sense change the default to always be building the latest binary off the code and indexing it ... This should be done efficienty too so if very littel has changed it doesn't take forever to start"; then "don't let this decision cause even more chaos. Address the issues you are seeing your agents facing." A small step on the one importer: a resumed boot indexes the files that changed since main last indexed them; nothing was deleted. Stated in docs/source.md#a-boot-catches-up-with-its-checkout.

### Pages are built from data by one walk (owner, 2026-11-10)

"No more hand drawn lists and custom shit. If I come up with a new agent type and I attach a bunch of data to it's entity and there are render functions it should just work everywhere. ... Root, the user, and normal agents should be different based on their data and their connections, not separate special code." "Why can't we write render functions that recognize 'this is a top level render it's not a leaf so it's not going to be rendered as a tile'"; "don't flatten, the location in the pull pattern has meaning"; on the chat's order, "Sort them by time?". One walk over the pull, nested like the pull; each entity drawn by its own schema's render, which knows only whether it is on top or nested; the chat is its entities in the order they were made; no declared places, no kinds, no read mark. With it, the same day: root's agent is made at boot (reversing 2026-11-08's "made by the first message"); an example session is sent to the model as real user, assistant and tool messages and drawn by the same renders ("not learn a text exampled but see exactly how to structure the tool calls"); a mechanical message's sender is the system entity, not an agent ("Why do we need a full agent for this?"). In flight on branch jolt/pages; the task is seon.render/a-model-render-draws-each-turns-diff until its own are landed.

### The forks follow upstream: theirs when almost as good, ours written to be offered (owner, 2026-11-10)

Owner, 2026-11-10: "Make sure deep fixes is merging all the fixes from all upstream dependencies"; "If there are two solutions and theirs is almost as good as ours choose theirs"; for Datahike, "start with the latest datahike and just overlay only the ones we need over it"; "I don't want to open upstream patches until we are sure what we did was correct and the solutions are proper high quality implementations"; "make sure all the code is well written and matches their style and coding guidelines for both datahike and jolt. I highly respect all the developers working on these projects and I don't want to waste their time or have them complain that I submitted a low effort pull request that is a nightmare to review and understand." Stated in: docs/forks.md#following-upstream.

### Every session proves its branch before handing it over (owner, 2026-11-10)

"Please task the test agent with coming up with a solution where we can catch these bugs more reliably. They are to ideally make the entire suite run very fast so every agent can fully check their work." Relayed by the managing session with the day's escapes (five changes handed over "green" on hand-picked namespaces turned whole proves red); the Tests session's plan, approved: one command checks a branch, the whole suite at the cost of what changed. That command is `bin/prove` (it runs what a change reaches, every check step a `src` change reaches, and reuses every green at its digest), not a second script beside it. Replaces "nobody proves a small change alone". Now stated in: AGENTS.md section 3 (the one way, item 2), [plan](plan.md).

### A test that starts a root takes what root runs (owner, 2026-11-10)

Asked with the question tool by the managing session, the Tests session's evidence beside it: on main's reach graph, of 233 process tests 7 reached `seon.writer/commit-batch`, 9 `seon.reconcile/start` and 10 `seon.fork-server/main`, though each starts a root that runs all three; a change to root's writer reused 226 of their greens, and five of them were red in the batch. The owner chose to reverse 2026-10-27's "a root's boot is no entry" ("no test includes all of src"): a process test's digest takes what root runs. Now stated in: [source](source.md#tests-run-by-reach) (a test that starts a root takes what root runs).

### The test system runs inside Seon; the outside is thin wrappers (owner, 2026-11-10)

"I want the test system focused on seon agents running it from the inside. I want a better system than our confusing worktrees and agents churning the codebase and re-running everything all the time. I want maximum test reuse across all branches. If it's the same code and the seed is the same don't re-run shit. How can we take our fragmented system and fix it in seon and then have thin wrappers calling into it from the outside? ... minimal duplication in infrastructure just what we need for our agents to run the system efficiently." Two rules with it (owner, 2026-11-10, asked with the Tests session's picks): a worktree session's changed files are written as a branch of the standing store (deleted when it lands or is dropped), and root proves that branch as it proves an agent's, so `bin/prove` and `bin/land` are thin wrappers over the one service; and a test's digest takes the code of the forms it reaches without their docstrings and comments, the tests that read docs or the whole program taking them explicitly, so the same code is the same digest on every branch. Now stated in: [what a test takes of a form's text](source.md#what-a-test-takes-of-a-forms-text) (digests of code); [merging](merging.md#a-merge-runs-what-the-change-reaches) (the rest, as each step lands).

### The test service runs what the cores hold, one test at a time, and waits on channels (owner, 2026-11-10)

"I don't want 'shards' anymore. I want the flow system that is always only letting the tests run that we have cores for and is updating the db as they run so we can immediately return the results to agents and short circuit the extra queueing and waiting and re-running." "Never do timers if we can setup blocking channels." And against a lock in the shell runner: "Why are they all independent if the db is shared and is holding the test results?" Stated in [merging](merging.md#the-test-service) and [the plan](plan.md#the-plan), part 5.

### A change that moves every verdict is run whole once (owner, 2026-11-10)

"The next time a change invalidates all the tests please just run it once so the other runs don't duplicate all the tests." Before a landing that moves all or most digests (a dependency pin, what root runs, the runner), the managing session tells every session to hold its proves; the landing's own prove is the one whole run; the others merge main and reuse its greens. Stated in [sessions](sessions.md#the-managing-session).

### No agent on the costliest model without the owner's yes (owner, 2026-11-10)

"Do not launch any fable agents without my approval. I literally can't afford it." A session says which model a helper or a new session would run on and gets his yes first; a helper takes the cheapest model that fits its piece. Stated in [sessions](sessions.md#no-fan-out).

### LMDB's corruption is fixed in the fork we own (owner, 2026-11-10)

"I changed my mind and want to see if we can just fix the corruption issue with LMDB since we already own the dependency" (relayed by the managing session). The task he set it: "our LMDB must not corrupt or exhaust the system; losing the last unsynced data is fine." Landed as batch 35: the store's locks are the kernel's and end with their process, and a crash with sync off loses the last commits and never corrupts. Stated in [storage](storage.md#durability-is-continuous-group-commit).

### A test earns its place or is cut (owner, 2026-11-10)

"If that test isn't god's gift to the world fucking kill it. Cut all bullshit tests." (relayed by the managing session, 2026-11-10 night). Stated in [testing](testing.md#a-proof-is-focused).

### Load is judged by cores and free memory (owner, 2026-11-10)

"I have 18 cores and load is 8". The "load under ~8" number came from the 16 GB target and was holding landings and proves at load 8-11 for nothing. Heavy runs go niced and in parallel while nothing is starved, judged by the machine's cores and its free memory (owner, 2026-11-06: "do dynamic balancing"); the 16 GB MacBook stays the target Seon must fit. Stated in: AGENTS.md section 8, docs/machine.md#builds-and-checks.

### The agent's history is native, and what is looked up goes through the hiccup (owner, 2026-11-10)

"The UI version of the mesesages, tool calls etc, is a repeat. ... If we want to identify the "parts" from the hiccup and then render them differently for the model into the openai-compat format that's fine. But I don't want to fuckup the agents context. ... The agent gets raw info like it's message and eval calls in it's native format and anything that's processed or dunamically looked up should go in the hiccup render (make this intermediate and not HTML so it can render well into each of it's output for the model and for the html)". And the same day: "I want the core openai-compat format to be normal system, user, assistant and tool calls and that to be where the agent understands it's history -- not from the hiccup web render ... just make it a [fn-call args children] style vector that will be expanded in the html version ... remove style info by default ... make sure the agent knows what function was called for each part of the hiccup render so they can edit it to change the page." It stands over the 2026-11-09 reading that a render deposits message values into the hiccup. Stated in: [render](render.md#what-the-agent-reads-is-its-native-history-and-everything-else-through-the-hiccup).

### Private helpers: tests only (owner, 2026-11-10)

"Private helpers: tests only" (asked with the question tool). Every function still has a contract and every contract still generates; a private function's contract (`seon.contract/defn-`, a var with `:private`) is checked at its definition, in tests and in the gate, and not on each production call, while a public function's stays checked on every call. Measured: a contracted call costs about 70 engine ticks to check; a small value's print was 25.8k ticks with the contracts on and 12.9k with them off, nearly all of it private per-node helpers; a whole cold request is about 3% contract checking on the in-memory store (3-10% by request). Stated in: [checks](checks.md#contracts-armed-at-definition).

### A read never writes (owner, 2026-11-10)

"A read never writes." Reading an agent's context to look at it commits nothing, records no model call and no eval of the read; the outside model's own read is its turn and is recorded as one. Stated in: docs/turn.md#an-outside-agent (proof seon.server-test/a-context-read-that-only-looks-writes-nothing).

### The model's history is the pull's data; what is computed is computed in the hiccup render (owner, 2026-11-10)

"All rendering that is pulling new data and processing it should happen at the hiccup render function step. I don't want what's shown on screen to dramatically differ from what the model sees." Then, asked where the history comes from: "Every render function gets the current db, the @ctx full pull data, and I think the current render tree? This allows any function to get whatever data it needs to do the proper render. So where are the messages and other parts that are data? In the @ctx pull data". So a history entity's native messages are made from its own pulled fields by its `:seon.render/openai-compat` render, never converted from hiccup; whatever looks data up or computes does it once, in the hiccup render, which the page shows styled and the model reads stripped. Stated in: docs/render.md#what-the-agent-reads-is-its-native-history-and-everything-else-through-the-hiccup.

### Three request cost proofs land waived (owner, 2026-11-10)

Asked whether pages from data (step 1) waits for stage 2b's request cost proofs, red since 2b at its base: "Land now, three waivers". `seon.render-cost-test/a-cold-request-draws-what-the-model-reads`, `seon.render-cost-test/drawing-latest-costs-one-request` and `seon.ctx.wire-cost-test/a-requests-cost-is-its-records` each carry an `:overrun` naming task `seon.render/a-model-render-draws-each-turns-diff`, with what each measured at its largest size; no bound moved, and each waiver comes off with that task. Stated in: docs/cost.md#an-accepted-overrun-and-its-ratchet (the rule), the three proofs' comments (the numbers).

### An entity is held at every path the pull reaches it (owner, 2026-11-10)

Asked about the stubs ctx held at every place an entity was joined but the first: "I'm pretty sure this is a self inflicted pain point and there's a simple solution please work with web and the other agents and find it". `seon.ctx/build` held each entity once and wrote `{:db/id e}` at every other path, and code across the renders undid it (fill, held-whole, a view resolving stubs was next); now it holds a joined entity whole at every path its pattern pulls it, each by that path's pattern, as `d/pull` does, and a swap! edits an entity by its `:db/id` at any path. Stated in: docs/ctx.md#the-pattern (with stage 2b, jolt/everypath).

## 2026-11-09 rulings

The owner's rulings of 2026-11-09, on one system of agents, rendering from data, the user and root. Stated in: docs/users.md#one-system-of-agents-the-design-agreed-2026-11-09, staged in docs/users.md#the-stages-agreed-2026-11-09.

### No hacks: adjust the system (owner, 2026-11-09)

"I don't want hacks I want to adjust the system so it works properly" (from session memory, 2026-11-10; unverified wording). A fix removes the cause: never a special case, a retry, a hand-kept list or a moved verdict, and its report tells what was verified from what is inferred. Stated in: AGENTS.md section 3 (the method, step 4).

### Understand before writing (owner, 2026-11-09)

"don't write data until you understand it". While a design is open, its findings stay out of docs and tasks; the wiki is written when it is true, and then in the commit that makes it true. Stated in: AGENTS.md section 7.

### Questions go through the question tool (owner, 2026-11-09)

"ask questions you are awaiting answers for with your questions tool" (from session memory, 2026-11-10; unverified wording); before it, 2026-11-06: "lets do a round of using your questions tool and give me background" (from session memory, 2026-11-10; unverified wording). One round, each question with its background, the session's pick first. Stated in: AGENTS.md section 2, docs/sessions.md#asking-the-owner.

### A red verdict is never deleted or moved (owner, 2026-11-09)

The owner's ruling, relayed by the Optimization session after two stale reds were deleted by hand: a fixed tree gets a new digest, and a red that stays at a digest that cannot move is a gap in the digest. Stated in: AGENTS.md section 3, docs/merging.md#verdicts-are-a-cache.

### One system of agents, and no new concepts (owner, 2026-11-09)

"We are designing one system for agents that we are re-using everywhere"; "make sure you repeat back to me the plan on how we are NOT INVENTING ANY NEW CONCEPTS HERE. Explain the building blocks that we are resuing everywhere and how it's the data and the pull that makes each part different"; "We've talked about a lot of ideas so we need to carefully stage it and agree on dividing up the work". Stated in: docs/users.md#one-system-of-agents-the-design-agreed-2026-11-09.

### A page is the pull drawn recursively; no layouts (owner, 2026-11-09)

"No layout-of. No seon.render/layout I want to remove all the manual layout work. A hiccup render can override anywhere we are walking and rendering recursively. It's just more data that we are detecting and rendering"; "Entities should have renders IFF we want them rendered. But why are we attaching data to the agent's entity if we don't want it rendered?"; "Ditch the 'draw' term ... We need a simple way from the system process to render data and get back hiccup". Stated in: docs/users.md#one-system-of-agents-the-design-agreed-2026-11-09.

### Separate definitions, linked by refs; accretion (owner, 2026-11-09)

"I want the separate components that we need to process maybe separately as separate definitions that are linked"; "I'm not saying make everything required. That's bad data modeling. Clojure and datomic are designed for accretion. If the data is there, it's validated"; "There are no 'rows'" and no "record": entities, attributes, values, refs. Stated in: docs/data-modeling.md#things-relate-by-refs.

### An agent renders its own context; the whole render each time (owner, 2026-11-09)

"we can have the agents rendering their own context and just sending that back? Do we need the :op made up protocol or can we just return the hiccup directly?"; "since we are sending everything every time it's okay to drop intermediate renders"; "Datastar teaches it's cheap to compress and send everything rather than diffing"; "I want efficient and smart render functions that are always focused on showing the right data". Stated in: docs/users.md#one-system-of-agents-the-design-agreed-2026-11-09.

### The user is an agent with a branch and a process; root is the go-between (owner, 2026-11-09)

"I want the user to have a process regardless. Anything that's user focused should be handled by this agent too so separate users don't take down the system"; "User should have a branch just like the other agents"; "it's a read only ctx tree. The user isn't modifying anything it's sending the requests to agents"; "root I think is mostly the go between for flow communication and isn't doing anything that can allow an agent or the user to take down the system"; "The user doesn't need to be an agent under root"; the system's entity is `:seon.system/*` ("sure"). The user agent's model and routing are later ("I don't want to tackle the live aspects of the user agent right now"). Stated in: docs/users.md#one-system-of-agents-the-design-agreed-2026-11-09.

### A running system runs off the db; boot resumes from it and reads no file (owner, 2026-11-09)

"Boot's job is to resume the existing system or if it's fresh to index it and get it to the point that everything is running off the db. From that point on we are syncing data TO files not reading from them"; "Of course we can re-index if we want. But I mean as a functioning system concept." A running system runs off the db and writes files from it; a boot resumes from the db and reads no file; indexing from files is an explicit act a person chooses (seeding a fresh store, or re-indexing one on purpose), never something a boot or a running root does by itself. Stated in: docs/source.md#a-boot-resumes-from-the-db, docs/source.md#indexing-a-tree.

### Claude sessions' landings enter the db as data, for now (owner, 2026-11-09)

"We have to accommodate you and your agents who primarily write the updates in files, but this is a temporary system -- I want seon agents writing updates inside the system and pushing them to disk." `bin/land` submits the landed change to root as data, which merges it into main and writes the files back; the bridge goes when Seon agents write the system's updates themselves. Stated in: docs/users.md#the-stages-agreed-2026-11-09 (stage 1d).

### Docs link to code, never copy it (owner, 2026-11-09)

"Don't put code into docs if we can link to the code. I want all the docs readable and renderable from the doc parse and indexing"; "I want clear specs and links to the schemas". A doc names a var, a schema key or a task (docs/references.md) where it would show code. Stated in: AGENTS.md section 7.

### Audit before merging; the code gets smaller; no heroics (owner, 2026-11-09)

"I want regular audits on all worktrees before merging confirming that we are not building hand wired custom code everywhere"; "The code base should actually get smaller after we remove all the custom parts and focus on reusing and refining"; "Do not force the square peg through the round hole. If you find yourself needing heroics and writing a shitload of code we are doing the wrong thing and we need to fix the design. I want algorithmic estimates for all work we are doing and not to accept anything that's outside of a reasonable version of that". Stated in: AGENTS.md section 3 (audit every branch before it lands).

### One pass over the pull renders the page and the model's context (owner, 2026-11-09)

"maybe we render both the hiccup and the agents context as the processing of the pull? By the end of the hiccup render on the agent we have all the data identified and the hiccup formed. Rather than walking the hiccup we can just treat it as the hiccup on the page"; "Then we have the model context deterministic from the pull and we can send back the hiccup as a composable part that the user pull can integrate"; "The pull doesn't need to be in order the render functions for the data being processed can order them. We still have separate render functions for everything"; "Once we know what something is it's just a db query to find what its render function is". Stated in: docs/users.md#one-system-of-agents-the-design-agreed-2026-11-09, item 4.

### What an entity is: recognized as the write check does, the best match winning (owner, 2026-11-09)

"Redo this so we are looking up what something is the same way our tx processor looks up and validates data. That is hand rolled shit"; "I think if an entity is several things the best matching one should win. So a single attribute vs two matching vs more is more specific right?" Stated in: docs/users.md#one-system-of-agents-the-design-agreed-2026-11-09, item 4.

### Render functions take what they read; the cache is never relied on (owner, 2026-11-09)

"I want less magic though so update the my.render/render-ctx function needs to pass in args that likely pass in the pull and whatever else it needs? No magic functions"; "I think this works even without the cache? It's pretty fast right?"; "don't write data until you understand it". Stated in: docs/users.md#one-system-of-agents-the-design-agreed-2026-11-09, item 4.

### A session syncs by default; a private one reaches main only by a save; only valid data goes up (owner, 2026-11-09)

"I do want options for agents to be private (experimental not syncing with main the entire time) and an auto sync feature (likely the default behavior) which is always trying to sync everything that we can except for the conflicts which they have to resolve"; "I like the idea of only syncing data that's not invalid. If we aren't testing something and there's no conflict I guess it just goes through"; "an agent can modify their own view, but they can't modify the whole system without an explicit save?" Stated in: docs/users.md#the-stages-agreed-2026-11-09 (stage 5), task seon.merge/a-session-syncs-with-main-unless-private.

### The user agent generates the page; root relays and renders nothing (owner, 2026-11-09)

"The user is the center of the world in this example. We are pulling from the users entity through root and the other agents to generate the hiccup and all the agents are generating their own context based on their own version of their pulls. So what does root have to do with anything if the user is the one generating the html?" An agent answers a render ask with the components that moved, by id. Stated in: docs/users.md#the-stages-agreed-2026-11-09 (stages 1b and 4).

### A message is delivered when it is on the recipient's branch (owner, 2026-11-09)

"just deliver the message and the message being in the agents branch is the proof of delivery?" No delivery entity and no mark of the turn that took it; that turn is derived from when the message first appeared on the branch. Stated in: docs/users.md#the-stages-agreed-2026-11-09 (settled).

### A cite is a ref to what exists; an opened eval reaches the model (owner, 2026-11-09)

"I think logically it doesn't make sense to cite something that isn't there?"; "You can transact with temp ids and it'll resolve in the transaction. Referring to a doc can create the doc at the same time if it doesn't exist." Files are a cite target; library vars are literals for now; dead citations are refused at the write. On opening an eval: "Model reads it too"; "I always want to see the current state including a waiting state". Stated in: docs/users.md#the-stages-agreed-2026-11-09 (settled).

### A private session takes only what main held when it branched (owner, 2026-11-09)

"The private session only takes from main what is on main at the time it is branched." Stated in: task seon.merge/a-session-syncs-with-main-unless-private.

## 2026-11-08 rulings

The owner's rulings of 2026-11-08, on what the model knows, erasing the store, and how a reply's evals run.

### A session knows its whole area; review is never delegated (owner, 2026-11-08)

"We are moving away from monolithic orchestrators who don't know anything and implementation agents who don't know the big picture"; "Well spec'ed tasks can be handed to sonnet, but the opus agents are responsible for reviewing the code"; "I want you to review all core code". A helper commits on its branch and stops; the session reads every line and lands it. Stated in: AGENTS.md section 2, docs/sessions.md#the-session-reviews-every-line.

### The Web session owns servers and demos (owner, 2026-11-08)

"Okay just take over all responsibility for servers and demos for me"; "yes you own web so you are in control of ports for web". The Web session keeps the standing root and any demo root up and on main; other sessions send the standing root only read-only evals unless the owner says yes to a write. Stated in: AGENTS.md section 0, docs/sessions.md#sessions-own-their-areas.

### What a new design replaces goes with it (owner, 2026-11-08)

"I want this shit gone so it doesn't keep coming back" (from session memory, 2026-11-10; unverified wording). The tasks, wiki text, decisions pointers and session notes of what a design replaces leave in the same change. Stated in: AGENTS.md section 3, docs/merging.md#a-landing-message-carries-its-audit.

### An estimate that is exceeded stops the work (owner, 2026-11-08)

"estimates that if we exceed them we stop and fix them". A plan carries its cost line (what n is, its big-O, the number expected) before code is written, and reality ten times over it stops the work. Stated in: AGENTS.md section 3 (the method, step 2).

### Entity schemas are sets of keys written together, checked on write; one definition each (owner, 2026-11-08)

"All sets of keys that match schemas are validated?"; "we want accretion"; "But yeah get rid of the duplicate definitions for sure"; "Validate the ones that are there"; "Just have the individual ones or if there are configs that must be together just do those"; "The goal of this is to see what shakes out so don't just patch things look for deeper issues"; "One good definition should catch problems". An entity holding any key a schema requires holds them all; maps are open; a function's contract and any whole map cite each key's one schema; a refusal is fixed where the disagreement is, the code, the schema or the fixture. Stated in: docs/data-modeling.md#a-schema-is-a-set-of-keys-written-together; docs/storage.md#the-write-checks-every-entity-it-touches. Work: task seon.db/one-definition-of-each-entity.

### The main tree stays on main; each session works in its own worktree (owner, 2026-11-08)

Owner, 2026-11-08: "Wait what? I thought we were using worktrees?"; "Yeah update the agents file. That's just wrong", of AGENTS §3 telling a session to work its topic branch in the main tree (written 2026-10-28 for one session: "Stop doing separate worktrees for small updates. It's overkill", now superseded). With several sessions sharing the main tree, one's branch checked out there took main from the others: their merges stopped and `bin/update` (which runs only from the main tree on main) could not restart root. Stated in: AGENTS.md §2 (helpers) and §3 (one task at a time).

### In-flight output is one general stream, keyed by an id in the db, and may be lost (owner, 2026-11-08)

Owner, 2026-11-08: "I don't want a specialized system for this in case we want to stream other things. How do we do this properly with effects that are writing to channels without complicating the exisitng database pull and render work? Can we get a stable ID for the streaming point to append to and then we can just fire message update data to a channel?"; "in flight streaming data can be lost without any serious consequences even if we restart the system or are restoring to apoint in time. The db is the source of truth"; approving the plan: "Approve, land it". Any effect writes its in-flight text to one Flow channel, keyed by the id of an entity already in the db; the page appends it to the element the render drew for that id; only the final value is written, and the commit's render replaces the element. The pull, ctx and renders are unchanged; nothing in flight is held or rebuilt. Stated in: docs/render.md#in-flight-output-streams.

### One walk per view, the smallest question each step, output streamed live (owner, 2026-11-08)

Owner, 2026-11-08, approving task ``seon.render/one-walk-per-view`` (retired 2026-11-10, replaced by stage 2a) (Web, Context, Optimization): "Approve, stream live". With it: "we shouldn't be doing 1m things if we only need to check 1 or two things and that answers the question. Have common sense and a principal engineers wisdom"; "datahike has an internal query cache that we can use"; "The cache isn't to be relied upon to save us if we are doing shit inefficently. I can see caching datastar patches … we know when something has changed so it's easy to update"; "We shouldn't be saving rendered data if we can re-render it from a specific point in time"; "I want optimization to review the plans and give algorithmic estimates on how much effort it SHOULD take and how we can get rid of the waste". Every step from the pull to the provider's request is a render on one walk; each step asks the smallest question that decides it; stop lines sit at 2× the minimum work; nothing rendered is stored, and the only cache Seon keeps is the Datastar patch per (view, commit), shared by tabs; a running eval's output streams to watching tabs on a Flow channel and is committed once when it ends. Stated in: task seon.render/one-walk-per-view.

### In active development, the store is erased whenever a change needs it (owner, 2026-11-08)

Owner, 2026-11-08: "I don't give a shit about the existing store data. Erase it and cleanup whenever you need to." "Erase whenever you want. Just warn the other agents." "I mean we are in active development. In production of course you don't just drop the whole store." Stated in: AGENTS.md section 9, [machine](machine.md#disk-stays-bounded).

### An id is the content of the facts that make it (owner, 2026-11-08)

Owner, 2026-11-08: "Don't we have an ID generator that's better than a UUID and we are just prepending characters to differentiate?" "context based IDs seems better than random unless it costs too much to hash?" "Yes, content ids." Then, asked how an agent is named: "If we need an ID to be the same for the same content use the content based ID generation. IF it needs to be always unique use a UUID or content hash based on the UUID if we need the same id generator". So: a thing whose id must be the same for the same content is named by seon.lib/digest of that content (a model call: its agent and turn; an eval: its call and place); a thing that must always be unique, an agent (two spawned for one task start with identical facts and are still two), by seon.lib/digest of a fresh random UUID, the same 16-hex form. Never a counter, a boot id, or characters prepended to tell things apart. And one live process per agent (owner: "One live process per agent"): Back stops the old process before it forks the new one. Stated in: ```seon.lib/id```, ```seon.lib/unique-id```, ```seon.lib/call-id```.

### A reply is its text and its calls; the message is written when there is something to say (owner, 2026-11-08)

Owner, 2026-11-08: "I do want a message in markdown but couldn't that just be the agents normal response and the tool calls are separate tool calls each with an intent and the forms? Is that easier to parse?"; "agreed A/B test and see if we can do the simpler version if not fix the format so it's not so hard to parse"; on the A/B (arm 1 kept a message on 10 of 22 replies, each good, against 118 of 120 for one call with message-md required): "If the messages are good with the simpler version we don't have to have a message for every batch of evals. If the agent knows what it's doing and just wants to update less frequntly that's fine." Each eval is its own call `{intent, code}`; the reply's text is its message, optional. Stated in: [one tool](model.md#one-tool-repl), [the prompt](prompt.md).

### The model's request holds no CSS; an agent reads it where it lives (owner, 2026-11-08)

Owner, 2026-11-08: "ONly the RELEVANT CSS is supposed to be pulled in. Ugh I guess if we don't have agents actively working on the CSS we can leave it out if that's easier"; "We need to make sure we are telling agents about their CSS and how they can search it, view it, update it whatever is the most intuitiveway tto do this from the REPL and it needs to be in the system prompt or in the docs that are pulled into all agents." The page draws every render's rules; the request holds none (it had been about 400 tokens of a small task's 1,900-token first page). A render's CSS is its var's `:seon.render/style`, found, read and changed at the REPL with Clojure's own `meta`, `source` and `edit!`, as the system prompt says. Only the rules of the renders an agent's work changes, in the request, waits until agents work on CSS. Stated in: [style](render.md#style), [the prompt](prompt.md).

### Every eval of a reply runs (owner, 2026-11-08)

Owner, 2026-11-08: "I want us to run every eval. Turns are precious and one fuckup shouldn't punish the other evals. Yes I understand this could cause issues if the next eval is dependent on the previous. I still want to try turning this on". And, of the eval tool's description still saying a throw ends the run: "yeah that's wrong update it. Agent turns are expensive. Still run the evals sequentially but execute all of them. Yes I understand that could lead to incorrect evals. It's okay". The calls run in order, every one; the rule is said once, in the system prompt, and the tool's description points there. Stated in: ```seon.repl/run-calls```, [the prompt](prompt.md).

## 2026-11-07 rulings

The owner's rulings of 2026-11-07, on the disk and the db, on what an agent starts from, on what a merge does by itself, on how proofs grow, on state and data structures, and on users.

### The db is the source of truth; a disk edit imports only when asked

Owner, 2026-11-07: "the db is the source of truth and we should be syncing to the disk from within seon (this is the focus for the system is seon agents working within seon). However, claude agents are working from the outside so we need a mechanism to index and update the db that you and your agents will manually control." A disk edit acts on nothing until a person re-indexes (`my.root/import!`); since 2026-11-09 nothing reads files at all but an index (below). Stated in [health](health.md#every-writer-the-db-is-written-files-follow) and [source](source.md#indexing-a-tree).

### No magic: maintenance is Clojure data on ctx

Owner, 2026-11-07: "I want less special functions and more clojure data manipulation on the ctx atom for maintenance. In the rendering of the mesaages we can include instructions for how they can manage their messages and archive them so they don't just pile up. I don't want any magic in the system." Stated in: [users](users.md#messages).

### A wake allowance controls the loop

Owner, 2026-11-07: "lets configure a wake allowance that increases the max turns and that is the main mechanism that controls the agentic loop." And: "I don't want the message to be the wake up mechanism that's database focused and we need to use flow channels for the actual wake and control of process stattes". Stated in: [users](users.md#messages).

### Messages have no read state

Owner, 2026-11-07: "I don't want a concept of 'read and unread' this is buggy as shit. Messages are delivered to the agents entity and they can remove them". And: "Shouldn't messages be sorted by time not entity id? ... we have to choose attributes that allow us to understand ordreing and anything else the agent might want to do to search, sort, filter etc." Stated in: [users](users.md#messages).

### What's on screen is a reflection of the database

Owner, 2026-11-07: "I want the UI state to be a reflection of the DB so we need to update thte db and that causes the re-render." And: "I really want the render function @ctx to be driving everything ... we stop writing process data and instead write data that reflects the state of the system". Stated in: [users](users.md#whats-on-screen).

### Only Clojure's, our libraries' and agreed words

Owner, 2026-11-07: "We don't have cursors. Please only use clojure terms or the libraries we are integrating with or the concepts that we agree on like using git terms for datahike branching". Stated in: [vocabulary](vocabulary.md).

### The system is a function of state in the database

Owner, 2026-11-07: "there is no 'run' there is an agent's valid schema which has evals and other data attached to it and we can change it and the context regenerates from it. ... This is not OO programming. State is always in the database and the system is a function of that state." Stated in: [users](users.md).

### Think in data structures, and never pass a database

Owner, 2026-11-07: "We need to think of things in terms of data structures. If we have this set of data with refs it's a valid eval and this allows us to control how we store and link data." "don't pass around databases ever. We have a data format and schema for it." Also: "There are no tables in the database please don't use any table or row references when doing these designs." Stated in: [users](users.md#what-each-thing-is).

### Immutable first: same value, same id

Owner, 2026-11-07: "We can reuse data structures. Think immutable data whenever possible ... Everything is a fork of a shared base that we can point to." Stated in: [users](users.md#where-each-fact-lives).

### Fences mean the design is off

Owner, 2026-11-07: "Fences are always signs a design is off. What do we need to simplify to remove the need for guards and fences." Stated in: [users](users.md#four-interfaces).

### Lazy, and dead branches cost nothing

Owner, 2026-11-07: "In general I want hte system to run lazily and only realize and do work that needs to be done at the time it is needed." "The next time an agent is running on their branch the first thing they do is merge. So we only deal iwth it if theres an active agent running and dead branches don't require any merging." Stated in: [users](users.md#lazy).

### Git's words

Owner, 2026-11-07: "I think we should stick to git terms whenever possible so agents have an intuitive understanding." Stated in: [vocabulary](vocabulary.md).

### A user is an agent, in worlds within worlds

Owner, 2026-11-07: "It's just a unique user id and users can reserve a user id lookup ref like 'sean'." "a user has refs to all the agents that they have started by default". Sessions merge everything back to the user's branch by a person's decision, and main pushes "schemas, functions, tests docs, all essentials of the sytem to the users branch so they stay the same from a 'program binary' point of view and their databases can hold different data". Merges "can be custom and curated it can be hard fisted". "merges can fail if main moves under them and that's fine, the error allows the agent to fix it". Stated in: [users](users.md).

### A contract checks a collection whole

Owner, 2026-11-07: "Keep full checks". Asked whether a contract should check at most a bound of a collection's members, with the cost measured: `seon.doc/citation-target`'s body is 82 ticks, and with its `:map-of` arguments checked 9,107 at maps of 1,000 and 43,273 at 5,000, 4.1 percent of a day's self ticks. Contracts stay whole. What a contract costs per call is stated in [cost](cost.md#what-a-contract-costs).

### The default agent chats, and more comes by threads it pulls

Owner, 2026-11-07: "The agent can have a directive to chat with the user and the only tests are to keep responding to their latest message"; "Not giant data dumps, but progressively over time they get the data when they need it". Stated in: [working for a person](agent.md#working-for-a-person).

### No kinds; a keyword may name a template

Owner, 2026-11-07: "in general there are no \"kinds\" and those are generally code smells"; of keywords that map to templates, "that's fine". A template is a map, and choosing one is a merge. Stated in: [a request is one transaction](tasks.md#a-request-is-one-transaction).

### A merge writes itself out while its tests pass, and approval comes later

Owner, 2026-11-07: "If we are essentially creating a merged with main version of the codebase and all tests pass then I'm okay with auto merged, but yes this behavior should be configurable and the user or root should be approving it. What helps us speed up our development right now and what can we later add to handle future ideas?" It stays on (`:seon.config.source/write-back-on-merge`, `my.root/write-back!` by hand when off); an approval by the user or root is later work. Stated in [source](source.md#commit-the-merges-branch).

### Seon's key files are facts, on one mechanism

Owner, 2026-11-07, through the question tool: index `default.edn` and `bin/*` now, and other files stay ordinary until something needs them; a running process reads config from the db, while agents keep the whole disk; write back after each merge that passed its gate, always from main; a file is checked as its reader reads it before it's written, and a boot that can't load a file says which and which commit last worked. "is the indexing a shared system or are we building another parallel system": schema resources' file entity and merge are generalised, not copied, and `:schema.file/*` becomes `:file/*`, chosen by the owner with the store erase that takes. Plan and seams: task `seon.source/seon-files-move-by-branch`.

### A user's branch tracks main like an agent's

Owner, 2026-11-07: "I imagined a users branch tracking main just like the agent branches we are launching. It's just a fact that different users will want to keep differetn data in the system, but we could always be merging the functions, schemas, tests, etc to keep everyone at least speaking the same language." Agents' branches merge to the user's branch and then to main, every user branch merges main by itself, and a conflict launches an agent to resolve it. Stated with the users design in [the plan](plan.md).

### A proof is focused

Owner, 2026-11-07: "make sure that all proofs are well written and appropriate for what they are doing. More is not always better. Focused and good at heping determine correctness and the state being done are the focus". Stated in: [testing](testing.md#a-proof-is-focused).

### A proof starts simple and evolves

Owner, 2026-11-07: "I generally want to start with a simple proof like respond to the user and build up or merge with templates to get more correct testing conditions for what success looks like. It should always be evolving." So a proof that passes without the task's change does not hold a merge; proofs get better as the work teaches what success looks like.

## 2026-11-06 rulings

The owner's rulings of 2026-11-06, on one tree for the model and the page, on data the model can read precisely, and on sessions working with no fixed ceremony; they stand over earlier entries where they differ.

### A well-scoped task is done without asking (owner, 2026-11-06)

"If you have a well scoped task please do it without asking". A clear defect is fixed the same way. Stated in: AGENTS.md section 2, docs/sessions.md#asking-the-owner.

### Old work retired, not left halfway

Owner, 2026-11-06, on branches stranded since October ("we are merging things and not just abandoning them halfway"): retired, each with its commit: judge second-move tooling and generated `:repl-calls` (`01ea574335`, the live-agent lab does this job); a content-keyed derivation (`a08ef67bf3`, cold first and the test reuse by digest that merged instead); a test digesting the files it read (`9fccfab234`, with Jolt's `jolt/file-reads-are-recorded`); a staged kinds deletion (`252a7fac9c`, written when runs existed; the kinds are still on main). The daily full run stays (`e5b45e24fb`, its deletion, is dropped). Loading init from units outside the boot is dropped on its new numbers (docs/machine.md). The write-time schema check lands with its bound moved from 83 to 101 calls (docs/storage.md#the-write-checks-every-datom). About 80 experiment and superseded branches are deleted; `curated` stays, the only git copy of two example sessions.

### A person's view of an eval's result, and what a repeat is

The person keeps an html view of an eval's result, from the one value printer, as a component: "The user should have an html view of an eval result so they can expand it and dig into it if it's data that we print from the one data value printer." The model's text for it is printed when the model reads it, kept by the walk with the component: "isn't the ratom rendering supposed to remember the evals. Can't we have the render function be part of the same system?" "An identical result should have the same result id and lets focus on not causing whatever was looping the agent before": a handle is content-addressed, and the result is still sent. Stated in [render](render.md) and [turn](turn.md).

### The model's context is a render of the same tree as the page

"The context the model is getting should be a part of this render system. It should be a computational graph with react style rendering of components using our understanding of immutable data structures (ctx atom) and the db (internal read tracking) do not reinvent anything." "When we were doing separate model and user html renders they always drifted." "hiccup is also data... Data itself can self render based on it's render function." "the model context should be efficiently re-renderable just like the hiccup is. this means yes we can re-eval something and update it. Yes this will cache bust and yes that's okay if explicitly done." "I am okay with busting the cache and re-rendering from that point on so redoing the diffs." The layer between the db and what's shown: "It's a transformation layer transforming raw data to meaningful processed data. Sometimes that's passthrough sometimes it's a summary but it's always dynamic and thinking ahead for how the diffs will present updates." One render, not two: "We were intentionally basing the model context off the rendered data. So it was a dependency tree and the hiccup / intermediary state was directly renderable to html. I don't want to go back to dual renders for everything. We end up with data that the agent may not care about and I want intelligent transformation, filtering, adding in other data if enecessary from the db." The word: "I really don't like the part name"; it's a component. Real-task runs come after diffs, before the conversation moves. Stated in: [the model's context](render.md#the-models-context-a-cached-prefix-then-the-turn).

### What the model reads is precise data, teaching included

Freeform Markdown on the wire is "a loss of precision"; "We saw a boost in internal model performance when we focused more on returning data." "I disagree that we can't have teaching and direct word statements. I just want them presented as data and strings." "I want evals to be data and not some magical structure so we can remove them re-eval them etc." Diffs: "There must be a clear way for the model to understand where the change is happening and then we only show the changed parts so we never reprint the full data structure"; editscript, "I want the gains". Keyframes: "Buiild the system so this is easy to do", tested after the refactoring.

### Seon's own files move by branch, and an agent has the whole disk

No isolated trees or refused writes: "Seon agents have their own databases which is their worktree"; "File ops will be required for other work like editing non seon code or just doing computer maintainance"; agents read "anything on the disk the same way you can". Work is proved by tests, including tests that inspect files. Sync is by hand for now: "The DB is the source of truth we need to merge to main cleanly and then we can efficienltly sync to disk if we want. That shouldn't be automated yet." A red on a merged tree is the agent's to clear, whoever caused it: "I don't care who fixes the problems on main". What comes into the db is named for what it's for, from an allow-list at import, with no listing: "I don't want to replicate a full filesystem in the db." Being built: task `seon.source/seon-files-move-by-branch`.

### An eval has an intent, and an agent talks to whoever started it through `message-md`

Superseded on the message by the 2026-11-08 ruling below, "A reply is its text and its calls": the intent per eval stands; the one required message per reply does not.

Two ideas, two fields: "theres' sa field for natural language responses to anything going to the user/agent and that's in markdown and then there are intents for each eval that forces the agent to think about what it's doing before it does it and it's just describing the eval." Who it talks to: "some agents will be sub agents so they will be talking to their parent agent and not the user, but the idea is the same. Natural language markdown describing what to the agent/user what the next evals will be doing and answering questions or otherwise commnuicating." "It's one message-md for ALL the tool calls. each tool call has an intent." The message is stored as the turn's text, `:seon.model-call/reply`, and goes back to the model on the reply's first call as it sent it: required with history kept that way, 85 of 96 replies carried one and nothing was refused, against 8% with the message moved to text, and 47% with 53% of turns lost when refusing. Stated in [prompt](prompt.md).

### Design for classes of problems, and look for root causes

"I want an elegant design that prevents entire classes of problems so if we find ourselves hacking things we have the wrong design." "Always look for root causes rather than solving symptoms." "Agents repeating themselves is not waste it's a bug." Data: "Data can only come from the pull which becomes ctx or the current db"; "pull friendly is king"; "There are no tables and no rows"; "Don't record shit no one cares about or heavily embed process (how something is done) with what needs to be stored." Stated in AGENTS.md section 3.

### Sessions work together with no fixed ceremony

"I don't want the work done twice. If you do it well merge it. If optimization does it well merge it. If you hand something off to optimization just do it. No more ceremony and mandated fixed policies." "Only give optimization work that it can work on." "Aim to get 90% of the benefit for 10% of the effort." Machine load is balanced by judgment. Stated in AGENTS.md sections 2, 3 and 8.

### A slow test runs last in a merge, and is left out of a plain run until it is fast

Owner, 2026-11-07: "The slow ones are just the last ones to run and again we focus on short circuit not writing out full reports." A slow red had stayed on main through every merge.

"exclude them by default until they can run fast"; "We need our own perf data... to disable the tests and mark them as broken and ideally auto create tasks to fix them". Stated in [merging](merging.md#a-slow-test-runs-last).

### A reply with no calls pauses the agent, whatever its proofs say

Owner, 2026-11-06: "I think that's fine to pause the agent if they don't do any tool calls"; "No more turns even if it's red. If we tell the agent that a reply with no tool calls will pause their session that's then we pause their session." Stated: docs/prompt.md, docs/turn.md#parked, seon.turn/act!.

### A parked agent stays in memory, and replay is deliberate

Owner, 2026-11-06: "I think we decided not to focus on making agents cheap (highly efficient) and not taking them out of memory. If by park you mean in the flow sense where it's a blocking wait and no cpu is used that's great. If it's replaying sessions -- no we know this is deeply problematic (fire-the-missles! kind of problematic) and we want to be intentional about replay and maybe even do selective replay". This tempers the automatic replay in the 2026-10-25 entry below, which was never built. Stated: docs/turn.md#parked.

### Edits go to the database, then to disk when asked

Owner, 2026-11-06: "I want edits to go into the database (which is the source of truth) and then to be explicitly synced to disk"; "don't reinvent the wheel. improve the existing systems our agents are using not creating new systems". An edit saves to a branch, merges to main with its proofs, then an explicit sync commits to the merges branch (the owner's choice in a round of questions). Task `seon.render/an-edit-reaches-disk-when-asked`.

### No regex or text parsing; function composition

Owner, 2026-11-06: "I don't want anything hacky here. No regex or parsing text. I want function composition." Stated in docs/source.md#a-forms-account-is-the-programs-facts, AGENTS.md §9 (regex over code) and docs/turn.md#an-eval-can-be-run-again.

### An eval run again replaces itself

Owner, 2026-11-06: "Evals I run again should replace (upsert) the existing eval and yes that's the point is for the model to see the updated version if I'm intentionally re-running it." Stated: docs/turn.md#an-eval-can-be-run-again.

### The debug page is the wire; the agent's page is where it is friendly

Owner, 2026-11-06: "I want the wire format types as they are. This is a debug page and there's too much magic going on. The agents rendered html page is where we make it user friendly."; "expand should expand not repeat". Stated in docs/render.md#the-debug-page-is-the-wire-request and seon.render.ui/debug-turn.

### Styling is a render the agent can read and change

Owner, 2026-11-06: "I want to keep the parts that allow the model to fix problems without guessing what the ids are ... pull in the relevant CSS as one of the render functions so the agent can separate styling with context and they can update it too". Stated in docs/render.md#style (`:seon.render/style` as plain CSS rules, seon.render.ui/page-rules).

## 2026-11-05 rulings

The owner's rulings of 2026-11-05, on looking before building and on what makes an agent's context; they stand over earlier entries where they differ.

### Look around before building; no page of doctrine (owner, 2026-11-05)

"I don't know wtf \"seon-way\" is so audit it for it's mertis and integrate them into the rest of th edocs and delete it. There is no ONE WAY, just fucking look around and don't rebuild shit that we've already built." And: "I want you to bring more engineering rigor and sanity to the work. Don't just act think and plan". Stated in AGENTS.md §1. A mechanism is judged by what the code and tests show, not by a page's say-so.

### An agent experiments with a render of its own (owner, 2026-11-05)

"Agents need to be able to declare their own render functions and they can either override the existing one, but that changes the entire system behavior if it's the default so it's better if they are experimenting to just declare their own. All functions are a trivial query away since all functions record their inputs and outputs and that's all a render function is." Stated in [ctx](ctx.md#adding-a-datum-and-its-render).

### An agent's entity, pull and renders make most of its context (owner, 2026-11-05)

"Design the agents entity with useful information and make it so the pull pattern and the render functions generate the majority of the agents context. It teaches them about the system, it explain what task they are working on, how messaging works when they get a message, evertyhing. It's all data driven." And: "NO SPECIAL CASE FOR CITING. That's just data and a render function". And: "Definitely got rid of \"runs\" (that concept doesn't exist) agents have turns, evals, max turns, etc." Stated in: [ctx](ctx.md#what-the-model-reads-and-what-the-page-renders).

### The debug page draws history with today's renders, and says when that differs (owner, 2026-11-05)

Asked whether the debug page should draw history with today's renders plus a hash that flags any difference: "Yes, and try it". It stands over "as recorded" for a request ([render](render.md#a-render-does-no-work-of-its-own)). Stated in: [the debug page](render.md#the-debug-page-is-the-wire-request).

### The render is an eval, and what changed is another (owner, 2026-11-05)

"We can just make the rendering of the hiccup a normal eval that we inject into turn 0 and then we can just do diffs when it changes. That way the context is accumulating and each step you can see the updates". And: "If we detect a change in the @ctx and/or the hiccup we add another eval that gets the diffs and the agent on their next turn sees what's changed". It stands over the page being latest only. Stated in: [the render is an eval](render.md#the-render-is-an-eval).

### Large or changing output is the render's bug (owner, 2026-11-05)

"Nothing we should be rendering should be huge or should be repeatedly changing dramatically. That's a failure of the render functions and the system for generating noise. Find and fix those don't blame the system."

### Data is entities with attributes, and nothing is quadratic (owner, 2026-11-05)

"There are no rows. ... Datomic like systems have entities and they have any number of attributes and values. That's what makes something a thing. An agent has turns, it has evals, it's just data." And: "Nothing should require N^2 because we are progressively building things if we aren't then again it's the wrong design." And: "find and remove all \"row\" references and instill proper data modeling understanding for datahike entities and the relationships and how to properly query and store data efficiently". Stated in [data modeling](data-modeling.md).

## 2026-11-04 rulings

The owner's rulings of 2026-11-04, on what an agent is and how everything it reads is made. They stand over earlier entries where they differ.

### No parallel systems (owner, 2026-11-04)

"I don't want agents to keep building parallel systems". A second layout, a second cache, a second path to the model, an entity to hold what an attribute or the db's history already holds, a store of printed values: before adding one, look at what already does it, and ask whether the problem is a cause or a consequence of something else being wrong ([a bug is a class](#a-bug-is-a-class-remove-what-allowed-it-owner-2026-11-03)). Stated in: AGENTS.md section 9.

### Data is stored as data; the one printer is for context (owner, 2026-11-04)

"All evals that we can store as data natively inside datahike or via the object storage we should do up to a reasonable point (we should have reasonable maxdese) and if that happens we can't resume that data and that's fine. Printed data is rarely the right choice and I want us to be very suspricious of any code that is printing values. The only value printer should be the one value printer and that's for assembling context for agents". Stated in [plain data](storage.md#plain-data); the sweep is task seon.print/the-one-printer-is-for-context.

### The standing store is erased when the new schemas land (owner, 2026-11-04)

"Yes you can erase the runs/store when we have the new schemas in place." Erased when the simpler schema merged (2026-11-05), with what could not be recreated at boot saved and read first; runs/curated and runs/kept-fresh-87 stay.

### Agent branches are kept and queried (owner, 2026-11-04)

"In general I don't want to delete the agent branches and I want to query them when we need information on what happened for an agent. Mostly to avoid complicated merging but I can see the value of merging the agents entity, but then what about any refs too? How far do we merge back?"; "I think in production we'll be auto archiving the branches if they work, but in development I want us to be able to restore sessions and arbitrarily fork and edit them". An agent's records never merge; main holds what root needs, and a merge stays the program and tasks. Archiving is a later production policy, with no code for it now. Stated in [merging](merging.md#seons-merge-is-a-three-way-merge-of-the-program).

### An agent's data makes its page and its context (owner, 2026-11-04)

"The fact that the agent's pull is what makes an agent an agent is what makes it render the UI and which parts go where. A different agent could have different data attached and we want it to render differently to adapt to whatever data we give it. This is a general core function of the seon system. Make all data known, validate it, and we make it easy to process it for whatever needs the user has." And: "No more hand crafting things. Let the system operate and figure out how to have everything declare itself". Stated in: [render](render.md#one-rendering-system-drawn-from-the-data).

### The data's shape is ours, batteries included (owner, 2026-11-04)

"You can change how data is connected to each other and rename it and change directions on what is ref'ing what. We have full control so make a system that will work generally and optimally one way and make it so it's easier to build things the optimal way ... This isn't do anything you want it's a more rails style batteries included but you follow these constraints." Stated in: [data modeling](data-modeling.md).

### Store data for how it is used; a turn is a number (owner, 2026-11-04)

"Whatever data we need you can store it any way you need to to retrieve it efficiently. That's how these databases work best. You plan ahead for how you want to use it and make sure that's easy and cheap". And: "I'm saying \"wtf is a turn\" but a number? An agent has turns and evals why are we organizing them on top of other entities". Stated in: [data modeling](data-modeling.md#things-relate-by-refs), [turn](turn.md#the-turn-is-a-number).

### A render asks for the db and queries what it wants (owner, 2026-11-04)

"We assemble an agent why can't we add some data that triggers a render function to get the data you want and render it? The render functions get the curretn db if they ask for it so you can do any query you want and it doesn't have to be reachable from the agent directly". Stated in: [components](render.md#components).

### Re-render when the data or its function changes (owner, 2026-11-04)

"My point is thing in data and when data changes OR THE FUNCTION THAT RENDERS IT changes then you re-render". Stated in: [reactive renders](render.md#reactive-renders).

### Delete, compact and reset are data (owner, 2026-11-04)

"A \"compaction\" is just removing and maybe modifying some data on the agent's entity and then the next message sent regenerates the context (which intentionally breaks caching) and we have the model keep working." And: "I want the ability to reset to any point AND to delete arbitrary data (the history is there if we ever need to revisit it) and have compaction work." Stated in: [ctx](ctx.md#deleting-compacting-and-resetting-are-changes-of-data).

### The page is latest only (owner, 2026-11-04)

The owner approved the page as only the request's last message, narrowing the 2026-10-29 and 2026-10-30 rulings that a part is sent as what changed: "always include the most recent ... (not accumulating)". The owner replaced this on 2026-11-05: the render is an eval, and what changed is another.

### Every agent improves the renders (owner, 2026-11-04)

"We distrubute the task of writing rendering functions for data across the whole system so every agent helps build a better understanding o fthe data we are storing and processing in the system by default". Stated in: [ctx](ctx.md#adding-a-datum-and-its-render).

## 2026-11-03 rulings

The owner's rulings of 2026-11-03, on bugs as classes and on what contracts cost; they stand over earlier entries where they differ.

### A bug is a class: remove what allowed it (owner, 2026-11-03)

"Always be suspicious about bugs. Usually fixing them directly is the wrong solution. Root cause and see if we should be doing something that eliminates the bug class." Batch 89 guarded an open schema (`[:response :map]` let a value no store keeps through to the write) where declaring the shape removes the class ([plain data](storage.md#plain-data); the owner: "The fix your question points at is to declare the response's real shape: correct"). Stated in AGENTS.md §3, the method's step 4.

### A contract costs nothing on its one interface; the same data is checked once (owner, 2026-11-03)

"Oh yeah contracts don't count against anything as long as you do it through the optimal interface and we aren't checking it multiple times for no reason. So understand what instrumentation does ensure we are instrumenting them and then if that is covering it then we don't need to check it multiple times if it's the same data." Cost proofs stop counting the contract's own calls (its checked wrapper) in `:calls`, once every contract is checked through the one instrumentation and no data is validated twice for no reason; a redundant re-check stays waste. The one-time re-pin of every proof it moves (the 616 and 8 included) is approved under it. Task `seon.contract/a-contract-is-checked-once-and-costs-nothing`; to be stated in [cost](cost.md) and the contract section when it merges.

## 2026-11-01 rulings

The owner's rulings of 2026-11-01, after the audit of what agents read; they stand over earlier entries where they differ.

### Benefits are general; nothing leads an agent to its answer (owner, 2026-11-01)

"We don't mimic repl output with comments it breaks the models. Do not lead the models just to score a win. All benefits should be general." Nothing an agent reads (the help page, its instructions, a cited section, a task's plan) tells a run's story or hints at the change it is asked for. A benchmark is work whose answer is not in the tree: a reverted change whose tests stay behind measures nothing. A red cost proof is never cleared by the changing agent raising its own bound; an overrun is accepted only as [cost](cost.md#an-accepted-overrun-and-its-ratchet) says, filed by a person (docs/merging.md#a-landing-message-carries-its-audit, every change earns its place).

### An agent's evals on a task are a session; past ones are shown as curated sessions (owner, 2026-11-01)

"Yeah sessions is fine"; "In the instructions I don't think we say the dead ends part. Curated sessions." A session is an agent's evals on a task in order (nREPL's word; still stored as `:seon.run/*`, renamed only by a store erase). A past session shown to an agent as a worked example is a curated session: the session as stored, with the evals that led nowhere marked out, reaching the model as the same tool-call turns its own evals do (`seon.ctx.wire/eval-msgs`), never as a text blob and never repeating the system message. No parallel store or renderer; it is evaluated as a measured arm before it is built in (plan.md, where it stands).

### Context is data a render draws from the db (owner, 2026-11-01)

"Do not create parallel systems to the context generation and rendering." What an agent is shown is data in the db (a task's citations, a search as a citation) drawn by the one render (`seon.ctx.wire/render`); an evaluation arm differs from another by its data, never by code that builds a second context.

## 2026-10-31 rulings

The owner's rulings of 2026-10-31; they stand over earlier entries where they differ.

### A test shows its claim, not a smaller copy of its old input (owner, 2026-10-31)

"question what is being tested rather than how fast can we make it. Algoirthmic improvements often can be found by rethinking the problem." Before shrinking a test, state what it claims and find the smallest input that shows exactly that claim (a parameter of the function, such as a shortlist, set small; a constant claim where the cost does not grow; an exact count where the dimension is exact). Stated in docs/plan.md (the shrink item) and the shrink briefs; batches 57 and 59 apply it.

### A flaky check step is merged past and filed, not rerun into a verdict (owner, 2026-10-31)

On batch 58 (swarm red once, green alone at the same tree, the change unable to reach it): "merge and file". The race is task seon.reconcile/a-behind-run-that-parked-green-is-merged-not-rerun-to-its-cap, its evidence kept until it is fixed.

## 2026-10-30 rulings

The owner's rulings of 2026-10-30 morning, after the overnight md-scan runs; they stand over earlier entries where they differ.

### Limits fail loudly (owner, 2026-10-30)

"Please don't put any artificially low limits that will bite us in our asses and especially if they won't loudly crash"; "The limits should always fail loudly btw. That budget is useful to catch problems IF we use it right." A bound is removed or derived from measurements, and reaching it is a typed error that names it. Stated in: AGENTS.md section 5, docs/config.md#a-bound-fails-loudly.

### A timed wait means the wrong design (owner, 2026-10-30)

"timeouts just mean we have the wrong design (most of the time)" (from session memory, 2026-11-10; unverified wording). A wait ends on its value or on a typed error from the producer that owed it, never on a clock. Stated in: AGENTS.md section 4, docs/clocks.md.

### There are no runs: an agent has an env, a branch and its evals (owner, 2026-10-30)

"there is no concept of a \"run\" agents have an env a branch and are doing evals. Attach data only to real things"; "please correct whatever is saying that runs exist it's a bad design"; "Agents have envs and run evals and take steps and we have max steps that we can increase if they hit their limit." At :seon.agent/max-turns an agent parks with its process and context; raising it, or a message, wakes the same agent, its turns counting on. A message wake raises the limit by a config amount ("nothing hard coded and we can override it on the agent's record ... defaults are properly inherited automatically"). Every message, the owner's, root's or another agent's, is a fact on main addressed to the agent, which root delivers and which wakes a parked agent. The standing store is erased when the run records are deleted. Stated in: task seon.agent/the-agent-is-the-unit-not-the-run.

### Errors reach the agent grouped, once (owner, 2026-10-30)

"errors render grouped by signature with counts, newest first line and a handle; root records all; the context sends what changed." Stated in: seon.render.ui/problems-model.

### The context sends changes, cites code at preview, and counts turns on the eval line (owner, 2026-10-30)

Approved after the run removal: a changed part that answers data sends clojure.data/diff of its value; a cited var at preview depth, its source on request; the turn counter on each eval's one-line outcome, not a part. Stated in: task seon.ctx/the-context-is-the-agents-evals-and-seons (when built).

### Light tests while an agent develops, the full suite near merge (owner, 2026-10-30)

"let's keep the test suite minimal and most likely impact (we can see there's a change in a function so we need to re-run all tests that consume that function and we'll do a more thorough check when all the proofs are green and we are building towards a full merge". A turn runs the proofs and the tests its change reaches, in-process and fast; the process tests and the full suite run once the proofs are green, before a merge. Stated in docs/merging.md#a-merge-runs-what-the-change-reaches.

### A process test's cache gap stays; the full run catches it (owner, 2026-10-30)

An in-agent test's code strings are not in its reach, so its digest may reuse a green across a change it reaches; "leave it", the heavy pre-merge run catches it. Asked next: mark such tests uncacheable, so the heavy stage always reruns them ("Can we just mark tests that we know can't be cached so if they are touched they always rerun?"). Stated in docs/plan.md#now.

### Main keeps recording live data; agents react only when main's program moves (owner, 2026-10-30)

"I do want a concept of a shared main moving forward with data so that has to remain the same"; "Should main's agents react only when main's program moves? I think so." An agent hears main moved only when main's program (forms, schemas, tests, by content key, never by entity id) moved; main's other facts (agents' live state) are recorded as before. Task seon.root/an-eval-never-moves-main; stated in docs/plan.md.

### Eval budgets come from measured costs (owner, 2026-10-30)

Budgets should be under 90%+ of the time and an overrun mostly a case worth intervening, as each test's tick budget comes from its own green history; :seon.config.agent/form-ticks and form-bytes are to be derived from measured eval costs, not fixed. The optimization session's; a test run an eval takes in place is not the form's work.

### Simplify by constraint; the test suite must be trusted (owner, 2026-10-30)

"I'm okay with adding constraints to the system to simplify the solutions"; "I want all correctness issues solved with our test suite. We need to trust it as much as possible." Hangs are removed as a class (one reader of an agent's socket, a mailbox per request id, every taken request answered in a finally, the cap checked once at the top of each turn); tests run as agents run them. Stated in docs/plan.md#now.

## 2026-10-29 rulings

The owner's rulings of 2026-10-29, each with where it is now stated; they stand over earlier entries where they differ.

### A link the owner is given survives a restart (owner, 2026-10-29)

"I can't see the changes. Always give me links or give me a stable way to find links on a stable server". An agent is linked by its branch, and the link names the commit the root was built from. Stated in: docs/sessions.md#stable-links.

### The page is a friendly chat; what the model was sent is shown whole (owner, 2026-10-29)

"I want the chat to look like a nice chat screen with just prose unless I dig into it. The subtle ticks, ms metadata is nice"; "Does that look like a friendly chat window that my mom could use?"; "have the step expansions open up the details directly so it's not two clicks"; "I want to understand what the agent was sent cleanly so you can format it nicely but I don't want anything hidden"; "Also the cache points". A turn is one message with each eval a step under it, opened in one click to its code and value; the chat's start and end are cards; what the model was sent is the debug tab, the raw wire with the provider's cache point. Stated in: docs/render.md#the-page, docs/render.md#the-debug-page-is-the-wire-request, seon.render.ui/conversation-turn, seon.render.ui/debug-turn.

### Counts are tokens; what an agent writes is Markdown (owner, 2026-10-29)

"only report tokens (estimates are fine) not characters and I want some sense of the total number of tokens being used for each eval and cumulatively"; "build this into the value printer so we don't have to duplicate effort"; "all descriptions the agent writes are assumed to be markdown and I want markdown rendering in the html side (it can remain markdown for the model context)"; "change the instructions to be clear the descriptions and any other text is markdown and it will be shown to the user so be expressive". Stated in: seon.print/tokens-text, seon.print.emit/cut-words, docs/prompt.md.

### References at their depth, previews first (owner, 2026-10-29)

"The old reference system needs to be adapted for the context rendering system. The backticks were meant to have an easy way to indicate more info should be shown here ... different levels of detail for different references (functions have args right?)"; "think critically about the references and make sure it's helpful and not noise. We control what we are showing and we can still just do previews and the agent can dig in for full details". One part per citation at its delimiter's depth, by the reference system's own views, a preview the agent digs into. Stated in: seon.ctx/citations, seon.doc/cited (task seon.ctx/the-context-is-the-agents-evals-and-seons).

### Parts answer data: an html render and a model render, the value named status (owner, 2026-10-29)

"I want the hiccup render to make the data we need to render the web ui and I similarly want the ai render to make the data we send to the model ... different models could have different renders ... make sure we are not doing shit multiple times"; "keep everything as data until it's at a print boundary ... only use the one value printer"; "Store information we need and it's presence means something" (on the model call basis's :observed: "wtf does observed mean?"). A page part may answer a value of a schema; the page draws its :seon.render/html, the model is sent its :seon.render/model, each a function of the one value the walk computed once. How an agent's work stands is that value, named status ("status": git's word, what my.task/status answers; over "standing"), computed from facts of the branch, never held. Stated in: docs/render.md#a-component-answers-hiccup, docs/render.md#how-the-work-stands, seon.ctx.status/status, seon.render/part.

### The standing store is test data: wipe it when a change needs it (owner, 2026-10-29)

"nothing in the Store is needed. wipe it whenever you need too. Keep an eye out for crap building up and we need to clean out test files and other tmp stuff". A stored attribute renamed or retyped erases the store, never migrates it (:seon.test/failure became :seon.test/failures). Stated in: docs/machine.md#disk-stays-bounded.

### The verdict cache holds 512 MB, least recently used out first (owner, 2026-10-29)

"least recently used automatic eviction if max capacity is reached"; the capacity: 512 MB (chosen over 256 MB; the main tree's cache held 121 MB in 27,034 files that day). One capacity for the whole cache, greens and reds alike; a verdict for a test no longer in the program goes by the same rule; nothing goes below the bound ("don't drop cache work that other agents may need"). Stated in: :seon.config.proofs/results-max-bytes, seon.proofs/the-verdict-cache-empties-itself.

### An eval call holds one or more forms (owner, 2026-10-29)

"remove that from the docs that only one form runs". A call is intent and one or more forms, each run top level in order, the first throw stopping the rest; the prompt asks for one form per call. Several forms answer as a REPL prints them (verified on Clojure 1.12.5: `(+ 1 1) (+ 2 2)` prints 2 then 4): `:forms` (how many ran) and `:value` the vector of their values ("okay that works"); one form, or one `do`, answers its value. The code is never rewritten (a vector of the forms compiles as one: an alias a `require` in it makes is unknown, a `def` after an `ns` lands in the old namespace). A call with no prose or no form answers `:seon.turn/invalid-call`. Supersedes "one form per call" in the two 2026-10-28 entries below. Stated in: docs/prompt.md, docs/agent.md, `seon.model/eval-tool`, `seon.repl/call-problem`, `seon.repl/response`.

### The model's context is the render of the agent's record, by the one walk (owner, 2026-10-29)

"I'm down with the whole db -> pull into ctx representation and then rendering that"; "This really feels like we are conflating the data with the render"; "build with both the context render and the web ui render in mind so we don't let one get out of sync with the other". A request is the agent's records in order (its evals, its replies, its messages) and the parts of its render of @ctx: whole at the first request, then only the parts whose drawing changed, each sent once a change. The model and the person read one layout (render-ctx), so they cannot drift. Removing evals from @ctx with swap! draws the request whole: the one compaction. Stated in: docs/render.md#the-models-context-a-cached-prefix-then-the-turn, docs/render.md#the-render-is-an-eval, docs/agent.md, task seon.ctx/the-context-is-the-agents-evals-and-seons.

### Nothing derived is held; everything is derived from the db at t (owner, 2026-10-29)

"Don't fear deletion make the system work with the data that's there. Period. Don't try and hold random derived state in the system. Derive it from the current state." What a request sent is a fact on its model call; a cache is keyed by its inputs and may be dropped. Removed under it: the fold and :same-as (a record's printed text depends only on that record), seon.ctx/world and its page caches. Stated in: AGENTS section 4 (a kept db value is a reference), docs/ctx.md, docs/render.md.

### The agent's record is facts about its work (owner, 2026-10-29)

"The agent's record is facts about its work. Telemetry and derived data live on their own entities, pointing at what they describe: a vector at its eval, a model call at its turn, a process sample at its agent. The agent's pull never reaches them, and the debug view and the ui render them." The agent's pull is `*` plus hand-declared reverse joins (its turns, evals, newest messages) and its task; anything an agent writes on its entity is pulled and rendered by its schema. Stated in: docs/ctx.md#the-agents-entity-holds-its-work, docs/ctx.md#the-pattern.

### The agent is the unit, not the run (owner, 2026-10-29)

"get rid of runs, the concept doesn't really exist and is just X amount of forms being evaluated ... An agent is just writing evals". An agent is one per branch, keyed by its branch, the same across boots and wakes; a turn limit on the agent (raised to extend it); a message is addressed to the agent; :lost and :killed are agent statuses; one agent works a task at a time; the check before a merge takes git's words ("more git merging terms ... less made up things"): a merge check of a commit, its result on the task, keyed by the commit and main's commit; done is every proof green, the merge check's included, and behind main is no red (main is merged in every turn it moved). Stated in: task seon.agent/the-agent-is-the-unit-not-the-run (cut 1 merged: docs/ctx.md#the-pattern; cuts 2 and 3 open).

### An agent's test results are facts on its branch; the shared cache stays for reuse (owner, 2026-10-29)

Chose "Agent runs only" for now: an agent's runs write :seon.test/* facts on its branch, which @ctx holds and the proofs part renders; bin/test and bin/prove keep the shared file cache, so agents still share greens; the cache gets one capacity with least-recently-used eviction past it ("least recently used automatic eviction if max capacity is reached"). Stated in: tasks seon.proofs/test-results-are-a-few-facts, seon.proofs/the-verdict-cache-empties-itself.

### A value is rendered by the newest render of its schema (owner, 2026-10-29)

"automatic per eval result auto rendering and to override by just writing a function that renders that ... just a query which gets the default and any other render functions and sorts them by newest first"; "if we don't know what something is we return a warning/error so the agents are encouraged to fix it and we only auto render things we know what they are". Stated in: task seon.render/a-value-is-rendered-by-the-newest-render-of-its-schema (not built).

### Seon runs on an ordinary MacBook (owner, 2026-10-29)

"I want this to run on an average users macbook for reference. Not just servers or people like me with 128GB of ram." Ten working agents fit an 8 GB-class machine: root idle at or under 650 MB, the fork server under 300 MB, ten agents under 3 GB. Stated in: docs/plan.md (beside step 1), docs/machine.md#ten-agents-at-once.

### The image keeps each frame's file:line and drops the expanded source (owner, 2026-10-29)

Chose V2b of three measured builds over `--opt` (-419 MB, but library stack lines lose their line numbers) and dropping the bodies alone (-116 MB): about -210 MB per root, every eval error unchanged. Stated in: task seon.main/the-image-keeps-no-inspector-information.

### The embedding model is a Flow proc that wakes on its work and spins down when idle (owner, 2026-10-29)

"It should be converted into a flow process that has the channel for waking it up and working while the work is there and idle or spun down when it's not if it's cheap. Look into this don't guess." Measured: a forked process cannot load the model (Apple's runtime aborts it), so the proc runs an exec'd helper, about 0.45 s to load, ended when its queue empties. Stated in: task seon.vector/the-model-is-loaded-outside-root, docs/failure-points.md#physics-who-may-run-threads, docs/vector.md#the-model.

### Config at every level, no env var switches (owner, 2026-10-29)

"formalize config options so there is no env var magic happening. I want the config process to be documented and a standard system for configuring at any level -- init, added to an agent's entity to override on a per agent level, task wherever it needs to go"; "Everything forks from root right? So maybe it is helpful to add to the root entity?". Order: defaults < profile < --config < --set < main's root entity < model < provider < task < agent; each key's schema names the levels that may set it; an agent level beside the task's; bin/ passes --set, not env vars. Stated in: docs/plan.md (beside step 1); docs/config.md when built.

### Env vars are fine when aero captures them into config entries (owner, 2026-10-29)

"env vars aren't bad, but the need to be captured by aero and translated into config entries." An environment variable reaches the program only as an `#env` value of a config key in `default.edn` (or the loader's documented inputs: the checkout, the secret); nothing switches behaviour on an env var read elsewhere. The profile is `--profile NAME` (4a34eb9227). Stated in: docs/config.md.

### Stop the garbage at its source (owner, 2026-10-29)

"Yes stop the garabage for sure": an allocation that is thrown away is cut where it is made (a spin, a recomputation, a decode done twice), not collected faster after. Stated in: docs/machine.md#roots-heap, docs/plan.md (beside step 1).

### Use the machine while nothing is starved (owner, 2026-10-29)

"Load isn't too bad, you can run as long as we aren't starved for anything which just slows down everything." Heavy runs may run in parallel past load 8 while no run is starved (a timing-sensitive step that goes red under load is a defect of that step, not a reason to stop). Stated in: docs/machine.md#builds-and-checks; the "load under ~8" default is gone ([load is judged by cores and free memory](#load-is-judged-by-cores-and-free-memory-owner-2026-11-10)).

### No artificial limits or cuts on an agent's context or evals (owner, 2026-10-29)

"wait do not put any artificial limits or cuts on agents context or evals." An agent keeps every eval result (result/eN), and its own errors, test failures and replies carry their full data; memory work bounds only what root keeps of other processes for its life (:root-errors, its view of agents). The 16-result window and the error bounding on an agent's own errors (merged earlier that evening) are taken back (branch jolt/agent-no-cuts). Stated in: docs/errors.md, docs/agent.md.

### Agents at once: ten to twenty on a small machine, no ceiling on big iron (owner, 2026-10-29)

"Yeah I think 10-20 should be fine for users with small machines and the sky is the limit for servers and big iron." `:seon.config.agent/max-running` defaults to 10 (was 4, v1's), and is sized from the machine's RAM and an agent's measured real cost (20.6 MB an agent by `bin/check scale`, 2026-10-29), never a fixed ceiling on a large machine. Stated in: resources/seon/config/default.edn (:seon.config.agent/max-running).

### Fix every major optimization issue; memory is the thread to pull (owner, 2026-10-29)

"Your job is to priortize fixing ALL major optimization issues that you see. Memory problems seem like a good way to finding real system / platform / core issues so it's not a bad thread to pull on. Blockers of course." and "I don't see why hundreds of mb are being used for anything right now so I think there's a lot of headroom". The machine session works beside step 1: blockers first, then every large cost found, each measured before and after. Stated in: docs/plan.md (beside step 1).

### Config levels are built as keys need them (owner, 2026-10-29)

Chose "only levels in use": one resolver with the levels something sets today (defaults < profile < --config < --set); main's root entity, model, provider, task and agent levels join that resolver the day a key needs them, in the order of the entry above. Stated in: docs/plan.md (beside step 1), resources/seon/tasks/seon.config.edn.

### The memory budget: Seon fits 8 GB and runs beside other apps on 16 GB (owner, 2026-10-29)

Chose "8 GB, fits in 16": root and ten working agents under ~4 GB in all, so Seon runs beside a browser and an editor on a 16 GB MacBook and still works on an 8 GB one; `bin/check scale` charges against that. Stated in: docs/plan.md, docs/machine.md#ten-agents-at-once.

### The standing root follows main's ref, never the checkout's HEAD (owner, 2026-10-29)

Chose "root follows main ref": a topic branch checked out in the main tree never reaches root's source or main through `bin/update`. Stated in: task seon.fx/the-standing-root-follows-main-not-the-checkouts-head.

### Old worktrees' run data is not kept (owner, 2026-10-29)

"No run data is important right now." Untracked runs/ directories and stores in old worktrees went with their worktrees; branch refs are kept. Stated in: this entry (the rule that a store is kept, docs/machine.md#disk-stays-bounded, is unchanged for the standing store).

## 2026-10-28 rulings

The owner's rulings of 2026-10-28, each with where it is now stated; they stand over earlier entries where they differ.

### An eval call is intent and one form; its result is data with Clojure's value (owner, 2026-10-28; one form superseded 2026-10-29)

"we can also just require both fields and be explicit"; "Okay match clojure for the eval output (4)"; "I mean the full data output with timing and result too. Not just the value". A call with no intent, or not exactly one form, is not run and answers `:seon.turn/invalid-call`; a `;` comment is code, never the description. The response is the full data map, its `:value` the one Clojure answers ((do (+ 1 1) (+ 2 2)) is 4): no `:values`. Supersedes the 2026-10-25 "each form's value" ruling. Stated in: docs/prompt.md, docs/agent.md, `seon.repl/response`, `seon.repl/call-problem`.

### The model is sent the whole conversation; no history budget (owner, 2026-10-28)

"who made a 32k history budget? Remove it I never approved that". A session chose 32,000 tokens on 2026-10-21; it dropped the agent's own evals while page re-renders filled the request. Every request now sends every record whole. Stated in: `seon.ctx.wire/request`, docs/render.md.

### The eval protocol: tool calls by id; one form per call; a top-level do is its forms, as in Clojure (owner, 2026-10-28; one form superseded 2026-10-29)

A reply's text heads its block of evals; each eval is one tool call with `intent` and one form, its result a tool message matched by the provider's call id; calls run in order and stop at the first throw, each later call answered "not run" (superseded 2026-11-08: [every eval of a reply runs](#every-eval-of-a-reply-runs-owner-2026-11-08)); a reply with no call is the message to the user. "Match Clojure: unroll a top-level do so its definitions are program, and tighten the system text to one definition per call." Stated in: docs/prompt.md, `seon.turn` (the calls), `seon.repl/eval-code`.

### Nothing crashes and nothing hangs; failures are data to the agent (owner, 2026-10-28)

"I want our system to be robust and to have it not crash or hang ever. We can generate errors as data and return them to the agent and they should be able to fix most things or send to root if they need another process to fix it." Every wait ends with its value or a typed error; a failure returns to the requester as an error value; the agent fixes it or messages root. Stated: docs/errors.md#nothing-crashes-and-nothing-hangs, AGENTS §4.

### Every eval's answer says how main differs from the branch (owner, 2026-10-28)

"we are supposed to be telling the agent always at every eval reply what is different with main so as main moves they are aware they need to fix things. They can ignore it and work and then merge at the end, but there are never any surprises." Each outside agent's `seon_eval` answer carries one line (`seon.agent/main-difference`), computed from the db at t and never stored, and the done answer names the same difference; the agent merges main when it chooses. Stated: docs/merging.md#main-moved-is-said-at-every-eval.

### Derived data may be cached; it is always regenerable from the db at t (owner, 2026-10-28)

"Derived data may be cached with the understanding that it may be evicted at any time so the process must always have a way to generate the data from database time T." A derived value (a score, a selection, a render, a diff) may be kept, in memory or in the store, as a cache keyed by its inputs, never by a clock; it may be evicted at any time, and its reader regenerates it from the db as of t. What a turn showed is derived this way: the scores recorded per turn are a cache of the scorer over the db at that turn, never the record. Refines "No derived data in the db or in ctx" (2026-10-26): ctx still holds the db's data only. Stated: docs/data-modeling.md#derived-data-is-a-cache-never-relied-on, AGENTS §5, docs/ctx.md.

### Focused sessions; no fan-out (owner, 2026-10-28)

"This system of launching 10+ agents just burned my tokens and was not productive"; "let's stick with just you and me"; "It would be good to have you mostly be working on the details of plans with me so we have less ambiguity too". A session does its work itself in one conversation, starts no other session unless the owner asks, and uses one helper at a time: long well-planned work to a fresh Sonnet agent, short context-heavy work to a fork, lookups to Haiku. Plans are written into task entries. The one session in the main tree of this ruling became named sessions that each own an area, each in a worktree of its own (2026-11-06, 2026-11-08); the rule against fanning out stands. Stated: AGENTS §2, docs/sessions.md#no-fan-out.

### Build the product, not infrastructure (owner, 2026-10-28)

"we built out a lot of infrastructure thinking it would speed things up and it really hasn't". A merge session, a worktree with its own root per lane, shared test roots, a test service, wake pipes and orders files were all built for parallel work: a one-function prove took 113 s, hangs and orphans came from the tooling itself, and no agent finished a real task. Before building tooling, ask whether it moves an agent finishing a real task; prefer deleting what served parallel work. Stated: AGENTS §9, docs/sessions.md#no-fan-out.

### Test results are a few facts per test and per function, with no history (owner, 2026-10-28)

"We want to be able to query things in the db, functions, tests, schemas and understand what tests are failing and timings so don't remove everything from the db"; on means, "Okay do it". Per test, on its var: the digest it was judged at, its status, its first failure while red, and running sums (runs, ms, ticks, bytes) that reset when the digest changes; per contracted function, running sums of calls, ticks and bytes that reset when its form changes; all `:db/noHistory`; written through root's one writer; a big value out of line; nothing per run. Replaces slice 1's file cache. Stated: task seon.proofs/test-results-are-a-few-facts.

### A kept db value is a reference, {:branch :commit :t} (owner, 2026-10-28)

"I don't want us carrying around large values for no reason. At those points we need to change db's into whatever facts we need to re-create that point in time." A db value is used, then let go; whatever keeps it past its moment keeps `{:branch :commit :t}` and re-creates it. Errors keep data, not a Throwable that pins the system. Stated: docs/storage.md#a-kept-db-value-is-a-reference, AGENTS §4.

### No surprises from main (owner, 2026-10-28)

"we are supposed to be telling the agent always at every eval reply what is different with main so as main moves they are aware they need to fix things. They can ignore it and work and then merge at the end, but there are never any surprises." Every eval answer carries main's difference from the branch, computed from the db at t, never stored. Stated: task seon.ctx/the-turn-shows-how-main-diverged.

### Context is structure first; ranking is judged by turns to green (owner, 2026-10-28)

"I don't think the ranker was built right if it barely moved the needle at all. We should be seeing 90% relevance and then the agent actually using it and shorter number of turns to achieve a goal as proof something is working"; "sure let's start with structure first". What the task's plan cites (functions, their tests, users and uses, sections, proofs) is shown by structure; ranking only fills a small second tier, later; shown items are append-only for the session. Stated in: [ctx](ctx.md#what-the-scorer-reads-from-a-pulled-node).

### The cron stays, written well (owner, 2026-10-28)

"We do need a cron, that's fine if it's well written." Stated: task seon.cron entry (the infra audit's verdict "delete" is overruled).

### The store is erased outright (owner, 2026-10-28)

"Yes, erase it outright with no archive copy." Done 2026-10-28; the standing store is test data until Seon holds a user's data.

## 2026-10-27 day rulings

The owner's rulings of 2026-10-27, each with where it is now stated; they stand over earlier entries where they differ.

### An inventory before a plan (owner, 2026-10-27)

"a new agent already has a plan? that can't be well thought out" (from session memory, 2026-11-10; unverified wording). A session taking over an area lists what exists (its file and line, its job, keep, extend or delete) before it designs. Stated in: AGENTS.md section 3 (the method, step 1), docs/sessions.md#sessions-own-their-areas.

### A merge runs the process tests its change reaches, automatically (owner, 2026-10-27)

`bin/prove --merge` runs every test and step the change reaches with no green at its digest, process tests included, so no one names them by hand. Stated: AGENTS §3, docs/merging.md#a-merge-runs-what-the-change-reaches.

### ctx stays seon.ctx/ctx (owner, 2026-10-27)

The name `ctx` stays as it is; no rename to another word. Stated: the code (`seon.ctx/ctx`).

### An agent changes a function by (in-ns) and defn (owner, 2026-10-27)

An agent redefines a function in its own REPL with `(in-ns ...)` and `defn`, Clojure's own way, not a special edit function. Stated: AGENTS §4 (get out of an agent's way).

### No lock on main's verification (owner, 2026-10-27: "Remove it")

Supersedes "main first, as a fact" (2026-10-26 night) and "Keep the lock for tests and checks": main's verification rarely runs (a branch that holds main and proved its tip merges with no run), and a hung run on main stalled every worktree for 48 minutes. Stated: docs/merging.md#main-goes-first, AGENTS §8.

### A branch proves its in-process tests; main's batch runs the process tests once (owner, 2026-10-27: "Get rid of the double testing")

Worktrees run plain `bin/prove` and report ready; the merge session merges a batch and runs one `bin/build` and one `bin/prove --merge`, because per-branch fast-forwards after a branch's own `--merge` are about N² process-test runs. Stated: AGENTS §3, docs/merging.md#a-branch-proves-its-in-process-tests-mains-batch-runs-the-process-tests-once.

### A known problem keeps its branch out; an unknown one takes its test out (owner, 2026-10-27)

"don't merge if we know there's a problem unless it's like this test which indicates we don't know what to fix and everyone is likely fine while we work on this separately. Just remove the test temporarily if we need to." A red a branch caused keeps that branch out of the batch; a red with an unknown cause the batch did not make takes its test out, in a commit naming the task that owns it with the evidence, and the owner puts it back with the fix. Stated: docs/merging.md.

### No test digests the whole tree; the daily full run is the net (owner, 2026-10-27)

"I don't care if it's perfect and if it's possible a test could be doing other work we don't know of. no tests should include all of src." A test's digest is what it reaches: forms, the config keys and schema entries it names, the pins of the libraries it reaches, and for a process test the process entry (since 2026-11-10 also what root runs: [a test that starts a root takes what root runs](#a-test-that-starts-a-root-takes-what-root-runs-owner-2026-11-10)); the cron's daily `bin/test --all` on main catches what reach misses. Stated: docs/merging.md, docs/source.md#tests-run-by-reach.

### A lock-free system (owner, 2026-10-27)

"I want a lock free system. This worktrees hack is temporary." No lock orders heavy runs; load stays down by not running the same work twice. Stated: AGENTS §8, docs/merging.md.

### The wiki is the one home; every function, schema entry and test cites its section (owner, 2026-10-27)

"we should be using the wiki for everything so it gets better because that's what agents are pulling into their context … This means don't duplicate what's in the wiki." A docstring says what its var is and cites the section for the mechanism; a missing citation is shown to the agent (an attention class), never a refusal of its work. Claude sessions' working papers are never system docs. Stated: AGENTS §7.

### Test results are a few facts through one writer (owner, 2026-10-27)

"one writer for sure"; "never 300k new records per test run, in the database, just the essentials." Results go to the standing root's writer; a reused test writes nothing; per-function cost is an aggregate written when it moves; a run's detail is one value out of line. Stated: docs/proofs.md once tests-are-facts merges.

### An eval records; what is derived for the agent is a render (owner, 2026-10-27)

"I want the eval system to stay rock solid and for it to be focused on executing code and instrumenting it … Info that is derived and made for the agent or the user should go into the hiccup", and of seon.turn/observe: "you shouldn't be marking if something has been observed you just record the data". The turn's derived reads are views over facts, an eval's tests never block its loop, and observe goes. Stated: docs/render.md, docs/turn.md as each merges.

### A relevance % means "worth reading" (owner, 2026-10-27)

"I really need a ranking ideally with relevancy % that we can do cutoffs for … showing everything isn't working"; "I want accuracy more than speed." seon.ctx.search/relevance is calibrated on judged useful/noise labels; search cuts on it. Stated: docs/vector.md.

### What a Claude session sees through MCP is what a model is sent (owner, 2026-10-27)

"making the results match the agents context we are generating. I want them to see all the errors and problems and for us to fix them at their source not patch things." One producer for the model's turn messages and the seon MCP answers. Stated: docs/agent.md once it merges.

### Unused is not dead (owner, 2026-10-27)

"Just because things aren't in use doesn't mean I haven't worked hard on the design" (seon.ratom): a new owner design with no callers yet is not cut for that, and code with no caller is cut only with the owner's go. Stated: AGENTS.md section 3, docs/merging.md#a-landing-message-carries-its-audit, this entry; the audit's cuts check the design's task first.

## 2026-10-26 night rulings

The owner's rulings of the night of 2026-10-26, each with his words and where it is now stated; they stand over earlier entries where they differ.

### A request is one whole task by default (owner, 2026-10-26 night)

Asked which default tonight's runs use, the owner chose one whole task over design-build and merge: `seon.template/request` makes the :whole task, and design-build and stages stay as experiment choices. Supersedes "make the default tests into two tasks -- design-build and merge". Stated in: docs/plan.md (Now), seon.template (jolt/work-text-and-requests).

### Paid runs only on context whose known problems are solved; $30 overnight (owner, 2026-10-26 night)

"How about the same $30 budget overnight. Only use paid runs though when we legitimately have solved the known problems with our context. Garbage in = garabage out and don't waste money if it's clearly got problems fix the problems. And not in a patch and hack the reds to green. But understand the scope of the real problem and we solve it at the root". A paid run starts only after a free audit of each request's context (`seon_context`, read by a reviewer that has not seen the work) finds none of the known problems (the archived paper known-context-problems-2026-10-26.md (git history) on jolt/work-text-and-requests); a problem found is fixed at its producer. A wait is never capped by a timer: paid runs wait on the hang fixes. Stated in: docs/plan.md (Now).

### Correctness and stability first, then efficiency to a stated bound; no regression without a reason (owner, 2026-10-26 night)

"dig into all rough edges and process issues we have and trace down and prioritize correctness and stability first and efficiency after that to a resonable (it should roughly take this amount of ticks, memory, etc) and don't accept regressions for no reason". A branch's report compares its numbers (ticks, bytes, tokens, the left-out count) with main's before it; a number that got worse is refused unless the report names its reason and who follows it up. Stated in: AGENTS.md §3 (No regressions), docs/merging.md#a-landing-message-carries-its-audit.

### The agent's view is the render of ctx; "page" names a wiki page only (owner, 2026-10-26 night)

"Yes it's just a render function for data. The render function for ctx is what is setting the layout and explicitly calling some render functions with args and just pulling data that is allowed to render itself. It's producing minimal hiccup that we can fully show so the agent understand what the user is seeing too. No separate systems right?" render-ctx is the layout: explicit render calls `[f opts & children]`, and every other value drawn by its schema's render, else the printer; the model reads its hiccup whole. The words: the render of ctx (not the agent's page, :page), the system prompt (not system-page), help (not help-page), the task's render (not task-page). Stated in: docs/vocabulary.md#words-we-do-not-use.

### The render of ctx never repeats what an eval shows (owner, 2026-10-26 night)

"If something is the same as the eval the evals have references to results right so we can sub those in? I don't want to over complicate the first tests but the render should be quite different really from the eval data as the focus for the ui is on the prose and showing the user what you are working on now how you are doing it", then "my point was to not show the same eval data in the render function". The render shows the prose and the work in hand; where it touches an eval it carries the eval's handle, its value drawn only when opened, and the model reads the eval itself once, in its transcript. Stated in: jolt/render-walk (build 3, 4029413e85's shape).

### Flaky results are kept and shown; test-results/ and runs/store erased at that merge (owner, 2026-10-27)

Asked whether to erase both for the flaky-results change: "Yes: erase test-results/ and runs/store at that merge (both are test data)." A digest records every status it has seen; red and green both is :flaky, judged red, never reused as green and never rerun. Stated in: docs/merging.md (on that branch).

### A work task merges itself through the one gate; no merge task per file task (owner, 2026-10-27)

Asked whether file tasks keep a merge task each (434 declarations became 868 tasks): "Yes: drop with-merge from task files; a work task merges itself through the one gate. Keep design-build and stages as experiment templates." Stated in: the one-merge design (task seon.merge/one-merge-system; docs/merging.md).

### All work happens inside Seon; worktrees are retired (owner, 2026-10-27)

"The goal is to stop using the worktrees and to do all work within seon itself". Claude sessions work as outside agents through Seon's server (seon_attach, seon_eval, seon_done): each on its own Seon branch at the REPL, merged by Seon's one gate and written back to git by root. Git worktrees and bin/prove --merge in a checkout are the bridge until that path holds every kind of change. Stated in: docs/plan.md (THE PLAN, step 0).

### Styling: compact hiccup, every element earns its spot (owner, 2026-10-26 night)

"Find efficient styling that we can pull in references for automatically and make it so the fully printed hiccup is compact and everything earns it's spot. The goal isn't to render everything, but to highlight key things to the user and to compute additional derived things to help the agent too". A component's root element carries one class, its render's name; the stylesheet is gathered from the renders drawn. Stated in: docs/render.md#style.

### Old branches: audit, then delete (owner, 2026-10-26 night)

An agent read each old branch and dirty worktree for anything not carried forward (the scratch paper deleted-branches-2026-10-26.txt (git history) lists the tips); what was worth keeping is filed, then the branch is deleted. Raw stores of paid rounds stay the owner's call.

## 2026-10-25 rulings

The owner's rulings of 2026-10-25, each with his words and where it is now stated.

### Message delivery: compare, then the better design or the simple one

Owner, 2026-10-25, on main-first delivery (agent loop) against delivery by merging a fact from the sender's branch (ctx-atom §11): "Launch a comparison. I would prefer real flow semantics where processes are independent, but not at the cost of stability and performance and complexity. We either have a better design or we do the simple one that works". Result (agent loop, 2026-10-25, design §3a 16066cc0b): main-first, with `:seon.run/taken`; 4.96 ms vs >=16.79 ms a delivery, the reconciler reads only main, no lost wakes in either over 2,000 schedules. Stated in: docs/turn.md §3a on ``jolt/agent-loop``.

### The related-section count is a config key; one problem-entry helper's bounds accepted

Owner, 2026-10-25: approved one new config key, how many related wiki sections the screen shows (0 turns it off); accepted raising 5 derived-problem cost proofs' call bounds by one contracted call per problem, for one helper in place of 8 copies. Stated in: this entry.

### Dogfood rulings: design registers proofs; each form's value (superseded 2026-10-28: one form, Clojure's value); stale means meaning changed; pilot budget $5

Owner, 2026-10-25, on the dogfood findings (the archived paper dogfood-and-docs-2026-10-25.md (git history) on ``jolt/dogfood``): `my.task/update!` takes `:seon.task/proofs`, so a design stage registers the red proofs it writes; an eval call's response lists each form's value in order under the call's one handle (``result/eN`` holds the last, like `*1`); a wiki section is stale when a cited var's contract, arglists or docstring changes, not its body; the pipeline pilot may spend up to $5 ("use up to $5 even I want to learn and fix the design"). Stated in: docs/tasks.md#the-done-rule (proofs), docs/agent.md#your-turn and docs/prompt.md (each form's value), docs/health.md#a-stale-section (stale on contract or docstring; the contract carries the arity, so no arglists); the pilot budget in docs/model.md#paid-runs not yet.

### No guards, least of all in Jolt or the db

Owner, 2026-10-25 night: "make sure we aren't doing stupid shit with guards and fences again especially in jolt the language or the db. It's always a sign we don't have the right solution which negates the need for a guard". A refusal, check or special case added to Jolt, Chez, Datahike or Seon must be the right solution itself (an error a caller can act on, at the cause), never a guard around a wrong design; where a design makes the bad state impossible, the check goes. Stated in: AGENTS §4 "Get out of an agent's way" and §9 (guards); applied by today's audit.

### An agent's spawn costs what a fork costs; no unnecessary complexity

Owner, 2026-10-25 night: "do your best to make the codebase work and not grow in unecessary complexity. Real features expect LoC and some performance, but even 1.x seconds to me still seems long just to spawn an agent? how was it ever 8.5s? like what are we doing if we can fork in ms?" An agent's spawn is measured phase by phase (fork, store open, task load, session load, first render) and brought toward the fork's own milliseconds: anything a spawn redoes that the parent already holds is the defect. Stated in: not yet (agent loop).

### @ctx prints as an atom; the web UI renders it as a block and looks good, built from the existing mockups

Owner, 2026-10-25 night: "the agent will have the value printer print out the atom like a normal atom with our structure and then in the web ui for that eval we'll render it in a block. I want the webui done so it looks good too and I've seen some good mockups so we don't need to start over to acheive it". The model reads `@ctx` printed by the one printer; the page renders that eval as a block; the web UI (root's page and the agent pages) is finished to look good from docs/render.md, the static mockup of 4624f2f4f and the value view's mock, not redesigned. Stated in: not yet (agent view r3, ctx-atom).

### One-go tasks for every run; fix the bug, not the kind (owner, 2026-10-26)

"switch to one-go for all tests until we figure out a better solution and don't blame one go on the bug that hung the agent fix the bug!". A request is worked as one whole task (design, implement and merge in one run), the staged kind kept only until it is deleted; the a3 hang (an agent's wait on its test child) is fixed at its cause. Now stated in docs/plan.md THE PLAN step 2.

### A stall is seen on the page and becomes root's task (owner, 2026-10-26)

"the hangs are super bad especially if you don't notice an agent has been stuck"; "eventually a page but we should definitely be an agent once the agents are somewhat competent. The goal is to have a task that we can pre-fill with a template and we can give root the deal with this task … define the boundary conditions we are going to accept … in our tasks.edn and the repl via claude agents until we can get seon agents doing productive work". A stall is a fact on the agent (cleared when it speaks or ends), an open problem on root's page and in ctx, and a task from a template addressed to root, whose proofs are the accepted boundary conditions (the stall cleared, the cause recorded with evidence, a defect filed). No push notification. Claude sessions work such tasks through seon_attach until Seon agents can. Now stated in docs/plan.md In flight (agent loop).

### Not passing until the flow is bounded and within budget (owner, 2026-10-26)

"it's not a passing solution until all functions are spec'ed including with cost estimates and we can budget roughly what the max total cost is among data flows within the system being referenced (both existing functions exercised and new functions) and we can have that also be a goal the agent is working towards". The done rule grows to: contracts and cost bounds on every function the change defines or modifies and on every project function its written code references (owner: "explicitly referenced in the code that is written"; read from the forms' stored :form/refs, verified on root's store); the composed bound within the task's budget; generative cost tests green. Library calls are priced by measured constants, so coverage grows with each change. Shown on the agent's page as the references' call graph against the budget. Now stated in docs/plan.md THE PLAN step 5.

### The agent's loop: its world, its REPL, bounds as the plan, feedback on change (owner, 2026-10-26)

"reduce programming to its more mathematical origins of where a problem is broken down and thought of in terms of limits and the cost of doing something roughly"; "writing bounds is also sketching out the algorithm … ideal for a REPL interface … if we are showing the diffs with main each turn the agent knows that new functions exist or conflicts occur and the agents environment is also clear with the data in the agents little world (pull pattern) we can with automatic efficient testing when things change also give feedback on what is failing and why". The agent's world is its pull pattern (`@ctx`); it works by defn, deftest and evals; a function's contract carries a cost bound as a function of its arguments, checked by generative tests, composable along the call graph; each turn it is told ctx's and the page's diff, main's moves included; the tests its change reaches run automatically, and their failures and bound overruns are data in ctx. Laws and scenarios are tests, not a new kind. Now stated in docs/plan.md THE PLAN.

### One rendering system: the data describes the page (owner, 2026-10-26)

"I keep asking not to build separate parallel systems for everything and it seems like you keep doing it … There is only one context rendering system and it needs to be built from the parts. The system itself just builds what the data describes … all values in the render tree that don't have an explicit render should be looked up and rendered. It was always that and we were building a separate system?" The system is: ctx (the agent's data); ctx's schema's one layout render; the tree walk drawing every value by its explicit render, else its schema's render, else the printer; one cache by render fn and args (Reagent's rule); the model reading the same hiccup with each turn's diff. A part layer, key cases, a second memo, hand-built cache keys or explicit dispatch calls beside it are deleted on sight. Now stated in docs/render.md and docs/plan.md THE PLAN step 3.

### No invented words; a render is a function and its data (owner, 2026-10-26)

"Please stop making up words just use the components and terms we agree on and stick with clojure terms and their library terms for everything else. A render is just a function and the data being rendered. No story". "Story" and "run-state" become the task's render; any new name is a Clojure or library term or one already agreed. Its §7a entry merges with the rename (agent view and ctx-atom), since docs-test holds every listed word to zero uses.

### Everything the agent and the page show comes through ctx (owner, 2026-10-26)

"any changes you want updated in the agents context and html should go through database updates that reach the same data the context pull gets which keeps the ctx atom in sync". Nothing on an agent's page or in its request is read from anywhere but its ctx; outside facts (a person's messages and acts, a stage, a verdict, a run's status) reach the agent's branch by `take!` and so its ctx. Now stated in docs/plan.md THE PLAN step 3.

### The page is the render of @ctx; the chat is prose; values render when opened (owner, 2026-10-26)

"we need a layout function which I think is our render-ctx function … with sub renders so it isn't one giant function and those sub renders can consume any data in the context"; "if the return value is just data we don't need to show it … the chat … basically just prose and thinking and an arrow expanding that would show the html rendered value … don't render things that we don't need until the user expands it … the agent should know the user is digging into it". render-ctx is the render declared on ctx's schema: the task column, the chat (prose, thinking, each eval one collapsed line drawn when opened), and the tiles column (evals whose value has a declared render, newest first); a person opening an eval is a fact the agent takes. Now stated in docs/plan.md THE PLAN step 3.

### Try it in agents' REPLs first; design after; never another system (owner, 2026-10-26)

"I want this tested with agents in the repl and confirmed to be correct and efficient and then the design is proposed after we've tried a few things and have found the best way" and "do not build yet another system"; on caching: "the database knows exactly what has changed … only need to re-render those parts. The rest should hit cached renderings. Make this simple and fast." A design is proposed only with REPL experiments on a live Seon agent behind it; a new concern (stale wiki sections) becomes a part on the one render path, never a mechanism of its own. Now stated in docs/plan.md THE PLAN step 3.

### Keep it simple, so the system is easy to modify (owner, 2026-10-26)

"Keep things simple so we can easily modify the system". A design that needs a second mechanism, a stored derivation, a projection or a special path beside the existing ones is sent back; each slice reports what it deleted. Now stated in AGENTS.md §4 (a mechanism is built once) and docs/plan.md THE PLAN step 3.

### Two outputs: an eval's data, and the page with its diffs (owner, 2026-10-26)

"there are only two outputs too: @ctx returns data just like any repl eval and then we need to show the UI and the diffs to the ctx and the html at every turn as formal data" and "don't leave anything out I want the agent to understand problems with the rendering functions … I don't think we need the separate seon.render/ai work". The model reads each render's hiccup exactly as it was returned (nothing projected or stripped), the whole page on the first turn, then ctx's diff and the page's diff each turn; `:seon.render/ai` and every ai face are deleted. Now stated in docs/render.md (jolt/design-rev42).

### No derived data in the db or in ctx (owner, 2026-10-26)

"no we don't store derived data in the db or the ctx atom". ctx is the agent's data pulled from the db and patched, nothing else; the story, related sections and order are renders over the db as of t, memoized by value. Supersedes revision 4.1's derived members. Now stated in docs/render.md. Refined 2026-10-28: derived data may be cached, in the db too, when it is always regenerable from the db at t (above).

### Stability and correctness first (owner, 2026-10-26)

"oh no I thought we had fixed all the hacks we put in that was causing stability problems? … Are we removing the hacks in datahike, jolt, etc?" and "Get them to main and prioritize stabilty and correctness". The fork server's boot order and one process mode (stability lead), Jolt's swallowed timer errors and the double-fork failure (agent loop), and the LMDB env_open EINVAL under load (full-run-reds) come before new features, each shipped to main in small verified pieces.

### The REPL stays simple; every enrichment is a render (owner, 2026-10-26)

"as part of the context rendering we need all active effects we were manually doing before to now be render functions. So similar embedding lookups, parsing the strings for documentation references we need to show, etc. So the compact and clear formatting for the hiccup is carrying more weight and we should think carefully about it's design. I want the repl and evals itself to be simple and stable and not doing a shitload of other things just like a real repl is simple and yet the tool built around the concept can do nice things for the user." An eval only evaluates and records (value, output, its transaction); ripple effects, related and similar lookups, cited references and staleness are renders over facts, memoized by value; the hiccup vocabulary those renders produce is designed with care. Stated in: docs/render.md (to revise with slice 2b).

### The wire: our channels inside the provider's roles (owner, 2026-10-26)

"can we turn it into system, agent, repl, and html and anything else we want and we can show full values or diffs in the right sections and then it's clear what's going on. The eval changes something and if the change updates something else they see those ripple effects?" The provider's roles stay (system, user, assistant, tool); system carries the rules, assistant the agent's prose and evals, tool the repl's answer with the eval's ripple effects (redefinitions, re-renders, synchronous ctx changes), user the world in fixed labelled sections ([from …], [changes], [page]).

### The request is a pure function of the db at the session's t's (owner, 2026-10-26)

"so it's very clear from the agent context side what is happening and it's all internally consistent and how we generate ctx data and then how it gets turned into the html and the agents context and how it's byte stable from database T and not a stored set of messages we are sending to the agent". No sent text is stored; each turn's request is re-derived from the db at the session's turn points with the code as of each t, so its prefix is byte-identical to the previous turn's by construction. Stated in: docs/render.md.

### Changes arrive as messages; the eval response says only what the eval caused (owner, 2026-10-26)

"So where does the html diff come from? It is a rendered context not the context itself? We want the agents updating the render functions and then seeing the update take effect … Maybe we just need this built into the repl as part of the eval response? How much data are we packing into this at this point though? … I think we might need explicit messages for changes coming in and having those return the diffs." An eval that changes a render answers which parts re-rendered and how; the eval response carries only what that eval caused; every change from outside (stage, results, verdicts, main moving, people) is an inbox message carrying its diff in ctx's shape: one delivery for messages and changes.

### Evals run once; the agent is told what changed (owner, 2026-10-26)

"evals maybe should never be re-run (unless we are resuming a session) and instead we should just show the diffs which are happening as things change like the task changes? Messages are also async and out of band so maybe it's a similar mechanism? How do we best inform the agent shit has changed between turns". Evals run once (re-run only on resume); no screen forms or replay; each turn ends with one "Since your last turn" message: the ctx parts that changed (task, proofs and results, verdicts, merges, edits to what the task cites) and the messages taken, in order; a session starts with the page in full. Supersedes "Evals are evals" where it speaks of replay.

### Every change is told, as a data diff in ctx's shape (owner, 2026-10-26)

"all changes should be worth telling (if we have weird naming and unnecessary updates and machinery instead of actual values we have the wrong design) and we should both tell it in a data diff that reflects the ctx structure so they can easily update it and then there's also the html changes which I don't think are necessarily always bad to show the updates even if there is some duplication. I'm thinking about the user messaging the agent and they get the message into their ctx and then that's reinforced by the transcript updating". No told-filter: noise in a change is a defect in what ctx holds; "Since your last turn" is paths into ctx with their new values, plus the rendered change where it helps; a user's message is an entry in ctx and in the transcript.

### Evals are evals; both contexts always live (owner, 2026-10-26)

"I want the components to be the same -- evals are evals and we are manipulating the context / updating the db to replace the values with their rerun values and that's what's being rendered. We have history so don't keep around old things keep the ctx updated so we are always rendering the db. The agents context and the users context are always live." A replayed eval's new value is transacted onto the same eval entity (history keeps the old); everything renders the current db through ctx; conversation evals are never replayed, so the cached prefix holds, and replayed evals go on the screen side as diffs.

### Owner answers, 2026-10-26

The owner's answers to root's questions of 2026-10-26: the joint ctx/UI design is approved by root when it follows the rulings, the paid budget continues, replay is explicit, caching is accretion, and the old branches are audited for merit.
1. The joint ctx/UI design: root approves and it is built when it follows the rulings ("yes").
2. The $30 paid budget continues today ("yes").
3. "All evals are 'live' it's a question of whether we are replaying them and that's just a context trick we are using to always have some forms be executed and replaced on every turn. So make it an attribute or something so it's clear if we re-execute or just pull it's cached value. This is an explicit thing not an inferred": replay is an explicit attribute of an eval or its form.
4. "Caching should be deterministic and if we shoot ourselves in the foot and change the data half way through we lose 50% of the cache, but if we are accreting everything up to the dynamic forms it should be caching at that rate."
5. "audit the branches for merit with sonnet and suggest integrations".
- On the hiccup the model reads: "if we remove the styling and don't show the removal how do agents easily update it? I want both a) a not giant representation of the UI that the user is actually seeing and b) not to distort it": compress, don't remove (semantic classes, a theme map once, render markers, cuts that say what they left).

### The web UI is the render of ctx

Owner, 2026-10-26: "I was thinking the webui needs to be built from rendering the ctx. This is the same ctx we are dynamically pulling and rendering into the UI right? How are we doing layout and how is the user agent represented and how are we sending messages back and forth and having them be updated". Direction: one producer: an agent's page is the render of its @ctx (each key by its declared render, the conversation its evals and messages, layout a declared render the agent or user can change), the debug toggle its :seon.render/ai face; root's and the user agent's pages likewise; a message is an entry in both ctxs. A joint design (agent view, ctx-atom) comes to the owner before building. The owner, the same morning: "are we deriving the same data twice? why?" (four times today: the page walk, @ctx, the screen, the request) and "I thought we merged everything to becoming hiccup as that's simpler and the agent can read the hiccup just as well as anything else. The goal is to get smarter explanations for data in context that also aligns with what the user is seeing … a chat side where it's mostly prose and expandable dig into details. But I want a friendly UI where everything is intuitive": one derivation (@ctx), rendered once to hiccup that is both the page and what the model reads; a screen side of live rendered evals and a chat side of prose with expandable details. And: "I want to show all the hiccup once and then diffs so we need to make the hiccup efficient": the model gets the whole hiccup once, then diffs; the hiccup printed for the model carries meaning only (no presentation attributes or layout wrappers), measured in tokens. Stated in: not yet.

### Fix causes, not symptoms: the repeat rule is under investigation

Owner, 2026-10-26 morning: "the repeat thing sounds like instructions or formatting and we should fix it not patch it. investigate." and "Design and launch fixes for all the F issues and ask me if you aren't sure how to fix them. We want less hacks and more fixing root causes". The provisional repeat rule (told once, ended at the second consecutive time) is a patch; the live-agents session classifies every recorded repeat by what the agent saw before it, and the fixes go to those causes (instructions, response format, the screen), after which the rule is expected to go. The pipeline's F1-F4 are fixed at their causes. The owner also approved deleting the superseded old branches (the scratch paper deleted-branches-2026-10-26.txt (git history)) and archiving finished sessions. Stated in: not yet.

### Overnight budget $30; old branches triaged forward; ctx built render-first

Owner, 2026-10-25 night: "$30 overnight. triage and move forward the good ideas from the unmerged branches prioritize stability performance and the agent getting shit done well. Yeah build the ctx atom part I think that'll make it easier for the agent to update data and their own context. Just deref'ing the @ctx will trigger the web ui rendering which the agent will also see so focus on the rendering functions part so this is immediately useful to the agent and they can easiliy update it and just add more data and render functions". Paid runs may spend up to $30 overnight (live agents and the pipeline pilot), each run's first turn checked first. The old unmerged branches are read and their good ideas carried forward as branches (stability, performance, agents finishing work first), not only filed. The ctx atom's build leads with rendering: `@ctx` is what the page and the agent see, and an agent adds data and render functions to it and sees them at once. Stated in: not yet (sessions ctx-atom, printer, live-agents).

### Overnight: all cylinders

Owner, 2026-10-25 evening: "I approved highlight.js. launch all fixes. I'm not playing any games I'm going to bed. You can work on all cylinders overnight." The one-run machine rule is lifted (then: up to three heavy runs while load stayed under ~8; since 2026-11-10 load is judged by cores and free memory); every ready fix runs and merges as verified; highlight.js approved in agent view's session. Stated in: this entry.

### The value view: highlight.js, agent hiccup unrestricted, built after the printer

Owner, 2026-10-25, on the archived paper ui-values-design-2026-10-25.md (git history) (``jolt/ui-values-design``): highlighting by vendored highlight.js; an agent's hiccup renders as HTML with "no restrictions" (no allowlist: agents are unrestricted, decisions "No restrictions"); build it once code-as-code (the printer branch) merges. Stated in: docs/render.md#a-value-on-the-page-is-its-structure (built: merged from jolt/ui-values-design).

### Platform stability first

Owner, 2026-10-25, on two test hangs in a day: "prioritize and force rebuild and clean the db if we need to. Platform stability is very important so find out why and fix the cause". A hang or a flaky red is found at its cause before other work in its area; root may force a rebuild and erase the standing store (test data) when stability needs it. Stated in: this entry.

### Related things are found by measured search over every kind, through stored edges

Owner, 2026-10-25: "have you merged the tools and tested any different ways to find the best way to search and show related things (docs, functions, schemas, etc?" and "Figure out the most beneficial references to include and to make it so all different schemas are searched. If we can discover data and when displaying the functions that data operates on and vice versa (look into always referring to the related things also via our db representation right?" Every schema kind (Malli registry, db attributes, config keys) is searchable; data and the functions that operate on it are linked by stored edges (contracts, refs) read from the db; what the screen shows by default is chosen by measured recall on merged tasks. Measured (docs/vector.md, 32 merged tasks): graph+embedding fused is best for sections, functions and config keys; a shown function's registry keys earn their tokens, a key's functions stay a query; embedding rendered hiccup is not worth it. Owner, 2026-10-25: "okay implement the best related context fixes we know of": the fused defaults and `:form/keywords` (root's gate approved). Stated in: not yet (session related-sections).

### Code is shown as code; the page shows values as structure

Owner, 2026-10-25, on a1's debug page (`(my.task/status)` returning a deftest's source as one escaped string): "Why are we returning so much data in such a strange format? If we reference a function we should return the quote escaped forms so agents see more clojure code as is normally seen in files or the repl"; and "What is the status of the UI? We can do more complex representations of values there". A value that is source text prints verbatim as code (marked at its producer, read by the printer and the page alike); the page renders a record's stored value as structure (maps, tables, code, collapsed paths opened on demand), designed for the owner's approval first. Stated in: not yet (sessions printer and agent view r3).

### The printer has a generous budget; the fix for noise is not generating it and not rendering it

Owner, 2026-10-25, on the printer audit: "make sure the value printer is working good everywhere" and "And that it has a generous context budget. Id rather us focus on not generating crap and then not rendering crap." The printer's budget stays generous; noise is fixed at its producer first (it is not made or stored in that shape), then by not rendering it (no placeholder-per-entry elisions, repeated paths, empty keys); truncation is the last resort. Stated in: not yet (session printer, ``jolt/printer-everywhere``).

### A change to a schema is an explicit fact the eval shows, never a hidden digest

Owner, 2026-10-25, on root's approval of a `:form/meaning` digest for the stale check: "I feel like this should be runtime eval that shows the change to the schema so they know about the change and learn how to update schemas. I want explcit changes in the system to modeled accurately". A contract or docstring change is already a transaction (`seon.program/def-tx` asserts and retracts `:var/contract` and `:var/doc`, history kept): the eval that makes it shows before → after and the sections citing the var; staleness reads those facts since the section's read t. The digest approval is withdrawn. Stated in: docs/health.md#a-stale-section, docs/agent.md#your-turn (the response's `:redefined`).

### Starting and resuming are one mechanism; replay is told and switchable; agents work in the UI

Owner, 2026-10-25: "we are building the same muscle here to load any session from any point in time too right? I don't want a separate system for running agents. We don't autoplay the things we know are effects and we inform the agent at every eval if it was replayed so they understand the env they are in ... I want a very 'real' environment for our agents with live feedback. Instruct agents to think about and use the UI and to get it working for me so I can follow along with experiments." And: "Optionally config to run everything even effects so it's easy to switch on (it's just a filter of the forms right?)". Starting an agent is loading a session at a point in time, the same path as resume; replayed evals are marked as replayed in what the agent sees; replaying effects is one config switch on the replay filter; agents' instructions say to use and improve the UI. Stated in: not yet.

### No new linking attributes where :needs and the record do it; everything survives resume

Owner, 2026-10-25, on the agent loop's proposed attributes: "What are these attributes doing? We have the :needs way of connecting the graph literally together ... Every agent should have a stable key of course that's essential. ... namespaces that 'survive' resume seems like the wrong idea -- of course everything should survive if we can resume it." A stable agent key: yes. Links between stages and sessions come from `:needs` and the record, not new attributes; resume restores everything. The design re-justifies each remaining attribute or drops it. Stated in: not yet.

### Messaging is one function, `my.agent/send-message`

Owner, 2026-10-25: chose one function for an agent to message others (named ``my.agent/message!`` then, `my.agent/send-message` now). Stated in: not yet.

### No restrictions; any agent can be root; agents edit their own dynamic forms

Owner, 2026-10-25: "No restrictions right now. I want to focus on not building separate systems for everything. All agents can be root. Root is just a name we give them and I think we'll need multiple ones responding to different things so we have their id be generated just the same. The task can just be instructions that say 'you are root running on main etc' ... I think we should have a way for agents to edit what forms are dynamically being run each turn so they can easily self modify those too". Any agent may release a held task; root is an agent with root's instructions on main, id generated like any agent's, and several may run; an agent can change its own per-turn (screen) forms. Stated in: not yet.

### A request's stages are separate steps in one session

Owner, 2026-10-25: "I want the investigation and implementation and merging steps to be separate steps so we can instruct the agent through some reliability steps but their previous context will be available so I do want the context to transfer (just have it be the same session right?)". Design, implement and merge stay separate tasks with their own instructions and checks, worked in one session: the next stage continues the same conversation on the same branch. Also: "Errors can be customized again ... Maybe that is a good easy task to investigate?" A candidate first request for the pipeline. Stated in: not yet (owed the agent-loop design).

### Every agent parks; resume replays pure forms and asks for effects

Owner, 2026-10-25: chose "Always park" (measured: a parked agent is 1 thread, 0% CPU, wakes in 7-35 ms; 47-300 MB each) and "Auto-replay pure, ask for effects". An agent parks until its task finishes. On resume, effect-free forms are evaluated again automatically and effectful ones (sends, writes, spawns) are listed for the user's approval; every eval records the functions it called so effects are known, not guessed; each task's agent namespace is stable, so forms never name an agent id. Stated in: docs/processes.md#a-parked-process (parking); the replay not yet (agent loop part 4).

### A session continues across turns and resumes; its context is generated from the db at a commit

Owner, 2026-10-25: "a resume where the agent wakes and responds to a message it should have the same previous context ... If a 'run' is too tightly coupled and we need to decouple the concept of N turns then lets design something to address this. We want to be able to resume any session which means continuing and not losing context. Again we should not be saving context and piecing it together we should be generating context from the db at a commit point and it should be immutable (except for the dynamic parts and the current eval)". The conversation an agent sees is its whole session, generated from the db at a commit point, never stitched from saved pieces; a turn budget is not what bounds a session. Stated in: not yet (the loop design owes it).

### Root's special cases go, all four

Owner, 2026-10-25: chose all four of the one-system audit's first items: root as an agent on main (no unbounded root eval), delete the dead merge path, root's work as tasks (merge conflict, dead agent, health, red proofs, update) run on main's current commit, and one prove path. Stated in: not yet.

### Agents stay running, parked, between messages; any commit can be resumed

Owner, 2026-10-25: "Maybe we should just keep the forks running and just parked. Flow can do this so do this and we don't have to worry about restarting. I still want a resume that can take any transaction commit point and we can get an agent back to the mostly same env even if it means re-evaluating the forms (I think if user approved this is fine)." A live agent's process parks on its input (Flow) when it has nothing to do and wakes on a message; a separate resume takes any commit point of a branch and brings an agent back to nearly the same environment: its conversation from the db, its data results from the store, and, with the user's approval, its forms evaluated again. Stated in: docs/processes.md#a-parked-process (parking and waking; the resume from any commit not yet).

### Correctness and performance come before lines of code

Owner, 2026-10-25: "prioritize correctness and perf over lines of code. Lines of code is not my main metric". A change is judged first on whether it is right and on its measured work (ticks, bytes, turns to green); a smaller diff never wins over a correct or faster one. Resistance to growth (2026-10-23) still means reusing and improving the core rather than adding one-offs. Stated in: (home not found, 2026-11-10).

### Root is main; everything else is the same system with different config

Owner, 2026-10-25: "Root is main and is in charge with keeping main stable and working well. Agents can explore different ideas safely in their own forked processes ... and then we merge to main via the same task system. If a merge fails root needs a root specific merge task ... other tasks for root to handle like when agents die or health checks fail. All of these I want to run on main CURRENT ... we need to refactor to make this possible not build a separate system for root. It's one system where we use data and flows to have a runtime, db, and agent running against a task. Who and what they are doing on what branch they are is all just config options." Root's own work (a failed merge, a dead agent, a failing health check) is tasks run by agents on main's current commit; root is not a separate code path. Stated in: docs/users.md#the-entities (root is an agent keyed "root", the same schema).

### Agents restart from their record; the lifecycle is Flow's

Owner, 2026-10-25: "it's only 0.08s to start a fresh agent and we can resume the context since it's immutable and generatable from any T id? Hell yes. Wake the agent once it's up ... Can we just have the flow step function and the lifecycle handle the forking and killing?" A message wakes or restarts an agent; how, built on Flow's step-fn and lifecycle, is being researched concretely (sources, then REPL experiments, then an Opus design and build) before it is built. Stated in: not yet.

### The screen is sent as diffs between turns

Owner, 2026-10-25: chose "Diffs between turns". `(seon.screen/show {:since t})` answers what changed since the agent's last screen at db t, the unchanged sections named; the first screen of a run is whole; a section about to be cut by the history budget is sent whole again. Stated in: not yet (owed the design note and docs/context.md).

### Merge the agent-view branch to main once its review is done

Owner, 2026-10-25: "873 lines isn't that much if you think it's good lets merge to main. ... I want to see the web work too so if merging to main makes that easier lets do it". Stated in: this entry.

### A request is one whole task, for now

Owner, 2026-10-26: "until we solve this we should keep experimenting with having the agent do everything in one task until we get this idea fully baked". A request's default is one task that designs, builds and merges (`seon.template/request`); design-build then merge ("make the default tests into two tasks -- design-build and merge", the default for one run) and the design → implement → merge stages stay, chosen for an experiment ("don't get rid of the other tasks"). Measured on the same ten requests, thinking off: one task 2/10 merged at $0.073 and 92.4% cached; the stages 2/10 at $0.145 and 86.3%; design-build then merge 1/10 at $0.068 and 93.2%, four of the ten requests already done on main (notes: context-for-20-turns). Stated in: [tasks](tasks.md#a-request-is-one-transaction).

## 2026-10-24 rulings

The owner's rulings of 2026-10-24, each with his words and where it is now stated.

### A failed model call keeps what it sent and its typed error

Owner, 2026-10-24: "Approve :seon.turn/error." A turn is written with its sent refs as the request goes; when the call ends it gains its reply and usage, or the typed error as data (as the run's end has `:seon.end/error`). Now the model call's `:seon.model-call/error`: a turn is a number, not an entity (owner, 2026-11-04). Stated in: docs/turn.md#the-turn-is-a-number.

### A turn records what it sent as refs; texts are read as of the turn

Owner, 2026-10-24: "Approve :seon.turn/sent only; refer to the system prompt's wiki section and the task entity as of the turn's t instead of new :seon.text attributes." One new attribute, `:seon.turn/sent`: the ordered refs to what the turn's request carried (its evals, messages, the system prompt's wiki section and the task entity), resolved as of the turn's t (history is kept); no separate text store. Stated in: docs/ctx.md#the-agents-entity-holds-its-work (since 2026-10-29 the sent refs are the turn's model call's, `:seon.model-call/sent`).

### A config key does what it says; a provider's schema refuses a combination it forbids

Owner, 2026-10-24: "have the config do what it says and if there's a combo that's not allowed we can just have a schema validation for deepseek that confirms the right settings are set". Whether earlier reasoning is sent back is a config key (resolved task -> model -> provider -> config) that is obeyed as written; a provider's config schema (Malli, under `seon.config.*`) refuses a combination the provider rejects (DeepSeek's docs: thinking on with tools requires all earlier reasoning sent back), so a bad setting is an invalid config at load, explained, never a silent override. Stated in: not yet (owed resources/seon/config/default.edn and docs/model.md with the agent-conversation work).

### The agent view and context design is approved

Owner, 2026-10-24: "Approve the design as written, including the owner items in section 11." docs/render.md (revision 2, commit 2067be8df): the conversation is function-calling messages of evals and responses; the screen is evals run before each request and replaced each time; views are plain HTML hiccup rendered once per set of reads and shared; reads recorded by Datahike at its index. Section 11: root renders every browser view and agents commit after each eval; new attributes `:seon.screen/*` and a user agent entity; a prose-only reply ends the turn and waits for the user; paid runs of about $1-2 for steps 0-2; the standing store is erased when the record changes land. Built in that order, steps 0-2 first with numbers before step 3. Stated in: the design note (owed docs/render.md and docs/context.md as it merges).

### Names tell a story; the printer shows every value well

Owner, 2026-10-24: "Beautiful naming and the render tree should tell a story and not just be full of random words" and "We can adjust the value printer too as we want a general printer which shows all values well". Records and views are named by what they are, in Clojure's, nREPL's, Reagent's or the chat API's words (an agent's conversation of turns, each eval's response, the user's screen); a fix to how a value prints goes in the one printer, for every value. Stated in: docs/vocabulary.md, docs/render.md#one-printer.

### A prove run is keyed by the digest of what it reads

Owner, 2026-10-24: "okay lets try the digest". The test service's run key (`seon.prove/run-key`) names the digest of the code and tests the run reaches, as each test's result is already keyed (AGENTS §5), not `branch@t`: a write to main that touches none of it never starts a second run. Stated in: not yet (owed docs/merging.md#the-test-service).

### Root merges verified fixes as they come in

Owner, 2026-10-24: "merge fixes as they come in". A fix branch whose report carries its numbers, its self-review rating and a green bin/prove is merged by root without asking (bin/prove and smoke on the merged tree, then bin/update); a branch that adds a namespace, config key, schema attribute, proc or route still needs the owner's gate. Stated in: docs/merging.md#a-branch-lands-with-binland (whoever proves a branch lands it), docs/merging.md#a-landing-message-carries-its-audit (the gate).

### One render per schema, declared where the schema is registered

Owner, 2026-10-24: "Okay I'm willing to try a single render function -- so it's specified during the register! for the malli schema?" and "If we can nail a compositional design and the function naming the hiccup could be extremely readable and concise to build complex frontends (this is important for me)… if it hasn't changed we aren't re-rendering". A schema names one render function (`:seon.render/fn`, set by `my.schema/register!` or the schema resources), replacing the two faces `:seon.render/html` and `:seon.render/ai`; views compose as hiccup (`[f props & children]` calls f; plain data renders by its schema; the db is passed when the function's contract asks for it); an unchanged value is not rendered again. Views are plain HTML hiccup with Tailwind classes, function symbols and data, with no house set of special tags or symbols (owner, 2026-10-24: "I want less hardcoding and special symbols and more easy to compose things together with function symbols or data and normal tailwind css and html"). Stated in: not yet (owed docs/render.md and its task).

### The debug view shows exactly what each turn was sent, styled, with where each part came from

Owner, 2026-10-24: "Yes in terms of content, but I would like it styled and clear to read. Maybe columns and we can show the form and eval result that created it or can explain the rendering?" The content a model call was sent is recorded, so the view reproduces it exactly; it is shown styled, beside the form, eval or render that made each part. Stated in: not yet (owed docs/render.md and THE PLAN step 3).

## Tonight's rulings (2026-10-23, with the 2026-10-21/22 rulings they rest on)

The owner's rulings of 2026-10-23, with the rulings of 2026-10-21 and 22 they rest on, each with his words and where it is now stated.

### Learn from earlier work, then write it clean; remove a worktree once it holds nothing unsaved

Owner, 2026-10-23: "don't forget to clean up the old work trees once they aren't needed and don't throw away good work have agents learn from it if you want a clean implementation. No crappy slow repetitive code." Stated in: AGENTS §9.

### Reuse every unique, immutable result that is proven the same and expensive to make

Owner, 2026-10-23: "I want reuse of all unique and immutable data we are sure is the same and is expensive to render". A pure computation's result is reused wherever its inputs are the same by content: keyed by the digest of exactly what it reads (content ids, the reach walk's digests), shared across branches, sessions and worktrees, and only for units measured as expensive (AGENTS §5). Anything that reads the clock, the network or a model, or launches a process, is not reused; the reach walk classifies it. One mechanism serves every such result, in `src`, `test` or `bin`, and it replaces the per-case caches it covers. Stated in: docs/data-modeling.md#derived-data-is-a-cache-never-relied-on.

### The turn's live state is REPL forms, run fresh each turn: the diff from main and the proofs to pass

Owner, 2026-10-23: "I want agents to write functions in the REPL, they get feedback for what's changed compared to main with clear diffs, the tests they need to pass are clearly listed. This dynamic part of the context needs to follow the same format, all those forms are just run each turn and replaced on the next turn so it's always current." The end of each turn's context is a fixed set of forms the system evaluates for the agent (what its branch changed against main, per definition, as a diff; its task's proofs with red or green and the failing assertion), shown as eval calls and their results in the same shape as the agent's own, and replaced, never accumulated, on the next turn. Examples that write files wait. Stated in: not yet (owed docs/turn.md and seon.turn's message builder).

### Examples are real green sessions, exported to EDN on root's call

Owner, 2026-10-23: "agreed the examples should be real and we should fix them correctly", and on writing them to disk, "maybe have that be your call instead of the seon agents for now". An example is the recorded evals of a session whose fix merged, written to `resources/seon/examples/<task>.edn` through `seon.fx/write-file` only when root calls the export; agents do not write examples. Owner, clarifying: "the ability to persist to disk should be at least not taught to the seon agents directly and we should be reviewing and making sure the solutions are good". No context, cheat-sheet or example teaches an agent to write files (`seon.fx/write-file`, `spit`, shell); an agent's work reaches disk only through its merge, after review (the 2026-10-22 ruling "Agents work at the REPL: no git, no source-file editing"). Stated in: not yet (owed the examples task on ``jolt/acts-by-second-move``).

### The model's context is the standard chat format: a short system prompt, the task as the user message, one eval tool

Owner, 2026-10-23: "we need clear more traditional template style chat based with tool calling official support or we validate our approach is actually correct against a known quantity", and "very clear repl semantics ... everything is a repl command and the output ... a much more compelling story". Measured by root's judge on ten real tasks: the standard format (1.6 KB; one `eval` tool answered by the REPL) gets the same moves as today's (17 KB) at a third less cost and a fifth of the waste, and today's help page, map and found buy nothing measurable. So the standard format is the base, and Seon's data (cited sources, fixtures, proofs' status) goes inside its messages only where a score shows it helps. Stated in: not yet (owed docs/render.md#the-models-context-a-cached-prefix-then-the-turn and seon.template/instructions).

### The design stage looks around and writes the referenced plan

Owner, 2026-10-23: "That's why I have the design task first. Look around and write the referenced plan". A request's first stage is design: its agent reads (the program, the wiki, v1) and its output is the task's plan with resolved references and named proofs, which the walk then pulls for implement. Reading first is the design stage's job, so first moves are judged per stage: design by the plan it writes, implement by acting on the cited vars and proofs. A Claude session doesn't hand-write task plans that a design agent should write. Stated in: not yet (owed docs/tasks.md#a-request-is-one-transaction and the judge's task).

### src resists growth; no line-count target

Owner, 2026-10-23: "don't hyper fixate on LoC. I want some resitance to growth mostly so agents reuse what's there and improve the core parts rather than making one offs". Replaces the "about 10k lines" aim. Stated in: AGENTS §1; docs/plan.md (THE PLAN step 4).

### The context is data, made relevant at its producer; renders do not turn it into prose

Owner, 2026-10-23: "don't just port. We moved away from text output too since the renders kept doing weird shit and it's out of context. Data is better so just improve the data so relveant info is there and you can also calculate additional info from it or rewrite it completely". What a model reads is Clojure data: each value carries what is relevant, derived values computed from it are added as data, and a value that isn't fit is reshaped or rewritten at its producer. v1's renders are a reference, not a template. Owner, same day: "The one value printer is still printing it so render functions need to think about how to properly frame their output so it isn't clipped. The value printer needs to remain general so improve it for the general case not one offs". A render shapes its data so that what matters survives the printer's budget (the important keys first, long parts as their own values); the printer improves only for the general case, never with a special case for one kind of value. Stated in: not yet (owed docs/render.md and seon.ctx/evaluations' task).

### Sessions research on their own; a new session needs root's approval, unless the owner says otherwise

Owner, 2026-10-23: "sessions can do web research with haiku I think on their own and should ask you for approval before launching larger sessions. I want to be able to instruct any agent to override that". Stated in: AGENTS §2.

### The embedding work goes ahead, the model download included

Owner, 2026-10-23: "yeah I approved the embedding work" (in agent-view's thread; relayed by root). Settles the open EMBED question: the embedding model is downloaded and run in-process for `similar`, keyed by each form's content id. Stated in: not yet (owed docs/parity.md and the task seon.ctx/similar-finds-helpers-by-embedding, on its branch).

### No live agent runs until the context has a format agents respond well to

Owner, 2026-10-23: "I think the current agent context isn't great. I'm not sure how helpful it is to run live agent runs until we get a sane format that agents respond well too". Paid end-to-end runs pause. The context's format is rewritten (agent-view's pull-and-walk render) and judged by its first move: real task contexts, one model call each, thinking off, scored blind, until a model acts on turn 1. Live runs resume after that, on a new real task. Stated in: docs/plan.md (THE PLAN step 1); extends "Agents run only on a sound platform" (2026-10-22).

### The reuse design is approved: one memo keyed by what the process runs, for the walk's renders first

Owner, 2026-10-23: "Approved: build the revised reuse design, walk node renders only, with its proofs." The design is the reuse design at 17ed5728 (``jolt/one-content-keyed-derivation``), after the independent attack (the scratch paper reuse/attack-2026-10-23.md (git history)): the key is a full SHA-256 of the boot digest (the build plus the config in force), the digest of everything loaded since boot, and the args' canonical EDN; non-EDN and rebound dynamics are refused; eviction is deterministic. Stated in: not built; the branch was retired on 2026-11-06 ([old work retired](#old-work-retired-not-left-halfway)).

### bin/test branches a kept program db per git tree (option B); each worktree's root is the end state

Owner, 2026-10-23: chose option B of docs/source.md. One durable test store per checkout holds a branch per git tree id (`C^{tree}`, content only); a run branches from the kept tree with the fewest changed files and applies that diff with `seon.source/refresh-tx`, never a whole re-import. Option A (each worktree's own root answers bin/test through seon_prove) stays the end state. Stated in: not yet (owed docs/source.md#tests-run-by-reach and the task).

### Reuse is memoization: `(= (f x) (lookup f x))`, so return it

Owner, 2026-10-23: "It's simpler than you think. (= (fn-a x) (cached-lookup `fn-a x)) they are literally the same so just return it". A pure function applied to the same immutable value gives the same result, so the one mechanism is a lookup keyed by the function (its var and the digest of its code and what it reaches) and the value it is given (its content). On a hit it returns the result, and nothing else changes. Owner, same day: "we shouldn't be caching on a key that needs to be updated. So key selection and however we are implementing this needs to be solid". A key is made only of content: a digest of the function's code and what it reaches, and of the argument's immutable value (a db value by its commit's content, never a branch name, a var name alone, a path or a time). So a key never goes stale and nothing is invalidated: a change makes a new key. Proved both ways: every change to the code or the input changes the key, and no other change does. Stated in: docs/data-modeling.md#derived-data-is-a-cache-never-relied-on.

### Reuse is transparent and simple: a reused value is shown as it is, never marked

Owner, 2026-10-23: "I wanted transparent and simple caching. The whole reuse thing is making me rethink showing it to the agent. Let's just show it as is if it's cached and don't state whether its generated or cached or not." A reused render, context or result reads exactly like a freshly made one: no reused/computed field in an eval's reply, a turn's record or the context. Its effect shows only in the cost proofs and in work-done facts recorded for the owner's views. Owner, same day, corrected: "the model should get cost numbers in the context. I just want them stable across turns in the run so we don't bust the cache. We want to get them used to seeing how expensive things are and so when they get warnings or limits it's easy to understand where they stand". Each eval's cost (ticks, bytes, calls, time) is shown with it, rendered from its recorded fact, so it never changes after the turn it happened in; what moves (the run's totals against its budget) is shown only in the turn's tail, after the cached prefix. Supersedes the hit recording of "Caching is never an excuse for slow code" below. Stated in: (home not found, 2026-11-10).

### Everything is fast; a render or a context whose inputs have not changed is never made again

Owner, 2026-10-23: "In general I want everything to just be fast, but something are just dumb to not cache like web rendering and even ai context if the underlying data/functions haven't changed". The cold path is fast first (below), and some results are reused even when they are fast, because making them again is pure waste: a web render and the model's context are reused while the data they read (content ids) and the functions that make them (reach digests) are unchanged. Stated in: docs/data-modeling.md#derived-data-is-a-cache-never-relied-on.

### Caching is never an excuse for slow code: the cold path is fast on its own, and a hit is visible

Owner, 2026-10-23: "caching isn't an excuse for writing shitty code. If the cold version (can we return whether there was a cache hit in repl evals?) is fuckign slow as shit it's still not okay". A reused result's computation carries its own cost proof, measured cold (no reuse), at the work it needs; reuse is added on top, never instead. Owner, same day: "if it's fast enough we don't need caching then don't use caching." Reuse is added only where the fixed cold path is still measured as expensive; a cold path that is fast enough gets none. (Its hit recording, "a REPL eval's result says which results it reused", is superseded by the entry above: reuse is transparent.) Stated in: (home not found, 2026-11-10).

### Push pinned fork commits without asking

Owner, 2026-10-23: "You don't have to ask me to push public repos anymore." A session pushes the fork commits its branch pins to ``seantempesta/<lib>`` on a ``jolt/<topic>`` branch: no force, never a fork's main. Stated in: AGENTS §8; docs/forks.md.

### Fork URLs are the GitHub forks

Owner, 2026-10-23: quote not recorded; relayed by root. `.gitmodules` names the forks at `seantempesta/*` over https, not a local path or the module store. Stated in: docs/forks.md (owed its rewrite when the URLs change).

### The project root is `~/src/seon-next`; worktrees live inside it at `.worktrees/NAME`; nothing refers to the old prototype folder

Owner, 2026-10-23: quote not recorded; relayed by root. The root is a fresh clone with its history kept (the outer repo's history is the tag `history/prototype`), and `next` stops reaching outside itself: the plan is the archived paper self-contained-plan-2026-10-23.md (git history). Stated in: docs/machine.md (what lives in the root and beside it), docs/merging.md#a-worktree-session-works-in-a-checkout-of-its-own, and `bin/worktree --path NAME`.

### Cost is work done: ticks, bytes and calls, never wall time

Owner, 2026-10-23: quote not recorded; relayed by root. Stated in: docs/cost.md (ticks, bytes and calls are exact; wall time is not a measure).

### A session's own prove runs the process and slow tests its change reaches (owner, 2026-11-08)

"Yes, run them early", to: should a plain `bin/prove` run the process tests its change reaches, so landing stops rerunning them. A bare `bin/prove` leaves nothing out, as `--merge` does, the slow tests last; a bare run and a `--merge` run of one tree share one green marker, so `bin/land` answers at once from a session's own green prove. An agent's turn still leaves process tests to its gate. Stated in: bin/prove; docs/merging.md#a-slow-test-runs-last.

### Process tests are not automatic: the reach walk classifies them

Owner, 2026-10-23: quote not recorded; relayed by root. A test that launches a binary is deferred by what the reach walk finds, not run on every change. Stated in: docs/turn.md#who-runs-which-tests; docs/merging.md#a-merge-runs-what-the-change-reaches.
Superseded (2026-10-27): by the day's ruling "a merge runs the process tests its change reaches, automatically" (see the 2026-10-27 day rulings above); the gate now defaults to the root's source root.

### Ids are derived from content; addressing is global

Owner, 2026-10-23: quote not recorded; relayed by root ("content-derived ids; global addressing"). Stated in: not yet (owed docs/storage.md, with the ``jolt/global-ids`` branch).

### Tests run on change, through Seon

Owner, 2026-10-23: "I want the testing to happen on demand. When a change happens. When data flows through the system." Root's file watcher sees a change and runs the tests the change reaches at the new digest, reusing greens and recording results as facts (``jolt/health``). Until it lands, `bin/prove` once per finished step, never after each edit, no full runs without root's go ("I want less useless runs. Agents are slowing everything down doing full checks after every little change."). Stated in: AGENTS §3 (Testing); docs/merging.md#a-merge-runs-what-the-change-reaches.

### Renders are `(opts & children)` functions, Reagent's form, with auto-render

Owner, 2026-10-23 (value first, relayed by root), restated 2026-10-26: "I was thinking react so symbol opts children would be my guess?". Every render call is `[f opts & children]`; a value in the tree with no explicit render is drawn by its schema's render, else the printer. Stated in: docs/render.md (with jolt/render-call-shape).

### Thinking is off by default

Owner, 2026-10-21, restated 2026-10-23: "if we can't have intuitive function calling based on the context our context is bad and instructions need help". Stated in: docs/model.md#thinking-is-off-by-default; `:seon.config.ai/thinking` in `resources/seon/config/default.edn`.

### No automatic retries: a refused or failed run is held for root

Owner, 2026-10-23: "no automatic retries, send it to root to decide if it should be retried" (and 2026-10-22: "We need a way to mark the task as failed and root should be notified"). Stated in: docs/tasks.md#a-refused-run-is-held-for-root.

### The standing store is test data: erase it, no migration; history within a store is kept

Owner, 2026-10-23: "why are you migrating 6 facts? isn't it just test data just nuke it and move on". Stated in: AGENTS §9 (dropping a store: the standing store alone is erased); docs/machine.md#disk-stays-bounded; docs/storage.md#history (history kept).

### Agents share greens: a test green at its digest never runs again

Owner, 2026-10-23: "tests that are the same (especially when freshly branched!) should not run again if they are already green". Stated in: AGENTS §5 (results reused by input digest); docs/merging.md#a-merge-runs-what-the-change-reaches (agents' greens are shared once ``jolt/brief3`` merges).

### Output quality is decided by blind scoring on real data

Owner, 2026-10-23: "Those decisions should override any of my decisions. This is up to the agent really". Stated in: docs/render.md#one-printer (the scored result goes there; not yet).

### The context speaks Clojure, with the agent's thinking beside its forms

Owner, 2026-10-23: "We should explain the task in terms of clojure code executing and thinking comments and encourage the agent to do the same." Stated in: no section yet; owed to docs/render.md#the-models-context-a-cached-prefix-then-the-turn and docs/agent.md#your-turn.

### waste stays (`:seon.run/waste`); stuck went with the budget

Owner, 2026-10-23: quote not recorded; from root's note "Owner answers, 2026-10-23 morning" (archive) and the triage's C5. Stated in: AGENTS §7a ("Waste stays our word"; the stuck entry is gone).

### The dashboard stays in `src`

Owner, 2026-10-23: quote not recorded; from root's note "Owner answers, 2026-10-23 morning" (archive). Stated in: no section yet.

### Worktree sessions are truly independent

Owner, 2026-10-22: "Each lane should be able to do anything they want to the codebase, fully test it and then we can shut it down and merge it. So true independent work". Stated in: AGENTS §3; docs/merging.md#a-worktree-session-works-in-a-checkout-of-its-own.

### Claude sessions use worktrees until Seon agents work inside the system; the end is Seon agents and git only for write-back

Owner, 2026-10-23: "I want worktrees for claude agents until we can get them working in seon itself. the end goal is seon agents inside the system and not having to fuck with git except to commit changes that we write back". Keeps the 2026-10-21 "Lanes become Seon agents" (archive) as the destination and "Worktree sessions are truly independent" as the way of working until then. Stated in: AGENTS §1, §3; docs/source.md (write-back); THE PLAN step E in docs/plan.md.

### Long runs only when the work is done; otherwise run only what a change reaches

Owner, 2026-10-22: "I want faster development. Don't run long tests unless we are pretty sure it's done"; also "why don't we have a comprehensive policy where we are only running tests that have changed?" and 2026-10-21: "I want faster iteration not a matrix with every run permutation." Stated in: AGENTS §3 (Testing); docs/merging.md#a-merge-runs-what-the-change-reaches; docs/source.md#tests-run-by-reach.

## 2026-10-22

The owner's rulings of 2026-10-22, each with his words and where it is now stated.

### Root runs autonomously while there is good work

Owner, 2026-10-22: "I am authorizing you to run autonomously as long as you have good work to work on". Stated in: AGENTS §2.

### One value printer, nothing cut before it; important data has declared `:ai` and `:html` renders

Owner, 2026-10-22: "Do not create more than one value printer and no clipping happens before then". Stated in: docs/render.md#one-printer; docs/render.md#no-clipping.

### A request's whole linked task set is one transaction on main, before any agent forks

Owner, 2026-10-22: "it's just one transaction with several maps". Stated in: docs/tasks.md#a-request-is-one-transaction.

### Names are Clojure's, its libraries' or Jolt's

Owner, 2026-10-22: "stop using names that aren't clojure or clojure library or jolt or whatever related". Stated in: AGENTS §7a; docs/vocabulary.md. Amended 2026-10-23: waste stays, stuck goes, and stored names change by erasing the store, not by migration.

### Compiled units are not stored now; the idea stays open

Owner, 2026-10-22: "keep the idea open I just want to get to the point we are productive and have real seon agents fixing problems in the system". Stated in: docs/storage.md#compiled-code.

### A task edited on two live branches is a conflict for root (D4)

Owner, 2026-10-22: "This should never happen. If it does there should be a merge conflict and root will have to figure it out." Stated in: docs/tasks.md#task-content-reaches-main-at-the-runs-end.

### A task's own checks live with the task, outside main's test namespaces (D5 (a))

Owner, 2026-10-22: quote not recorded (the owner chose option (a)). Stated in: no section yet.

### Paid model runs: up to $50, productive runs only; known-wrong context is fixed first

Owner, 2026-10-22: "if the context is dog shit and we know it's fucked do not waste money or time just fix the shit we know is wrong first". Stated in: AGENTS §8; docs/machine.md#secrets-and-paid-runs; docs/model.md#paid-runs.

### One store holds the whole system; branches are experiments at every layer

Owner, 2026-10-22: quote not recorded. Stated in: AGENTS §4 (a value), §5 (one store, many branches); docs/storage.md.

### Our Datahike is Jolt-first, and storage is tuned for our workload

Owner, 2026-10-22: "one really good datahike with jvm specific features only in a jvm branch and jolt specific features as the main version going forward". Stated in: docs/forks.md; docs/storage.md.

### Agents work at the REPL through tool calls: no git, no source-file editing

Owner, 2026-10-22: "Agents shouldn't be editing their own source code files directly. The point is to use the REPL and to not do file editing". Stated in: docs/source.md#an-agent-changes-code-at-its-repl; docs/agent.md#the-repl.

### Tasks are unrestricted; behaviour follows the data; a task's branch, model and config are derived along `:needs`

Owner, 2026-10-22: "tasks shouldn't be restricted in what they can do". Stated in: AGENTS §4 (a task), docs/tasks.md. Proof protection is deferred.

### A request becomes design, implement, merge; templates are held tasks in the task EDN; one proofs field; every agent gets `(help)` and its task

Owner, 2026-10-22: "No not a wiki. This is internal seon." Stated in: docs/tasks.md#a-request-is-one-transaction; docs/references.md#task-references; docs/agent.md.

### Tasks are extensible, with no explicit kinds (`:seon.task/kind` goes)

Owner, 2026-10-22: quote not recorded. Stated in: no section yet (every task file still carries `:kind`).

### One agent entity: the run folds into `:seon.agent/*`

Owner, 2026-10-22: quote not recorded. Stated in: no section yet.

### RELOAD goes: `require` and `load-file` load as in Clojure and record nothing

Owner, 2026-10-22: quote not recorded. Stated in: docs/source.md#an-agent-changes-code-at-its-repl.

### Agents run only on a sound platform

Owner, 2026-10-22: "Don't run agents if we know that we have serious problems with our context or the task instructions or its functionality with auto running the tests." Stated in: AGENTS §8 (paid runs need a checked context).

## 2026-10-21

The owner's rulings of 2026-10-21, each with his words and where it is now stated.

### No locks, no cap on worktree sessions, no waiting

Owner, 2026-10-21: "get rid of all of it". Stated in: docs/merging.md#main-goes-first (no lock).

### Every live run is better than the last; new real tasks, no rerun matrix; DeepSeek works every stage, measured

Owner, 2026-10-21: "we are NOT doing a matrix on the same run". Stated in: AGENTS §1 (turns to green, tasks merged).

### The BUILD, SCHEMAS and PKG plans are approved; direct linking stays

Owner, 2026-10-21: "EDN for offline compiled into program and register at the repl and that can write to the edn if it's merged to main." Stated in: docs/source.md#a-merge-loads-in-place; docs/source.md#schemas-at-the-repl; docs/source.md#dependencies; docs/source.md#shared-build-caches.

### Minimal code: extend the existing systems

Owner, 2026-10-21: "If we are building shit we don't need to build and not using the existing systems or extending them for minimal code we are doing the wrong thing." Stated in: AGENTS §3 (Land small and often); docs/merging.md#every-merges-src-diff-stat-is-read.

### Disk stays bounded; regenerable output is deleted

Owner, 2026-10-21: "just cleanup shit we aren't using anymore and build in rules so this doesn't get out of control". Stated in: docs/machine.md#disk-stays-bounded.

### Files, namespaces and symbols are plain Markdown, read by one reader; only Seon's own docs are stored

Owner, 2026-10-21: "This should be just like the markdown syntax and we should just understand what are files and what are namespaces and symbols." Stated in: docs/references.md#files-and-namespaces-on-demand; docs/references.md#the-wiki-in-the-store.

### Line citations keep v1's shape (a `path:N` is a file reference marked stale-prone)

Owner, 2026-10-21: quote not recorded. Stated in: docs/references.md#the-delimiter-picks-the-depth.

### `bin/root` restarts the next generation; a process does not own its replacement

Owner, 2026-10-21: quote not recorded (the owner chose option a). Stated in: docs/source.md#the-next-generation.

### How we work: process, not whim; research, then a simple plan; learn into the wiki

Owner, 2026-10-21: "Make it so we don't always start from zero and keep redoing the same mistakes." Stated in: AGENTS §3, §7.

### The goal is the recursive self-improvement loop, toward thousands of agents accreting good changes

Owner, 2026-10-21: "The recursive code self improvement loop is the goal." (2026-10-20: "I want thousands of running agents doing different tasks and focusing on each task.") Stated in: AGENTS §1.

### Loading code is an effect, typed at the edge

Owner, 2026-10-21: "Make anything like this an effect and we treat it as strongly typed at the edges." Stated in: AGENTS §5 (effects go through their owner), docs/flow.md#effects-one-owner-and-its-workers-per-kind.

### Every definition happens once, where it is defined

Owner, 2026-10-21: "Why are we redefining things?" Stated in: AGENTS §5; docs/resolution.md.

### Programs are known from the compiler, not from text

Owner, 2026-10-21: "I don't want regex's I want to know exactly what something is so there are no hacks." Stated in: AGENTS §9; docs/source.md.

### Runs are kept, history and all; until there is real user data the standing store is test data (above)

Owner, 2026-10-21: "keep it so we have a perfect record frozen in time." Stated in: AGENTS §9; docs/storage.md#history.

### Agents stay forked processes, each named by role, task and checkout

Owner, 2026-10-21: "But fucking name the processes if they really have to be different." Stated in: AGENTS §4 (a process), docs/processes.md#the-agent-process.

### Work is admitted by its measured workload, through flows

Owner, 2026-10-21: "I don't want slots I want smarter systems where we understand the workload and they are flow based." Stated in: AGENTS §4.

### Embeddings join the context: fused, small and measured

Owner, 2026-10-21: quote not recorded; 2026-10-23: "We can build bindings to some fast C library for running it ourselves right? maybe as a flow so it's just processing requests". 2026-10-24: "keep embeddings in the context; bring a measured version back in 4d", then "If it's not working well for now leave them out but we haven't fully explored embeddings to drop them": a small embedding-found list joins the task story only while measurement shows it helps; the embedding machinery (seon.vector, its search and config) stays for exploration either way. Stated in: docs/vector.md.

### Patterns live in the topic sections, linked

Owner, 2026-10-21: "I want us to learn how we can detect patterns in our code and reuse these solutions". Stated in: docs/README.md#how-the-wiki-is-kept.

### If nothing has changed, we do nothing; no clock or timer decides (three exceptions)

Owner, 2026-10-21: "yeah no clocks or timers." Stated in: AGENTS §4; docs/clocks.md.

### A failure goes to whoever can fix it

Owner, 2026-10-21: "An fatal error needs to be addressed a runtime fix that can be applied needs the error to apply the fix." Stated in: AGENTS §4 (an error as data); docs/errors.md, docs/failure-points.md.

### Only documented config

Owner, 2026-10-21: "the only things that should stay are documented config schemas that we are porting from seon if they are relevant." Stated in: AGENTS §5; docs/config.md#the-file.

### Branches as in production; a separate store is a last resort

Owner, 2026-10-21: "separate stores should be a last ditch effort". Stated in: AGENTS §5.

### A merge carries the program and the agents' data, never platform records

Root, 2026-10-21 (reversible): no owner quote. Stated in: docs/tasks.md#task-content-reaches-main-at-the-runs-end; docs/config.md#facts-on-main.

### Durability is continuous group commit

Owner, 2026-10-21: "Why have an arbitrary 10ms collection point?" Stated in: docs/storage.md#durability-is-continuous-group-commit, AGENTS §5.

## 2026-10-20

The owner's rulings of 2026-10-20, each with his words and where it is now stated.

### Estimates are proofs on tasks

Owner, 2026-10-20: "How long should something really take." Stated in: AGENTS §5; docs/cost.md.

### Scheduling is reconciliation over task attributes; no chain entity, no separate scheduler

Owner, 2026-10-20: quote not recorded. Stated in: AGENTS §4 (a task), docs/tasks.md.

### Effects go over channels to their owner procs; whoever asked records the outcome

Owner, 2026-10-20: "IO and effects in general I think we may want to send over channels to dedicated processing flows." Stated in: AGENTS §5.

### Flow is the one model: every component is a step-fn proc, declared in one file, ordered by dataflow

Owner, 2026-10-20: "No point in using flow if we are going to use it the wrong way." Stated in: AGENTS §4, §5; docs/flow.md.

### Data-centric, always valid: contracts on every function

Owner, 2026-10-20: "make this the universal thing that we hone and fix." Stated in: AGENTS §5, docs/checks.md#contracts-armed-at-definition.

### Each agent writes its own branch, in its own process; root writes only main

Owner, 2026-10-20: "Agents just take care of their own shit if they are in different branches". Stated in: AGENTS §4 (a value); docs/storage.md#who-writes-a-branch.

### It's a REPL into a real session; branches are true branches, merged per definition, never replayed

Owner, 2026-10-20: "We should be able to create new namespaces period. Just like a REPL." Stated in: docs/agent.md#the-repl; docs/source.md.

### Get out of the way: fixes at the producer, no guards; failure points are named

Owner, 2026-10-20: "I want thoughtful fixes not fences and guards." Stated in: AGENTS §4, §5 (No guards), §9; docs/failure-points.md.

### The transition goal: `` replaces Seon, consistent, contracted, about 10k lines

Owner, 2026-10-20: "have everything be spec'ed and have only good tests using our test harness". Stated in: AGENTS §0, §1.

### Everything on Jolt; libraries fixed properly, measured, with no extra caches

Owner, 2026-10-20: "if it's faster to not use the cache why cache at all?" Stated in: docs/forks.md.

### Renders attach to schemas; the UI and the context are renders, ported not reinvented

Owner, 2026-10-20: "Everything has a schema right so we say this renders this." Stated in: docs/render.md.

### Agent context comes from the plan's references

Owner, 2026-10-20: "If we need to give agents more context we should do so in the plan". Stated in: docs/references.md#task-references.

### One messaging system; the user is an agent

Owner, 2026-10-20: "one simple messaging system that works everywhere." Stated in: no section yet.

### Outside agents reach any agent or root through a thin MCP server

Owner, 2026-10-20: "outside agents need minimal MCP wrapping so they can repl in and run statements against any agent or as root." Stated in: AGENTS §6; docs/parity.md#the-mcp-server.

## Open: does this still stand?

Early rulings that later work may have outdated, left for the owner to confirm or drop.

### Each agent is a proc in its own Flow graph, or agents are procs of the one root graph

Owner, 2026-10-20: "the agent is its own process so having the flow controls be directly hooked up seems like it's ideal." Per-agent graphs, the leaning and root's "Assembly choices" (archive) were left to measurement; which stands?

## Open owner questions (pending rulings; the plan's blockers are also in plan.md)

The questions waiting on the owner's ruling, the plan's blockers among them.

- D6 names for the merge model: `:seon.task/carry`, `:seon.task/source`, `:seon.task/target`, `:seon.merge/kept`, `:seon.tx/changed`.
- D7 task config: keep the config keys as top-level task attributes (today `seon.task/create-tx` merges `:seon.task/config` into them and `d/pull` of the key throws) or a real `:seon.task/config` attribute.
- What a merge carries and who wins a conflict: the whole branch with main winning (`:seon.merge/kept`, v1 MERGE-STEP 5a) or an explicit `:seon.task/carry` list; parity.md still says "ours is kept".
- Changed-since: a Datahike tree diff by node address (port v1 fork `771a0c4b`, prototype first) or a `:seon.tx/changed` stamp against the "no stamps" ruling.
- Proofs along `:needs`: keep `seon.task/accumulate` (2026-10-20 ruling) or never carry proofs, since an accumulated designed-red check blocks implement (DESIGN-tasks §3.4, §11 #14); possibly moot once templates become held task data.
- No progress: is a byte-identical repeated reply a recorded fact only, or does it end the run.
- What is a stall once an eval is bounded by its work: one task (`seon.root/a-stall-is-past-the-requests-own-bound`; the other two of the three are done and removed) still plans a clock; `:seon.config.agent/stall-ms` (30000, a config key now) remains.
- The per-run turn cap: lower `:seon.config.agent/max-turns` (40) to about 2x the measured p90 turns-to-green once 20 real tasks give one.
- A dropped model connection (`:seon.model/transport`): retry once after a wait, or end as now.
- File and shell roots: a config key with a permissive default, or none; and whether `:git` `:build` `:deps` `:source` effects are root-only.
- A `jolt/<topic>` fork of `jolt-lang/ring-chez-adapter` (needed by the `seon.web` listener-as-fiber task).
- Upstream: when to push the `upstream/*` Jolt branches and open PRs, and whether to send the Chez 0006 report and a 0007 issue.
- Profiling tooling: which to build (Jolt symbol map per generation for `sample`/xctrace; per-op LMDB counts; a `bin/check profile` mode; `mdb_stat` from `bin/build lmdb`).
- Root's external channels (`:to-writer`, `:back`, `:ext`, `:events`, passed as `:bind`) as declared Flow `:conns`?
- The render proc's render-once caches (`:event-rows`, the zoom history's `:rows`) as units with a `:container`?
- Do `seon.cost` and `seon.profile` belong in the release binary or with the drivers?
