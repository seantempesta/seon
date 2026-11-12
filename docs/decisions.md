# Decisions

The owner's standing rulings, one entry each: his words and date, and where the rule is stated now. The mechanism lives in `AGENTS.md` and the wiki; the reasoning and the history around each ruling are in git. A ruling a later one reverses keeps a one-line pointer to it.

## 2026-11-12 rulings

The owner's rulings of this day, each with his words and where it is now stated.

### One unit: everything stateful that runs is a Flow agent

"If it's stateful and it's like a running process that we need to keep track of it should be a separate flow properly wired up to the system for communication, repl for debugging, errors, topology, etc." And: "It's the docker rule. If you make everything the same and make that unit (the container for docker, the flow agent for us) fast and finely tuned and extensible we have a building block we can standardize on." A test child and a clone are agents: "Tests should have the agent env too." A difference is an option of the one unit, never a second path. The only processes that are not agents are the fork server, which makes them, and exec'd programs, which are effects through their owner. Stated in [flow](flow.md#the-step-fn-contract-every-proc-follows) and [processes](processes.md#the-agent-process).

### Reasoning is on at low effort for work tasks

Asked whether reasoning `:low` should be the default for work tasks: "Sure". It replaces thinking off (2026-10-21). Stated in [model](model.md#thinking-is-on-at-low-effort).

### Dead history-on-scroll page code is deleted

Asked whether to delete `seon.render.ui/more`, the web's /more route and the `:more` ops, which no page used: "Yes". A list holds a window and says how many it left out ([ctx](ctx.md#the-pattern)).

### Design to the lower bound

"I want to make sure all agents have shared rules going forward and are being methodical and questioning how long things should take if they were optimal in a math sense. We are never to design systems that are so inefficient again." Stated in [AGENTS.md §3a](../AGENTS.md) and [cost](cost.md#what-things-cost-here).

### AGENTS.md is not an append log

"I do not want a long append log of instructions that won't be evergreen." Stated in [sessions](sessions.md#the-way-improves).

## 2026-11-11 rulings

The owner's rulings of this day, each with his words and where it is now stated.

### The system goal: the codebase improves itself, by refinement

"The goal isn't radical change, but refinement over time." His picks: the first loop closes at the end of a task; a system change is approved by a review task; a review files tasks and never edits what it reviews. Stated in [the plan](plan.md#the-plan).

### One agent mechanism for every role, root included

"So there is the same system everywhere don't start building different systems for different agents." Stated in [the plan](plan.md#the-plan).

### A layout is a render over the same pulled data

"I don't want to lose our data driven UI rendering and don't want any hand rolled systems. This a design goal." Stated in [the plan](plan.md#the-plan), part 3; not built.

### An agent reads what it can read; a question goes to its parent

"we need to stop contacting root for things the agent can access on their own." Questions go to the parent in the hierarchy. Stated in [users](users.md#what-each-thing-is).

### Design the data for the pull

"Remember we control the data model and the design and we should be designing it for the pull". Stated in [data modeling](data-modeling.md#before-you-add-something).

### An agent may hold a set of tasks

"These fork and improve sessions should reuse the same task system." "maybe we need to differentiate between start now and start when the system is free." Stated in [the plan](plan.md#the-plan), parts 1 and 2; not built.

### Tasks chain by needs

"make sure we aren't hard coding in "example" or whatever when they get turned into openai-compat formats." Stated in [the plan](plan.md#the-plan), part 2, and [tasks](tasks.md#a-task-names-examples).

### The data model is worked through live

"Is a session just an agent if so then we don't need session-open/example-open." "What's the next step that will strain our data model". Stated in [the plan](plan.md#the-plan), part 1.

### A message's turn is not asked

"In general I think the message shouldn't matter what turn it arrived on". Stated in [the plan](plan.md#the-plan), part 1.

### Ids are unique across branches; order by time, not by transaction id (owner, 2026-11-11)

"I want all IDs to be unique among all the branches and for us to use time for ordering not TX-ids". He withdrew unique across stores, and a transaction's time is not made its id. Order by `[:db/txInstant, tx id]`. Stated in [storage](storage.md#history) and [data modeling](data-modeling.md#a-ref-or-a-value).

### A new session starts at the commit at or before a point; a read is exact at any transaction (owner, 2026-11-11)

"Okay tell Point that works and we can build on this". A read at a branch and transaction is exact; a new session forks the commit at or before it, derived, never stored. Stated in [turn](turn.md#an-agent-goes-back-to-an-eval).

### Root is the agent env at the top

"I want to stop calling anything that the system is doing without an active env as "root"." Root is the agent env at the top of the hierarchy. Stated in [vocabulary](vocabulary.md#words-we-do-not-use).

### A sync merges everything that does not conflict

"I don't want a hand made list of attributes to sync. I want all non conflicting data to merge and conflicts to be fixed before we merge anything else." Stated in [the plan](plan.md#the-plan), part 1; not built.

### An unread attribute gets a render or goes

"(if there's no purpose remove them)". Stated in [data modeling](data-modeling.md#before-you-add-something).

### Messages are ordered by when they arrived, from the pull

"Land it". Stated in [ctx](ctx.md#the-pattern).

### Root's names change with the erase

"Later, with the erase". `bin/root`, `seon.root`, `my.root` and `:seon.root/*` are renamed together at the erase.

### A merge replays the source's transactions

"We should never record two transaction times that are the same". Each source transaction becomes a new, later transaction of the receiving branch. Stated in [the plan](plan.md#the-plan), part 1; not built.

### No derived value is stored

"If we are storing derived values stop that." "I'm talking about the 'all good' being run on the db state". A measured observation is a fact under an identity that names its observer. Stated in [data modeling](data-modeling.md#derived-data-is-a-cache-never-relied-on).

### Work, then refine, then review

"And then we can test those refinements without losing the previous one because we have db history." Stated in [the plan](plan.md#the-plan), part 2; not built.

### Reviewed sessions are kept in the repository

"Make it a general import area like tasks and it's in the repo". Stated in [the plan](plan.md#the-plan), part 2; not built.

### Measures accumulate from use; a measurement is one call

"I wanted results to accumulate and become more precise over time naturally as they are exercised in the system." Stated in [cost](cost.md#how-we-measure) and [the plan](plan.md#the-plan), part 6.

### Before a compaction a session reports up

"all agents before they compact should reflect on the work they did ... and report to their parent". "Over time I want to stop drift." Stated in AGENTS.md §2 and [sessions](sessions.md#the-way-improves).

### Live agent runs harvest problems

"Not teach to the test, recognize systemic failure and adapt over time". "If after our claude agent reviews the work it's good additions I want those synced to disk." Stated in [sessions](sessions.md#live-agent-runs-find-the-systems-problems).

### Embeddings stay in the store

"Oh it's more robust than I thought ignore my previous request I think this is fine?" Stated in [vector](vector.md#a-vector-is-its-own-entity).

### Check-step bounds are signals, not hard constraints

"The step bounds are signals not hard constraints." Stated in [merging](merging.md#a-merge-runs-what-the-change-reaches). For a test or step ENDED past its bound, overturned on 2026-11-12: "A test killed for running too long is a bug and it should be recorded as an error and fixed. It shouldn't take down the system and we shoudln't do nothing and not record anything." Stated in [clocks](clocks.md#time-decides-the-owners-exceptions). A bound still never decides when an item starts or waits ([config](config.md)).

### `bin/root` becomes `bin/system`

"Rename to bin/system". Stated in [vocabulary](vocabulary.md).

### When lines of work collide: proving, the product loop, renders as data, Jolt fiber safety (owner, 2026-11-11)

"B, A, D, C": proving through the system, the product loop, renders as data, Jolt fiber safety. Stated in [the plan](plan.md#now).

### A stuck agent is stopped and analyzed

"Stop it and analyze and fix. Repeating is a problem with our context and instructions." To be stated in [agent](agent.md).

### The system message is only for what every agent is told

"The system context is literally only for universal explanations that every agent in the system should get." "Any begging or pleading ripped out." To be stated in [model](model.md).

### The Data model session is in charge of schemas and pull patterns

"I want the solutions to be based on data so make sure the data model agent is consulted." Stated in [sessions](sessions.md#sessions-own-their-areas).

### The page reaches the model as evals, at commit points

"I want the @ctx and the hiccup displayed in full at some point in the agents evals". To be stated in [ctx](ctx.md).

### The model reads data, and the render's output as hiccup data

"I want data and not rendered html shown the model"; "(the model of course gets the stripped out CSS and the hiccup as data version)". To be stated in [render](render.md).

### The website renders the hiccup by default

"Can we by default render the hiccup?" To be stated in [render](render.md).

### A diff is an editscript, and a delete is simple

"I want editscript diffs and I think simpler deletes since the agent already has the data in their previous context". To be stated in [render](render.md).

### A pull pattern is stored with the agent

"the pull pattern should be stored with the agent and the agent should be rendering it so it knows about it". To be stated in [ctx](ctx.md#the-pattern).

### A render may read the previous point's tree

"The previous point's tree". To be stated in [render](render.md#what-a-render-is-handed), which says "never" today.

### A render's output has rules, but its content is not constrained

"The page hiccup can have rules I suppose, but we don't want to constrain the content". To be stated in [render](render.md).

### A text field is Markdown

"In general if it's a text field it's assumed to be markdown." To be stated in [render](render.md).

### Break the agent's physics and the agent breaks; keep auditing

"We need to keep auditing and finding our failures and fixing them". To be stated in [agent](agent.md).

### Reasoning is a development microscope

"The goal is to maybe understand via the reasoning what the model is finding confusing and then we fix it and demo it on no reasoning". To be stated in [model](model.md).

### Disregard an LLM's estimates of time

"Disregard all LLM estimates they are always based on human output and we'll reach it much sooner." Stated in [sessions](sessions.md#the-managing-session).

### Fibers take the safe paths; a guard is a finding

Of 11 ticks an element instead of 8: "Pay it". "You can use this to find the issue, but i want the classes eliminated so the guard is unnecessary." Of the tip's +18% bytes: "sure". Stated in [forks](forks.md#jolt).

### Runs are throttled by load

"I want load based runs that we are throttling via the flow channels". To be stated in [machine](machine.md) and [config](config.md).

### Health checks watch the queues and write to health/problems

"let's schedule adding good checks in when we deploy it and we have it write to health/problems." To be stated in [health](health.md).

### The standing system and its store are for use; erasing it stays his

"What? the database is read only? Why? No. Just use it!" Stated in AGENTS.md §0 and [sessions](sessions.md#sessions-own-their-areas).

### The test suite is for the online version, in the db, and the workflow is the agents' (owner, 2026-11-11)

"we are designing this for seon agents or claude agents using the mcp tools". Not yet stated in the wiki.

### Worktrees stay for real work

"Yeah keep pushing a session through and use it to mine problems. Don't slow down real work though". Not yet stated in the wiki.

### Seon's own calls carry an intent and a good model message

"Seon's own calls should carry an intent too." The tool is named "repl". Stated in [render](render.md) and [model](model.md#one-tool-repl).

### Seon's calls are stored evals, and deterministic

"if they are evals of course we store the results just like a normal eval"; "I just want it to be deterministic and to work". Stated in [ctx](ctx.md); the calls as stored evals are not built.

### A render shows that context exists and how to pull more

"Agents need to know that context exists and we need explanations and short instructions that the agent can use to pull in more context." Not yet stated in the wiki.

### An agent's entity holds what is present; a render derives the rest

"we need to have the data model agent do an audit for data that needs to be present vs derived in a render function". Not yet stated in the wiki.

### The first point shows `describe` of the context

"How about that we teach them a general skill and learn how to fish on any other value." The function is `describe`. Not yet stated in the wiki.

### A tool's answer is the plain value

"oh I like yours better already". Not yet stated in the wiki.

### An agent works in its task's namespace

"I think we need to be clearer that we are setting them up in that namespace because we are auto pulling in context from that namespace." Not yet stated in the wiki.

### Every state converts to a completed task

"Every state should be convertable to a completed task." Stated in part in [tasks](tasks.md#a-refine-curates-its-own-task).

### Proving is one call on a reference: fork main, load that point's program whole, run its proofs (owner, 2026-11-11)

"Fork main and load all the code in the system in the right order in one eval and you have something close to the full build without building it from scratch." "Build both so we have both." Stated in [the plan](plan.md), part 5; not built.

### The whole load while the owner is away

"I'm not using my machine so target the full load of all cores and when the test system lands you can update the config to run on load". The key is `:seon.config.proofs/max-load`.

### Spec more work than fits, and reshape what does not fit

"Spec out more work than that and keep refining the work with your agents don't just accept it if it's a square peg and a round hole". Stated in [sessions](sessions.md#the-managing-session).

## 2026-11-10 rulings

The owner's rulings of this day, each with his words and where it is now stated.

### The store may lose its last commits, never corrupt or exhaust the machine

"Losing data is fine, but semaphore exhaustion and corrupt storage is not okay." Fixed in our LMDB fork. Stated in [failure points](failure-points.md#lmdb-locks), [failure points](failure-points.md#lmdb-crash-safety), [storage](storage.md#durability-is-continuous-group-commit) and [forks](forks.md#lmdb).

### A run far past its measured time is ended, after its evidence (owner, 2026-11-10)

The fourth clock exception: a script may end a run far past its measured time, after capturing its evidence, reported red with a typed reason and never retried. Stated in [clocks](clocks.md#time-decides-the-owners-exceptions) (`:seon.config.check/step-end-factor`, STEP-OVERRAN, `bin/evidence`).

### A write to main is answered after the store holds it (owner, 2026-11-10)

He chose answering after the store commit over answering at apply: answered means every process can read it. Stated in [storage](storage.md#durability-is-continuous-group-commit).

### The best known way, improved

"Pain points can become well polished and intentional features." Stated in AGENTS.md §1 and [sessions](sessions.md#the-way-improves).

### Small changes land in batches, proved once

"I don't want 100% separated work separately running all the tests for each little change." Stated in AGENTS.md §3 and [merging](merging.md#the-managing-session-lands-a-batch).

### A task a Seon agent finished reaches main by its owner's review

"if it's almost done right just fix it and sync it to disk and remove the task." Stated in [source](source.md#a-merged-task-reaches-mains-files).

### A root catches up with its checkout at start

"don't let this decision cause even more chaos." Stated in [source](source.md#a-boot-catches-up-with-its-checkout).

### Pages are built from data by one walk (owner, 2026-11-10)

"No more hand drawn lists and custom shit." "Root, the user, and normal agents should be different based on their data and their connections, not separate special code." "don't flatten, the location in the pull pattern has meaning". With it, root's agent is made at boot and an example session reaches the model as real messages. Stated in [render](render.md).

### The forks follow upstream: theirs when almost as good, ours written to be offered (owner, 2026-11-10)

"If there are two solutions and theirs is almost as good as ours choose theirs". Stated in [forks](forks.md#following-upstream).

### Every session proves its branch before handing it over

"They are to ideally make the entire suite run very fast so every agent can fully check their work." The command is `bin/prove`. Stated in AGENTS.md §3.

### A test that starts a root takes what root runs (owner, 2026-11-10)

He reversed 2026-10-27's "a root's boot is no entry": a process test's digest takes what root runs. Stated in [source](source.md#tests-run-by-reach).

### The test system runs inside Seon; the outside is thin wrappers (owner, 2026-11-10)

"I want the test system focused on seon agents running it from the inside." A digest takes the code of the forms a test reaches, without docstrings. Stated in [source](source.md#what-a-test-takes-of-a-forms-text) and [merging](merging.md#a-merge-runs-what-the-change-reaches).

### The test service runs what the cores hold

"I don't want 'shards' anymore." "Never do timers if we can setup blocking channels." Stated in [merging](merging.md#the-test-service).

### A change that moves every verdict is run whole once

"The next time a change invalidates all the tests please just run it once so the other runs don't duplicate all the tests." Stated in [sessions](sessions.md#the-managing-session).

### No agent on the costliest model without the owner's yes

"Do not launch any fable agents without my approval. I literally can't afford it." Stated in [sessions](sessions.md#no-fan-out).

### A test earns its place or is cut

"Cut all bullshit tests." Stated in [testing](testing.md#a-proof-is-focused).

### Load is judged by cores and free memory

"I have 18 cores and load is 8". Heavy runs go niced and in parallel while nothing is starved; the 16 GB MacBook stays the target. Stated in AGENTS.md §8 and [machine](machine.md#builds-and-checks).

### The agent's history is native, and what is looked up goes through the hiccup (owner, 2026-11-10)

"The agent gets raw info like it's message and eval calls in it's native format and anything that's processed or dunamically looked up should go in the hiccup render". Stated in [render](render.md#what-the-agent-reads-is-its-native-history-and-everything-else-through-the-hiccup).

### Private helpers: tests only (owner, 2026-11-10)

"Private helpers: tests only". A private function's contract is checked at its definition, in tests and in the gate, not on each production call. Stated in [checks](checks.md#contracts-armed-at-definition).

### A read never writes

"A read never writes." Stated in [turn](turn.md#an-outside-agent).

### Three request cost proofs land waived (owner, 2026-11-10)

"Land now, three waivers". Each carries an `:overrun` naming `seon.render/a-model-render-draws-each-turns-diff`; no bound moved. Stated in [cost](cost.md#an-accepted-overrun-and-its-ratchet).

### An entity is held at every path the pull reaches it

"I'm pretty sure this is a self inflicted pain point and there's a simple solution". `seon.ctx/build` holds a joined entity whole at every path its pattern pulls it, as `d/pull` does. Stated in [ctx](ctx.md#the-pattern).

## 2026-11-09 rulings

The design of one system of agents agreed that day is stated in [users](users.md#one-system-of-agents-the-design-agreed-2026-11-09) and staged in [users](users.md#the-stages-agreed-2026-11-09).

### No hacks: adjust the system

"I don't want hacks I want to adjust the system so it works properly" (wording unverified). Stated in AGENTS.md §3, the method, step 4.

### Understand before writing

"don't write data until you understand it". Stated in AGENTS.md §7.

### Questions go through the question tool

"ask questions you are awaiting answers for with your questions tool" (wording unverified). Stated in AGENTS.md §2 and [sessions](sessions.md#asking-the-owner).

### A red verdict is never deleted or moved

A fixed tree gets a new digest; a red stuck at a digest that cannot move is a gap in the digest. Stated in AGENTS.md §3 and [merging](merging.md#verdicts-are-a-cache).

### One system of agents, and no new concepts

"We are designing one system for agents that we are re-using everywhere"; "Explain the building blocks that we are resuing everywhere and how it's the data and the pull that makes each part different". Stated in [users](users.md#one-system-of-agents-the-design-agreed-2026-11-09).

### A page is the pull drawn recursively; no layouts

"No layout-of. No seon.render/layout I want to remove all the manual layout work. A hiccup render can override anywhere we are walking and rendering recursively." "Entities should have renders IFF we want them rendered." Stated in [users](users.md#one-system-of-agents-the-design-agreed-2026-11-09).

### Separate definitions, linked by refs; accretion

"Clojure and datomic are designed for accretion. If the data is there, it's validated"; "There are no 'rows'". Stated in [data modeling](data-modeling.md#things-relate-by-refs).

### An agent renders its own context; the whole render each time

"since we are sending everything every time it's okay to drop intermediate renders"; "I want efficient and smart render functions that are always focused on showing the right data". Stated in [users](users.md#one-system-of-agents-the-design-agreed-2026-11-09).

### The user is an agent with a branch

"I want the user to have a process regardless."; "User should have a branch just like the other agents". Stated in [users](users.md#one-system-of-agents-the-design-agreed-2026-11-09).

### A running system runs off the db; boot resumes from it and reads no file (owner, 2026-11-09)

"From that point on we are syncing data TO files not reading from them"; "Of course we can re-index if we want." Stated in [source](source.md#a-boot-resumes-from-the-db) and [source](source.md#indexing-a-tree).

### Claude sessions' landings enter the db as data, for now

"I want seon agents writing updates inside the system and pushing them to disk." Stated in [users](users.md#the-stages-agreed-2026-11-09).

### Docs link to code, never copy it

"Don't put code into docs if we can link to the code." Stated in AGENTS.md §7.

### Audit before merging; the code gets smaller; no heroics

"If you find yourself needing heroics and writing a shitload of code we are doing the wrong thing and we need to fix the design." Stated in AGENTS.md §3.

### One pass over the pull renders the page and the model's context

"Once we know what something is it's just a db query to find what its render function is". Stated in [users](users.md#one-system-of-agents-the-design-agreed-2026-11-09).

### What an entity is: the best match wins

"I think if an entity is several things the best matching one should win." Stated in [users](users.md#one-system-of-agents-the-design-agreed-2026-11-09).

### Render functions take what they read; the cache is never relied on

"No magic functions"; "I think this works even without the cache? It's pretty fast right?" Stated in [users](users.md#one-system-of-agents-the-design-agreed-2026-11-09), item 4.

### A session syncs by default

"I do want options for agents to be private (experimental not syncing with main the entire time) and an auto sync feature (likely the default behavior)". Stated in [users](users.md#the-stages-agreed-2026-11-09).

### The user agent generates the page; root relays and renders nothing

"So what does root have to do with anything if the user is the one generating the html?" Stated in [users](users.md#the-stages-agreed-2026-11-09), stages 1b and 4.

### A message is delivered when it is on the recipient's branch

"just deliver the message and the message being in the agents branch is the proof of delivery?" Stated in [users](users.md#the-stages-agreed-2026-11-09).

### A cite is a ref to what exists; an opened eval reaches the model

"I think logically it doesn't make sense to cite something that isn't there?"; of an opened eval: "Model reads it too". Stated in [users](users.md#the-stages-agreed-2026-11-09).

### A private session takes only what main held when it branched

"The private session only takes from main what is on main at the time it is branched." Stated in task seon.merge/a-session-syncs-with-main-unless-private.

## 2026-11-08 rulings

The owner's rulings of this day, each with his words and where it is now stated.

### A session knows its whole area; review is never delegated

"Well spec'ed tasks can be handed to sonnet, but the opus agents are responsible for reviewing the code". Stated in AGENTS.md §2.

### The Web session owns servers and demos

"Okay just take over all responsibility for servers and demos for me". Its read-only rule for others was reversed on 2026-11-11 (the standing system and its store are for use). Stated in AGENTS.md §0 and [sessions](sessions.md#sessions-own-their-areas).

### What a new design replaces goes with it

"I want this shit gone so it doesn't keep coming back" (wording unverified). Stated in AGENTS.md §3 and [merging](merging.md#a-landing-message-carries-its-audit).

### An estimate that is exceeded stops the work

"estimates that if we exceed them we stop and fix them". Stated in AGENTS.md §3, the method, step 2.

### Entity schemas are sets of keys written together

"we want accretion"; "But yeah get rid of the duplicate definitions for sure"; "One good definition should catch problems". Stated in [data modeling](data-modeling.md#a-schema-is-a-set-of-keys-written-together) and [storage](storage.md#the-write-checks-every-entity-it-touches).

### The main tree stays on main; each session works in its own worktree

"Yeah update the agents file. That's just wrong". It reverses 2026-10-28's "Stop doing separate worktrees for small updates." Stated in AGENTS.md §3.

### In-flight output is one general stream

"in flight streaming data can be lost without any serious consequences even if we restart the system or are restoring to apoint in time." Stated in [render](render.md#in-flight-output-streams).

### One walk per view, the smallest question each step, output streamed live

"we shouldn't be doing 1m things if we only need to check 1 or two things and that answers the question." "The cache isn't to be relied upon to save us if we are doing shit inefficently." Its task was retired for pages built from data (2026-11-10).

### In active development, the store is erased whenever a change needs it

"Erase whenever you want. Just warn the other agents." "In production of course you don't just drop the whole store." Stated in AGENTS.md §9 and [machine](machine.md#disk-stays-bounded).

### An id is the content of the facts that make it (owner, 2026-11-08)

"If we need an ID to be the same for the same content use the content based ID generation. IF it needs to be always unique use a UUID or content hash based on the UUID". "One live process per agent". Stated in ```seon.lib/id``` and ```seon.lib/unique-id```.

### A reply is its text and its calls; the message is written when there is something to say (owner, 2026-11-08)

"If the agent knows what it's doing and just wants to update less frequntly that's fine." Each eval is its own call `{intent, code}`; the reply's text is its optional message. Stated in [one tool](model.md#one-tool-repl) and [the prompt](prompt.md).

### The model's request holds no CSS; an agent reads it where it lives

"We need to make sure we are telling agents about their CSS and how they can search it". Stated in [style](render.md#style).

### Every eval of a reply runs (owner, 2026-11-08)

"I want us to run every eval. Turns are precious and one fuckup shouldn't punish the other evals." "Still run the evals sequentially but execute all of them." Stated in ```seon.repl/run-calls``` and [the prompt](prompt.md).

## 2026-11-07 rulings

The owner's rulings of this day, each with his words and where it is now stated.

### The db is the source of truth; a disk edit imports only when asked

"the db is the source of truth and we should be syncing to the disk from within seon". A disk edit acts on nothing until a person re-indexes. Stated in [health](health.md#every-writer-the-db-is-written-files-follow) and [source](source.md#indexing-a-tree).

### No magic: maintenance is Clojure data on ctx

"I want less special functions and more clojure data manipulation on the ctx atom for maintenance." "I don't want any magic in the system." Stated in [users](users.md#messages).

### A wake allowance controls the loop

"lets configure a wake allowance that increases the max turns and that is the main mechanism that controls the agentic loop." Stated in [users](users.md#messages).

### Messages have no read state

"I don't want a concept of 'read and unread' this is buggy as shit. Messages are delivered to the agents entity and they can remove them". Stated in [users](users.md#messages).

### What's on screen is a reflection of the database

"I want the UI state to be a reflection of the DB so we need to update thte db and that causes the re-render." Stated in [users](users.md#whats-on-screen).

### Only Clojure's, our libraries' and agreed words

"Please only use clojure terms or the libraries we are integrating with or the concepts that we agree on like using git terms for datahike branching". Stated in [vocabulary](vocabulary.md).

### The system is a function of state in the database

"This is not OO programming. State is always in the database and the system is a function of that state." Stated in [users](users.md).

### Think in data structures, and never pass a database

"We need to think of things in terms of data structures." "don't pass around databases ever. We have a data format and schema for it." "There are no tables in the database". Stated in [users](users.md#what-each-thing-is).

### Immutable first: same value, same id

"We can reuse data structures. Think immutable data whenever possible ... Everything is a fork of a shared base that we can point to." Stated in [users](users.md#where-each-fact-lives).

### Fences mean the design is off

"Fences are always signs a design is off. What do we need to simplify to remove the need for guards and fences." Stated in [users](users.md#four-interfaces).

### Lazy, and dead branches cost nothing

"In general I want hte system to run lazily and only realize and do work that needs to be done at the time it is needed." Stated in [users](users.md#the-merges).

### Git's words

"I think we should stick to git terms whenever possible so agents have an intuitive understanding." Stated in [vocabulary](vocabulary.md).

### A user is an agent, in worlds within worlds

"a user has refs to all the agents that they have started by default". "merges can fail if main moves under them and that's fine, the error allows the agent to fix it". Stated in [users](users.md).

### A contract checks a collection whole

"Keep full checks". Stated in [cost](cost.md#what-a-contract-costs).

### The default agent chats, and more comes by threads it pulls

"Not giant data dumps, but progressively over time they get the data when they need it". Stated in [working for a person](agent.md#working-for-a-person).

### No kinds; a keyword may name a template

"in general there are no \"kinds\" and those are generally code smells"; of keywords that map to templates, "that's fine". Stated in [tasks](tasks.md#a-request-is-one-transaction).

### A merge writes itself out while its tests pass, and approval comes later

"yes this behavior should be configurable and the user or root should be approving it." Stated in [source](source.md#commit-the-merges-branch).

### Seon's key files are facts, on one mechanism

"is the indexing a shared system or are we building another parallel system": the schema files' entity and merge are generalised, not copied. Task `seon.source/seon-files-move-by-branch`.

### A user's branch tracks main like an agent's

"I imagined a users branch tracking main just like the agent branches we are launching." Stated in [the plan](plan.md).

### A proof is focused

"More is not always better. Focused and good at heping determine correctness and the state being done are the focus". Stated in [testing](testing.md#a-proof-is-focused).

### A proof starts simple and evolves

"I generally want to start with a simple proof like respond to the user and build up or merge with templates to get more correct testing conditions for what success looks like. It should always be evolving."

## 2026-11-06 rulings

The owner's rulings of this day, each with his words and where it is now stated.

### A well-scoped task is done without asking

"If you have a well scoped task please do it without asking". Stated in AGENTS.md §2 and [sessions](sessions.md#asking-the-owner).

### Old work retired, not left halfway

"we are merging things and not just abandoning them halfway". The retired branches and their commits are in git.

### A person's view of an eval's result, and what a repeat is

"An identical result should have the same result id". Stated in [render](render.md) and [turn](turn.md).

### The model's context is a render of the same tree as the page

"When we were doing separate model and user html renders they always drifted." "I really don't like the part name"; it is a component. Stated in [render](render.md#the-models-context-a-cached-prefix-then-the-turn).

### What the model reads is precise data, teaching included

"I just want them presented as data and strings." Diffs are editscript: "I want the gains".

### Seon's own files move by branch, and an agent has the whole disk

"File ops will be required for other work like editing non seon code or just doing computer maintainance"; "I don't care who fixes the problems on main".

### An eval has an intent

Superseded on the message by 2026-11-08, a reply is its text and its calls; the intent per eval stands: "there are intents for each eval that forces the agent to think about what it's doing before it does it". Stated in [prompt](prompt.md).

### Design for classes of problems, and look for root causes

"I want an elegant design that prevents entire classes of problems so if we find ourselves hacking things we have the wrong design." "Agents repeating themselves is not waste it's a bug." Stated in AGENTS.md §3.

### Sessions work together with no fixed ceremony

"I don't want the work done twice. If you do it well merge it." "Aim to get 90% of the benefit for 10% of the effort." Stated in AGENTS.md §§2, 3 and 8.

### A slow test runs last

"The slow ones are just the last ones to run and again we focus on short circuit not writing out full reports." "exclude them by default until they can run fast". Stated in [merging](merging.md#a-slow-test-runs-last).

### A reply with no calls pauses the agent, whatever its proofs say

"No more turns even if it's red." Stated in [prompt](prompt.md), [turn](turn.md#parked) and `seon.turn/act!`.

### A parked agent stays in memory, and replay is deliberate

"we want to be intentional about replay and maybe even do selective replay". A parked agent is a blocking wait. Stated in [turn](turn.md#parked).

### Edits go to the database, then to disk when asked

"I want edits to go into the database (which is the source of truth) and then to be explicitly synced to disk". Task `seon.render/an-edit-reaches-disk-when-asked`.

### No regex or text parsing; function composition

"No regex or parsing text. I want function composition." Stated in [source](source.md#a-forms-account-is-the-programs-facts) and [turn](turn.md#an-eval-can-be-run-again).

### An eval run again replaces itself

"Evals I run again should replace (upsert) the existing eval". Stated in [turn](turn.md#an-eval-can-be-run-again).

### The debug page is the wire; the agent's page is where it is friendly

"I want the wire format types as they are. This is a debug page"; "expand should expand not repeat". Stated in [render](render.md#the-debug-page-is-the-wire-request).

### Styling is a render the agent can read and change

"pull in the relevant CSS as one of the render functions so the agent can separate styling with context and they can update it too". Stated in [render](render.md#style).

## 2026-11-05 rulings

The owner's rulings of this day, each with his words and where it is now stated.

### Look around before building; no page of doctrine

"There is no ONE WAY, just fucking look around and don't rebuild shit that we've already built." "Don't just act think and plan". Stated in AGENTS.md §1.

### An agent experiments with a render of its own

"it's better if they are experimenting to just declare their own." Stated in [ctx](ctx.md#adding-a-datum-and-its-render).

### An agent's entity, pull and renders make most of its context

"NO SPECIAL CASE FOR CITING. That's just data and a render function". Stated in [ctx](ctx.md#what-the-model-reads-and-what-the-page-renders).

### The debug page draws history with today's renders, and says when that differs

"Yes, and try it". Stated in [the debug page](render.md#the-debug-page-is-the-wire-request).

### The render is an eval, and what changed is another

"We can just make the rendering of the hiccup a normal eval that we inject into turn 0 and then we can just do diffs when it changes." Stated in [render](render.md#the-render-is-an-eval).

### Large or changing output is the render's bug

"Nothing we should be rendering should be huge or should be repeatedly changing dramatically. That's a failure of the render functions and the system for generating noise."

### Data is entities with attributes, and nothing is quadratic

"Nothing should require N^2 because we are progressively building things if we aren't then again it's the wrong design." Stated in [data modeling](data-modeling.md).

## 2026-11-04 rulings

The owner's rulings of this day, each with his words and where it is now stated.

### No parallel systems (owner, 2026-11-04)

"I don't want agents to keep building parallel systems". Before adding a second layout, cache, path to the model or store, look at what already does it ([a bug is a class](#a-bug-is-a-class-remove-what-allowed-it-owner-2026-11-03)). Stated in AGENTS.md §9.

### Data is stored as data; the one printer is for context

"The only value printer should be the one value printer and that's for assembling context for agents". Stated in [plain data](storage.md#plain-data).

### The standing store is erased when the new schemas land

"Yes you can erase the runs/store when we have the new schemas in place." Done 2026-11-05.

### Agent branches are kept and queried (owner, 2026-11-04)

"In general I don't want to delete the agent branches and I want to query them when we need information on what happened for an agent." Stated in [merging](merging.md#seons-merge-is-a-three-way-merge-of-the-program).

### An agent's data makes its page and its context

"No more hand crafting things. Let the system operate and figure out how to have everything declare itself". Stated in [render](render.md#one-rendering-system-drawn-from-the-data).

### The data's shape is ours, batteries included

"We have full control so make a system that will work generally and optimally one way and make it so it's easier to build things the optimal way". Stated in [data modeling](data-modeling.md).

### Store data for how it is used; a turn is a number

"An agent has turns and evals why are we organizing them on top of other entities". Stated in [turn](turn.md#the-turn-is-a-number).

### A render asks for the db and queries what it wants

"The render functions get the curretn db if they ask for it so you can do any query you want and it doesn't have to be reachable from the agent directly". Stated in [components](render.md#components).

### Re-render when the data or its function changes

"when data changes OR THE FUNCTION THAT RENDERS IT changes then you re-render". Stated in [reactive renders](render.md#reactive-renders).

### Delete, compact and reset are data

"I want the ability to reset to any point AND to delete arbitrary data". Stated in [ctx](ctx.md#deleting-compacting-and-resetting-are-changes-of-data).

### The page is latest only

Replaced on 2026-11-05: the render is an eval, and what changed is another.

### Every agent improves the renders

"We distrubute the task of writing rendering functions for data across the whole system". Stated in [ctx](ctx.md#adding-a-datum-and-its-render).

## 2026-11-03 rulings

The owner's rulings of this day, each with his words and where it is now stated.

### A bug is a class: remove what allowed it (owner, 2026-11-03)

"Always be suspicious about bugs. Usually fixing them directly is the wrong solution. Root cause and see if we should be doing something that eliminates the bug class." Stated in AGENTS.md §3, the method, step 4.

### A contract costs nothing on its one interface; the same data is checked once

"Oh yeah contracts don't count against anything as long as you do it through the optimal interface and we aren't checking it multiple times for no reason."

## 2026-11-01 rulings

The owner's rulings of this day, each with his words and where it is now stated.

### Benefits are general; nothing leads an agent to its answer

"Do not lead the models just to score a win. All benefits should be general." Stated in [cost](cost.md#an-accepted-overrun-and-its-ratchet).

### Past sessions are shown as curated sessions

"Yeah sessions is fine"; "Curated sessions." A curated session reaches the model as the same tool-call turns its own evals do, never as a text blob. Stated in [the plan](plan.md).

### Context is data a render draws from the db

"Do not create parallel systems to the context generation and rendering."

## 2026-10-31 rulings

The owner's rulings of this day, each with his words and where it is now stated.

### A test shows its claim, not a smaller copy of its old input

"question what is being tested rather than how fast can we make it. Algoirthmic improvements often can be found by rethinking the problem."

### A flaky check step is merged past and filed, not rerun into a verdict

"merge and file".

## 2026-10-30 rulings

The owner's rulings of this day, each with his words and where it is now stated.

### Limits fail loudly

"The limits should always fail loudly btw." Stated in AGENTS.md §5 and [config](config.md#a-bound-fails-loudly).

### A timed wait means the wrong design

"timeouts just mean we have the wrong design (most of the time)" (wording unverified). Stated in AGENTS.md §4 and [clocks](clocks.md).

### There are no runs: an agent has an env, a branch and its evals

"there is no concept of a \"run\" agents have an env a branch and are doing evals. Attach data only to real things". A message wake raises the turn limit by a config amount.

### Errors reach the agent grouped, once

"errors render grouped by signature with counts, newest first line and a handle; root records all; the context sends what changed." Stated in `seon.render.ui/problems-model`.

### The context sends changes

Approved: a changed part sends `clojure.data/diff` of its value; a cited var at preview depth; the turn counter on each eval's outcome line. Task seon.ctx/the-context-is-the-agents-evals-and-seons.

### Light tests while an agent develops, the full suite near merge

"let's keep the test suite minimal and most likely impact". Stated in [merging](merging.md#a-merge-runs-what-the-change-reaches).

### A process test's cache gap stays; the full run catches it

"leave it". "Can we just mark tests that we know can't be cached so if they are touched they always rerun?" Superseded by 2026-11-10, a test that starts a root takes what root runs.

### Main keeps recording live data; agents react only when main's program moves

"I do want a concept of a shared main moving forward with data so that has to remain the same"; "Should main's agents react only when main's program moves? I think so." Stated in [the plan](plan.md).

### Eval budgets come from measured costs

`:seon.config.agent/form-ticks` and form-bytes are derived from measured eval costs, not fixed; an overrun is mostly a case worth intervening.

### Simplify by constraint; the test suite must be trusted

"I'm okay with adding constraints to the system to simplify the solutions"; "I want all correctness issues solved with our test suite. We need to trust it as much as possible." Stated in [the plan](plan.md#now).

## 2026-10-29 rulings

The owner's rulings of this day, each with his words and where it is now stated.

### A link the owner is given survives a restart

"Always give me links or give me a stable way to find links on a stable server". Stated in [sessions](sessions.md#stable-links).

### The page is a friendly chat; what the model was sent is shown whole

"Does that look like a friendly chat window that my mom could use?"; "I don't want anything hidden". Stated in [render](render.md#the-page) and [the debug page](render.md#the-debug-page-is-the-wire-request).

### Counts are tokens; what an agent writes is Markdown

"only report tokens (estimates are fine) not characters". Agent text is Markdown. Stated in [prompt](prompt.md).

### References at their depth, previews first

"We control what we are showing and we can still just do previews and the agent can dig in for full details". Stated in `seon.ctx/cited`.

### Parts answer data: an html render and a model render, the value named status

"keep everything as data until it's at a print boundary". How an agent's work stands is named status, git's word. Stated in [render](render.md#a-component-answers-hiccup) and [render](render.md#how-the-work-stands).

### The standing store is test data: wipe it when a change needs it

"nothing in the Store is needed. wipe it whenever you need too." A renamed or retyped stored attribute erases the store, never migrates it. Stated in [machine](machine.md#disk-stays-bounded).

### The verdict cache holds 512 MB, least recently used out first

"least recently used automatic eviction if max capacity is reached"; "don't drop cache work that other agents may need". Stated in `:seon.config.proofs/results-max-bytes`.

### An eval call holds one or more forms

"remove that from the docs that only one form runs". `:value` is the vector of the forms' values ("okay that works"). Stated in [prompt](prompt.md) and `seon.repl/response`.

### The model's context is the render of the agent's record, by the one walk

"build with both the context render and the web ui render in mind so we don't let one get out of sync with the other". Stated in [render](render.md#the-models-context-a-cached-prefix-then-the-turn).

### Nothing derived is held; everything is derived from the db at t

"Don't fear deletion make the system work with the data that's there. Period. Don't try and hold random derived state in the system. Derive it from the current state." Stated in AGENTS.md §4, [ctx](ctx.md) and [render](render.md).

### The agent's record is facts about its work

"The agent's record is facts about its work. Telemetry and derived data live on their own entities, pointing at what they describe". Stated in [ctx](ctx.md#the-agents-entity-holds-its-work) and [ctx](ctx.md#the-pattern).

### The agent is the unit, not the run

"get rid of runs, the concept doesn't really exist"; "more git merging terms ... less made up things". See 2026-10-30, there are no runs.

### An agent's test results are facts on its branch

"Agent runs only": an agent's tests write `:seon.test/*` facts on its branch; `bin/test` and `bin/prove` keep the shared verdict cache.

### A value is rendered by the newest render of its schema

"if we don't know what something is we return a warning/error so the agents are encouraged to fix it". Not built.

### Seon runs on an ordinary MacBook

"I want this to run on an average users macbook for reference. Not just servers or people like me with 128GB of ram." Stated in [machine](machine.md#ten-agents-at-once).

### The image keeps each frame's file:line and drops the expanded source

He chose V2b: about -210 MB a root, every eval error unchanged. Task seon.main/the-image-keeps-no-inspector-information.

### The embedding model is a Flow proc

"Look into this don't guess." Stated in [vector](vector.md#the-model).

### Config at every level, no env var switches

"formalize config options so there is no env var magic happening." Levels are built as keys need them (below). Stated in [config](config.md).

### Env vars are fine when aero captures them into config entries

"env vars aren't bad, but the need to be captured by aero and translated into config entries." Stated in [config](config.md).

### Stop the garbage at its source

"Yes stop the garabage for sure". Stated in [machine](machine.md#roots-heap).

### Use the machine while nothing is starved

"Load isn't too bad, you can run as long as we aren't starved for anything which just slows down everything." See [load is judged by cores and free memory](#load-is-judged-by-cores-and-free-memory).

### No artificial limits or cuts on an agent's context or evals (owner, 2026-10-29)

"wait do not put any artificial limits or cuts on agents context or evals." Stated in [errors](errors.md) and [agent](agent.md).

### Ten to twenty agents on a small machine

"Yeah I think 10-20 should be fine for users with small machines and the sky is the limit for servers and big iron." Stated in `:seon.config.agent/max-running`.

### Fix every major optimization issue; memory is the thread to pull

"Your job is to priortize fixing ALL major optimization issues that you see."

### Config levels are built as keys need them

"only levels in use". Stated in `resources/seon/tasks/seon.config.edn`.

### Seon fits 8 GB

"8 GB, fits in 16". Stated in [machine](machine.md#ten-agents-at-once).

### The standing root follows main's ref, never the checkout's HEAD

"root follows main ref". Task seon.fx/the-standing-root-follows-main-not-the-checkouts-head.

### Old worktrees' run data is not kept

"No run data is important right now."

## 2026-10-28 rulings

The owner's rulings of this day, each with his words and where it is now stated.

### An eval's result is data with Clojure's value

"Okay match clojure for the eval output (4)". One form per call is superseded by 2026-10-29.

### The model is sent the whole conversation; no history budget

"who made a 32k history budget? Remove it I never approved that". Stated in `seon.ctx.wire/request` and [render](render.md).

### The eval protocol: tool calls by id

"Match Clojure: unroll a top-level do so its definitions are program". Stopping at the first throw is superseded by [every eval of a reply runs](#every-eval-of-a-reply-runs-owner-2026-11-08). Stated in [prompt](prompt.md).

### Nothing crashes and nothing hangs; failures are data to the agent

"I want our system to be robust and to have it not crash or hang ever." Stated in [errors](errors.md#nothing-crashes-and-nothing-hangs).

### Every eval's answer says how main differs from the branch

"They can ignore it and work and then merge at the end, but there are never any surprises." Stated in [merging](merging.md#main-moved-is-said-at-every-eval).

### Derived data may be cached; it is always regenerable from the db at t

"Derived data may be cached with the understanding that it may be evicted at any time". Stated in [data modeling](data-modeling.md#derived-data-is-a-cache-never-relied-on).

### Focused sessions; no fan-out

"This system of launching 10+ agents just burned my tokens and was not productive". Stated in AGENTS.md §2 and [sessions](sessions.md#no-fan-out).

### Build the product, not infrastructure (owner, 2026-10-28)

"we built out a lot of infrastructure thinking it would speed things up and it really hasn't". Before building tooling, ask whether it moves an agent finishing a real task. Stated in AGENTS.md §9 and [sessions](sessions.md#no-fan-out).

### Test results are a few facts per test and per function, with no history

"We want to be able to query things in the db, functions, tests, schemas and understand what tests are failing and timings so don't remove everything from the db". Task seon.proofs/test-results-are-a-few-facts.

### A kept db value is a reference, {:branch :commit :t}

"I don't want us carrying around large values for no reason. At those points we need to change db's into whatever facts we need to re-create that point in time." Stated in [storage](storage.md#a-kept-db-value-is-a-reference) and AGENTS.md §4.

### Context is structure first; ranking is judged by turns to green

"We should be seeing 90% relevance and then the agent actually using it and shorter number of turns to achieve a goal as proof something is working"; "sure let's start with structure first". Stated in [ctx](ctx.md#what-the-scorer-reads-from-a-pulled-node).

### The cron stays, written well

"We do need a cron, that's fine if it's well written."

### The store is erased outright

"Yes, erase it outright with no archive copy."

## 2026-10-27 day rulings

The owner's rulings of this day, each with his words and where it is now stated.

### An inventory before a plan

"a new agent already has a plan? that can't be well thought out" (wording unverified). Stated in AGENTS.md §3, the method, step 1.

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

"Just remove the test temporarily if we need to." Stated in [merging](merging.md).

### No test digests the whole tree; the daily full run is the net

"no tests should include all of src." Amended by [a test that starts a root takes what root runs](#a-test-that-starts-a-root-takes-what-root-runs-owner-2026-11-10). Stated in [source](source.md#tests-run-by-reach).

### A lock-free system

"I want a lock free system. This worktrees hack is temporary." Stated in AGENTS.md §8 and [merging](merging.md).

### The wiki is the one home

"we should be using the wiki for everything so it gets better because that's what agents are pulling into their context … This means don't duplicate what's in the wiki." Stated in AGENTS.md §7.

### Test results are a few facts through one writer

"one writer for sure"; "never 300k new records per test run, in the database, just the essentials."

### An eval records; what is derived for the agent is a render

"Info that is derived and made for the agent or the user should go into the hiccup"; "you shouldn't be marking if something has been observed you just record the data". Stated in [render](render.md) and [turn](turn.md).

### A relevance % means "worth reading"

"I really need a ranking ideally with relevancy % that we can do cutoffs for"; "I want accuracy more than speed." Stated in [vector](vector.md).

### What a Claude session sees through MCP is what a model is sent

"making the results match the agents context we are generating. I want them to see all the errors and problems and for us to fix them at their source not patch things." Stated in [agent](agent.md).

### Unused is not dead

"Just because things aren't in use doesn't mean I haven't worked hard on the design". Code with no caller is cut only with the owner's go. Stated in AGENTS.md §3.

## 2026-10-26 night rulings

The owner's rulings of this day, each with his words and where it is now stated.

### A request is one whole task by default (owner, 2026-10-26 night)

He chose one whole task over design-build and merge; design-build and stages stay experiment choices. Stated in [the plan](plan.md).

### Paid runs only on context whose known problems are solved; $30 overnight (owner, 2026-10-26 night)

"Garbage in = garabage out". The budget is now $50 ([paid model runs](#paid-model-runs-up-to-50-productive-runs-only-known-wrong-context-is-fixed-first)).

### Correctness and stability first, then efficiency to a stated bound; no regression without a reason (owner, 2026-10-26 night)

"prioritize correctness and stability first and efficiency after that to a resonable (it should roughly take this amount of ticks, memory, etc) and don't accept regressions for no reason". Stated in AGENTS.md §3 and [merging](merging.md#a-landing-message-carries-its-audit).

### The agent's view is the render of ctx; "page" names a wiki page only (owner, 2026-10-26 night)

"Yes it's just a render function for data." "No separate systems right?" Stated in [vocabulary](vocabulary.md#words-we-do-not-use).

### The render of ctx never repeats what an eval shows (owner, 2026-10-26 night)

"my point was to not show the same eval data in the render function". Where it touches an eval, the render carries the eval's handle.

### Flaky results are kept (owner, 2026-10-27)

"Yes: erase test-results/ and runs/store at that merge (both are test data)." Red and green at one digest is `:flaky`, judged red, never rerun. Stated in [merging](merging.md).

### A work task merges itself (owner, 2026-10-27)

"Yes: drop with-merge from task files; a work task merges itself through the one gate. Keep design-build and stages as experiment templates." Task seon.merge/one-merge-system.

### All work happens inside Seon; worktrees are retired (owner, 2026-10-27)

"The goal is to stop using the worktrees and to do all work within seon itself". Worktrees stay as the bridge until then: see 2026-11-08 and 2026-11-11 (worktrees stay for real work). Stated in [the plan](plan.md).

### Styling: compact hiccup, every element earns its spot (owner, 2026-10-26 night)

"make it so the fully printed hiccup is compact and everything earns it's spot." Stated in [render](render.md#style).

### Old branches: audit, then delete (owner, 2026-10-26 night)

Each old branch is read for anything not carried forward, which is filed; then it is deleted.

## 2026-10-25 rulings

The owner's rulings of this day, each with his words and where it is now stated.

### Message delivery: compare, then the better design or the simple one

"We either have a better design or we do the simple one that works". The comparison chose delivery on main first. Replaced by 2026-11-09, a message is delivered when it is on the recipient's branch.

### The related-section count is a config key; one problem-entry helper's bounds accepted

He approved one config key for how many related wiki sections are shown (0 turns it off), and raising 5 cost proofs' call bounds by one contracted call per problem for one shared helper.

### Dogfood rulings: design registers proofs; each form's value; stale means meaning changed; pilot budget $5

A design stage registers its red proofs; a section is stale when a cited var's contract or docstring changes; the pilot may spend $5. Stated in [tasks](tasks.md#the-done-rule) and [health](health.md#a-stale-section).

### No guards, least of all in Jolt or the db

"It's always a sign we don't have the right solution which negates the need for a guard". Stated in AGENTS.md §§4 and 9.

### An agent's spawn costs what a fork costs; no unnecessary complexity

"like what are we doing if we can fork in ms?"

### @ctx prints as an atom

"the agent will have the value printer print out the atom like a normal atom with our structure and then in the web ui for that eval we'll render it in a block."

### One-go tasks for every run; fix the bug, not the kind (owner, 2026-10-26)

"switch to one-go for all tests until we figure out a better solution and don't blame one go on the bug that hung the agent fix the bug!" Stated in [the plan](plan.md).

### A stall is seen on the page and becomes root's task (owner, 2026-10-26)

"the hangs are super bad especially if you don't notice an agent has been stuck". Stated in [the plan](plan.md).

### Not passing until the flow is bounded and within budget (owner, 2026-10-26)

"it's not a passing solution until all functions are spec'ed including with cost estimates and we can budget roughly what the max total cost is among data flows within the system being referenced". Stated in [the plan](plan.md).

### Bounds as the plan (owner, 2026-10-26)

"writing bounds is also sketching out the algorithm … ideal for a REPL interface". Stated in [the plan](plan.md).

### One rendering system: the data describes the page (owner, 2026-10-26)

"There is only one context rendering system and it needs to be built from the parts." Stated in [render](render.md).

### No invented words; a render is a function and its data (owner, 2026-10-26)

"A render is just a function and the data being rendered. No story". Stated in [vocabulary](vocabulary.md).

### Everything the agent and the page show comes through ctx (owner, 2026-10-26)

"any changes you want updated in the agents context and html should go through database updates that reach the same data the context pull gets which keeps the ctx atom in sync". Stated in [the plan](plan.md).

### Values render when opened (owner, 2026-10-26)

"don't render things that we don't need until the user expands it … the agent should know the user is digging into it". Stated in [render](render.md#the-page).

### Try it in agents' REPLs first; design after; never another system (owner, 2026-10-26)

"do not build yet another system". Designs follow REPL experiments. Stated in AGENTS.md §3.

### Keep it simple, so the system is easy to modify (owner, 2026-10-26)

"Keep things simple so we can easily modify the system". Stated in AGENTS.md §4.

### Two outputs: an eval's data, and the page with its diffs (owner, 2026-10-26)

"there are only two outputs too". Its deletion of a model render is reversed by 2026-10-29.

### No derived data in the db or in ctx (owner, 2026-10-26)

"no we don't store derived data in the db or the ctx atom". Refined 2026-10-28 (derived data may be cached when regenerable from the db at t) and restated 2026-11-11 (no derived value is stored).

### Stability and correctness first (owner, 2026-10-26)

"Get them to main and prioritize stabilty and correctness".

### The REPL stays simple; every enrichment is a render (owner, 2026-10-26)

"I want the repl and evals itself to be simple and stable". Stated in [render](render.md).

### The wire: our channels inside the provider's roles (owner, 2026-10-26)

The provider's roles stay: system, user, assistant, tool.

### The request is a pure function of the db at the session's t's (owner, 2026-10-26)

"how it's byte stable from database T and not a stored set of messages we are sending to the agent". Stated in [render](render.md).

### Changes arrive as messages; the eval response says only what the eval caused (owner, 2026-10-26)

"I think we might need explicit messages for changes coming in and having those return the diffs."

### Evals run once; the agent is told what changed (owner, 2026-10-26)

"evals maybe should never be re-run (unless we are resuming a session) and instead we should just show the diffs which are happening as things change". See 2026-11-06, an eval run again replaces itself.

### Every change is told, as a data diff in ctx's shape (owner, 2026-10-26)

"all changes should be worth telling (if we have weird naming and unnecessary updates and machinery instead of actual values we have the wrong design)".

### Evals are evals; both contexts always live (owner, 2026-10-26)

"The agents context and the users context are always live." Its replay is superseded (2026-11-06).

### Owner answers, 2026-10-26

The owner's answers to that day's questions.

1. The joint ctx/UI design is built when it follows the rulings ("yes").
2. The $30 paid budget continues ("yes").
3. "So make it an attribute or something so it's clear if we re-execute or just pull it's cached value. This is an explicit thing not an inferred": replay is an explicit attribute.
4. "Caching should be deterministic and if we shoot ourselves in the foot and change the data half way through we lose 50% of the cache, but if we are accreting everything up to the dynamic forms it should be caching at that rate."
5. "audit the branches for merit with sonnet and suggest integrations".
- Of the hiccup the model reads: "I want both a) a not giant representation of the UI that the user is actually seeing and b) not to distort it".

### The web UI is the render of ctx

"are we deriving the same data twice? why?" Stated in [render](render.md).

### Fix causes, not symptoms: the repeat rule is under investigation

"the repeat thing sounds like instructions or formatting and we should fix it not patch it. investigate." "We want less hacks and more fixing root causes". See 2026-11-11, a stuck agent is stopped, analyzed and fixed.

### Overnight budget $30; old branches triaged forward; ctx built render-first

"triage and move forward the good ideas from the unmerged branches".

### Overnight: all cylinders

"I approved highlight.js. launch all fixes." See 2026-11-10, load is judged by cores and free memory.

### The value view: highlight.js, agent hiccup unrestricted, built after the printer

Highlighting by vendored highlight.js; an agent's hiccup renders as HTML with "no restrictions". Stated in [render](render.md#a-value-on-the-page-is-its-structure).

### Platform stability first

"Platform stability is very important so find out why and fix the cause".

### Related things are found by measured search over every kind, through stored edges

"okay implement the best related context fixes we know of". Stated in [vector](vector.md).

### Code is shown as code; the page shows values as structure

"If we reference a function we should return the quote escaped forms so agents see more clojure code as is normally seen in files or the repl". Stated in [render](render.md#a-value-on-the-page-is-its-structure).

### Noise is fixed at its producer

"Id rather us focus on not generating crap and then not rendering crap." See 2026-10-29, no artificial limits or cuts.

### A change to a schema is an explicit fact the eval shows, never a hidden digest

"I want explcit changes in the system to modeled accurately". A contract or docstring change is a transaction, and the eval that makes it shows before and after. Stated in [health](health.md#a-stale-section) and [agent](agent.md#your-turn).

### Starting and resuming are one mechanism

"I don't want a separate system for running agents." See 2026-11-06 on replay.

### No new linking attributes where :needs does it

"We have the :needs way of connecting the graph literally together ... Every agent should have a stable key of course that's essential." "of course everything should survive if we can resume it."

### Messaging is one function, `my.agent/send-message`

One function for an agent to message others.

### No restrictions; any agent can be root; agents edit their own dynamic forms

"All agents can be root. Root is just a name we give them". See 2026-11-11.

### A request's stages are separate steps in one session

"I want the investigation and implementation and merging steps to be separate steps". The default is one whole task (below).

### Every agent parks; resume replays pure forms and asks for effects

"Always park" and "Auto-replay pure, ask for effects". Automatic replay is tempered by 2026-11-06 ([a parked agent stays in memory](#a-parked-agent-stays-in-memory-and-replay-is-deliberate)). Stated in [processes](processes.md#a-parked-process).

### A session's context is generated from the db

"we should be generating context from the db at a commit point and it should be immutable". Stated in [render](render.md).

### Root's special cases go, all four

Root is an agent on main, the dead merge path is deleted, root's work is tasks on main's current commit, and there is one prove path.

### Agents stay running, parked, between messages; any commit can be resumed

"Maybe we should just keep the forks running and just parked." Stated in [processes](processes.md#a-parked-process).

### Correctness and performance come before lines of code

"prioritize correctness and perf over lines of code. Lines of code is not my main metric". Stated in AGENTS.md §1.

### Root is main; everything else is the same system with different config

"not build a separate system for root". Stated in [users](users.md#the-entities).

### Agents restart from their record; the lifecycle is Flow's

"Can we just have the flow step function and the lifecycle handle the forking and killing?"

### The screen is sent as diffs between turns

"Diffs between turns". Replaced by 2026-11-05, the render is an eval, and what changed is another.

### Merge the agent-view branch to main once its review is done

"873 lines isn't that much if you think it's good lets merge to main."

### A request is one whole task, for now

"until we solve this we should keep experimenting with having the agent do everything in one task until we get this idea fully baked"; "don't get rid of the other tasks". Stated in [tasks](tasks.md#a-request-is-one-transaction).

## 2026-10-24 rulings

The owner's rulings of this day, each with his words and where it is now stated.

### A failed model call keeps what it sent and its typed error

"Approve :seon.turn/error." Now `:seon.model-call/error`. Stated in [turn](turn.md#the-turn-is-a-number).

### A turn records what it sent as refs; texts are read as of the turn

"Approve :seon.turn/sent only; refer to the system prompt's wiki section and the task entity as of the turn's t instead of new :seon.text attributes." Now `:seon.model-call/sent`. Stated in [ctx](ctx.md#the-agents-entity-holds-its-work).

### A config key does what it says; a provider's schema refuses a combination it forbids

"have the config do what it says and if there's a combo that's not allowed we can just have a schema validation for deepseek that confirms the right settings are set". Stated in [config](config.md).

### The agent view and context design is approved

"Approve the design as written, including the owner items in section 11." Its parts are restated by later rulings; stated in [render](render.md).

### Names tell a story; the printer shows every value well

"Beautiful naming and the render tree should tell a story and not just be full of random words". Stated in [vocabulary](vocabulary.md).

### A prove run is keyed by the digest of what it reads

"okay lets try the digest". Stated in [merging](merging.md#the-test-service).

### Root merges verified fixes as they come in

"merge fixes as they come in". Stated in [merging](merging.md#a-branch-lands-with-binland) and [merging](merging.md#a-landing-message-carries-its-audit).

### One render per schema, declared where the schema is registered

"Okay I'm willing to try a single render function". Its single face is reversed by 2026-10-29. Stated in [render](render.md).

### The debug view shows exactly what was sent

"Yes in terms of content, but I would like it styled and clear to read." Stated in [the debug page](render.md#the-debug-page-is-the-wire-request).

## Tonight's rulings (2026-10-23, with the 2026-10-21/22 rulings they rest on)

The owner's rulings of this day, each with his words and where it is now stated.

### Learn from earlier work, then write it clean; remove a worktree once it holds nothing unsaved

"don't throw away good work have agents learn from it if you want a clean implementation." Stated in AGENTS.md §9.

### Reuse every unique, immutable result that is proven the same and expensive to make

"I want reuse of all unique and immutable data we are sure is the same and is expensive to render". Stated in [data modeling](data-modeling.md#derived-data-is-a-cache-never-relied-on).

### The turn's live state is REPL forms

"I want agents to write functions in the REPL, they get feedback for what's changed compared to main with clear diffs, the tests they need to pass are clearly listed." See 2026-11-05, the render is an eval.

### Examples are real green sessions, exported to EDN on root's call

"agreed the examples should be real and we should fix them correctly". See 2026-11-11 on reviewed sessions.

### The model's context is the standard chat format: a short system prompt, the task as the user message, one eval tool

"we need clear more traditional template style chat based with tool calling official support or we validate our approach is actually correct against a known quantity". The tool is now `repl` ([model](model.md#one-tool-repl)). Stated in [render](render.md#the-models-context-a-cached-prefix-then-the-turn).

### The design stage looks around and writes the referenced plan

"That's why I have the design task first. Look around and write the referenced plan". The default request is one whole task (2026-10-26).

### src resists growth; no line-count target

"don't hyper fixate on LoC. I want some resitance to growth mostly so agents reuse what's there and improve the core parts rather than making one offs". Stated in AGENTS.md §1.

### The context is data, made relevant at its producer; renders do not turn it into prose

"Data is better so just improve the data so relveant info is there". Stated in [render](render.md).

### Sessions research on their own; a new session needs root's approval, unless the owner says otherwise

"I want to be able to instruct any agent to override that". Stated in AGENTS.md §2.

### The embedding work goes ahead, the model download included

"yeah I approved the embedding work". Stated in [vector](vector.md#the-model).

### No live agent runs until the context has a format agents respond well to

"I'm not sure how helpful it is to run live agent runs until we get a sane format that agents respond well too". Stated in [the plan](plan.md).

### The reuse design is approved: one memo keyed by what the process runs, for the walk's renders first

"Approved: build the revised reuse design, walk node renders only, with its proofs." Its branch was retired on 2026-11-06 ([old work retired](#old-work-retired-not-left-halfway)).

### bin/test branches a kept program db per git tree (option B); each worktree's root is the end state

He chose option B: a kept test store holds a branch per git tree. Replaced by 2026-11-10, the test system runs inside Seon.

### Reuse is memoization: `(= (f x) (lookup f x))`, so return it

"they are literally the same so just return it". "we shouldn't be caching on a key that needs to be updated." Stated in [data modeling](data-modeling.md#derived-data-is-a-cache-never-relied-on).

### Reuse is transparent and simple: a reused value is shown as it is, never marked

"Let's just show it as is if it's cached". Stated in [cost](cost.md).

### Everything is fast; a render or a context whose inputs have not changed is never made again

"In general I want everything to just be fast, but something are just dumb to not cache like web rendering and even ai context if the underlying data/functions haven't changed". Stated in [data modeling](data-modeling.md#derived-data-is-a-cache-never-relied-on).

### Caching is never an excuse for slow code: the cold path is fast on its own, and a hit is visible

"caching isn't an excuse for writing shitty code." "if it's fast enough we don't need caching then don't use caching." Its visible hit is superseded by the entry above. Stated in [cost](cost.md).

### Push pinned fork commits without asking

"You don't have to ask me to push public repos anymore." Stated in AGENTS.md §8 and [forks](forks.md).

### Fork URLs are the GitHub forks

`.gitmodules` names the forks at `seantempesta/*` over https (quote not recorded). Stated in [forks](forks.md).

### The project root is `~/src/seon-next`

Quote not recorded. Stated in [machine](machine.md) and [merging](merging.md#a-worktree-session-works-in-a-checkout-of-its-own).

### Cost is work done: ticks, bytes and calls, never wall time

Quote not recorded. Stated in [cost](cost.md).

### A session's prove runs the process tests it reaches (owner, 2026-11-08)

"Yes, run them early". Stated in [merging](merging.md#a-slow-test-runs-last).

### Process tests are not automatic: the reach walk classifies them

Superseded on 2026-10-27 by a merge runs the process tests its change reaches, automatically.

### Ids are derived from content; addressing is global

"content-derived ids; global addressing" (root's words). See 2026-11-08, an id is the content of the facts that make it, and 2026-11-11, ids are unique across branches.

### Tests run on change, through Seon

"I want the testing to happen on demand. When a change happens. When data flows through the system." "I want less useless runs." Stated in AGENTS.md §3 and [merging](merging.md#a-merge-runs-what-the-change-reaches).

### Renders are `(opts & children)` functions, Reagent's form, with auto-render

"I was thinking react so symbol opts children would be my guess?" Stated in [render](render.md).

### Thinking is off by default

Reversed 2026-11-12: [reasoning is on at low effort](#reasoning-is-on-at-low-effort-for-work-tasks).

### No automatic retries: a refused or failed run is held for root

"no automatic retries, send it to root to decide if it should be retried". Stated in [tasks](tasks.md#a-refused-run-is-held-for-root).

### The standing store is test data: erase it, no migration; history within a store is kept

"why are you migrating 6 facts? isn't it just test data just nuke it and move on". Stated in AGENTS.md §9, [machine](machine.md#disk-stays-bounded) and [storage](storage.md#history).

### Agents share greens: a test green at its digest never runs again

"tests that are the same (especially when freshly branched!) should not run again if they are already green". Stated in AGENTS.md §5 and [merging](merging.md#a-merge-runs-what-the-change-reaches).

### Output quality is decided by blind scoring on real data

"Those decisions should override any of my decisions. This is up to the agent really".

### The context speaks Clojure, with the agent's thinking beside its forms

"We should explain the task in terms of clojure code executing and thinking comments and encourage the agent to do the same."

### waste stays (`:seon.run/waste`); stuck went with the budget

Quote not recorded. Stated in AGENTS.md §7a.

### The dashboard stays in `src`

Quote not recorded.

### Worktree sessions are truly independent

"Each lane should be able to do anything they want to the codebase, fully test it and then we can shut it down and merge it. So true independent work". Stated in [merging](merging.md#a-worktree-session-works-in-a-checkout-of-its-own).

### Claude sessions use worktrees until Seon agents work inside the system; the end is Seon agents and git only for write-back

"I want worktrees for claude agents until we can get them working in seon itself." Stated in AGENTS.md §3.

### Long runs only when the work is done; otherwise run only what a change reaches

"I want faster development. Don't run long tests unless we are pretty sure it's done". Stated in AGENTS.md §3 and [source](source.md#tests-run-by-reach).

## 2026-10-22

The owner's rulings of this day, each with his words and where it is now stated.

### Root runs autonomously while there is good work

"I am authorizing you to run autonomously as long as you have good work to work on". Stated in AGENTS.md §2.

### One value printer, nothing cut before it; important data has declared `:ai` and `:html` renders

"Do not create more than one value printer and no clipping happens before then". Stated in [render](render.md#one-printer) and [render](render.md#no-clipping).

### A request's whole linked task set is one transaction on main, before any agent forks

"it's just one transaction with several maps". Stated in [tasks](tasks.md#a-request-is-one-transaction).

### Names are Clojure's, its libraries' or Jolt's

"stop using names that aren't clojure or clojure library or jolt or whatever related". Stored names change by erasing the store. Stated in AGENTS.md §7a and [vocabulary](vocabulary.md).

### Compiled units are not stored now; the idea stays open

"keep the idea open I just want to get to the point we are productive and have real seon agents fixing problems in the system". Stated in [storage](storage.md#compiled-code).

### A task edited on two live branches is a conflict for root (D4)

"This should never happen. If it does there should be a merge conflict and root will have to figure it out." Stated in [tasks](tasks.md#task-content-reaches-main-at-the-runs-end).

### A task's own checks live with the task, outside main's test namespaces (D5 (a))

He chose option (a); quote not recorded.

### Paid model runs: up to $50, productive runs only; known-wrong context is fixed first

"if the context is dog shit and we know it's fucked do not waste money or time just fix the shit we know is wrong first". Stated in AGENTS.md §8 and [model](model.md#paid-runs).

### One store holds the whole system; branches are experiments at every layer

Quote not recorded. Stated in AGENTS.md §§4 and 5 and [storage](storage.md).

### Our Datahike is Jolt-first, and storage is tuned for our workload

"one really good datahike with jvm specific features only in a jvm branch and jolt specific features as the main version going forward". Stated in [forks](forks.md) and [storage](storage.md).

### Agents work at the REPL through tool calls: no git, no source-file editing

"The point is to use the REPL and to not do file editing". Stated in [source](source.md#an-agent-changes-code-at-its-repl).

### Tasks are unrestricted; behaviour follows the data; a task's branch, model and config are derived along `:needs`

"tasks shouldn't be restricted in what they can do". Stated in AGENTS.md §4 and [tasks](tasks.md).

### A request becomes design, implement, merge; templates are held tasks in the task EDN; one proofs field; every agent gets `(help)` and its task

"No not a wiki. This is internal seon." The default request is one whole task (2026-10-26). Stated in [tasks](tasks.md#a-request-is-one-transaction) and [references](references.md#task-references).

### Tasks are extensible, with no explicit kinds (`:seon.task/kind` goes)

Quote not recorded. See 2026-11-07, no kinds.

### One agent entity: the run folds into `:seon.agent/*`

Quote not recorded. See 2026-10-30, there are no runs.

### RELOAD goes: `require` and `load-file` load as in Clojure and record nothing

Quote not recorded. Stated in [source](source.md#an-agent-changes-code-at-its-repl).

### Agents run only on a sound platform

"Don't run agents if we know that we have serious problems with our context or the task instructions or its functionality with auto running the tests." Stated in AGENTS.md §8.

## 2026-10-21

The owner's rulings of this day, each with his words and where it is now stated.

### No locks, no cap on worktree sessions, no waiting

"get rid of all of it". Stated in [merging](merging.md#main-goes-first).

### Every live run is better than the last; new real tasks, no rerun matrix; DeepSeek works every stage, measured

"we are NOT doing a matrix on the same run". Stated in AGENTS.md §1.

### The BUILD, SCHEMAS and PKG plans are approved; direct linking stays

"EDN for offline compiled into program and register at the repl and that can write to the edn if it's merged to main." Stated in [source](source.md#schemas-at-the-repl) and [source](source.md#dependencies).

### Minimal code: extend the existing systems

"If we are building shit we don't need to build and not using the existing systems or extending them for minimal code we are doing the wrong thing." Stated in AGENTS.md §3.

### Disk stays bounded; regenerable output is deleted

"just cleanup shit we aren't using anymore and build in rules so this doesn't get out of control". Stated in [machine](machine.md#disk-stays-bounded).

### Files, namespaces and symbols are plain Markdown, read by one reader; only Seon's own docs are stored

"This should be just like the markdown syntax and we should just understand what are files and what are namespaces and symbols." Stated in [references](references.md#files-and-namespaces-on-demand) and [references](references.md#the-wiki-in-the-store).

### Line citations keep v1's shape (a `path:N` is a file reference marked stale-prone)

Quote not recorded. Stated in [references](references.md#the-delimiter-picks-the-depth).

### `bin/root` restarts the next generation; a process does not own its replacement

He chose option a; quote not recorded. Stated in [source](source.md#the-next-generation).

### How we work: process, not whim; research, then a simple plan; learn into the wiki

"Make it so we don't always start from zero and keep redoing the same mistakes." Stated in AGENTS.md §§3 and 7.

### The goal is the recursive self-improvement loop, toward thousands of agents accreting good changes

"The recursive code self improvement loop is the goal." "I want thousands of running agents doing different tasks and focusing on each task." Stated in AGENTS.md §1.

### Loading code is an effect, typed at the edge

"Make anything like this an effect and we treat it as strongly typed at the edges." Stated in [flow](flow.md#effects-one-owner-and-its-workers-per-kind).

### Every definition happens once, where it is defined

"Why are we redefining things?" Stated in AGENTS.md §5 and [resolution](resolution.md).

### Programs are known from the compiler, not from text

"I don't want regex's I want to know exactly what something is so there are no hacks." Stated in AGENTS.md §9 and [source](source.md).

### Runs are kept, history and all; until there is real user data the standing store is test data (above)

"keep it so we have a perfect record frozen in time." Stated in [storage](storage.md#history).

### Agents stay forked processes, each named by role, task and checkout

"But fucking name the processes if they really have to be different." Stated in [processes](processes.md#the-agent-process).

### Work is admitted by its measured workload, through flows

"I don't want slots I want smarter systems where we understand the workload and they are flow based." Stated in AGENTS.md §4.

### Embeddings join the context: fused, small and measured

"If it's not working well for now leave them out but we haven't fully explored embeddings to drop them". Stated in [vector](vector.md).

### Patterns live in the topic sections, linked

"I want us to learn how we can detect patterns in our code and reuse these solutions". Stated in [the wiki's map](README.md#how-the-wiki-is-kept).

### If nothing has changed, we do nothing; no clock or timer decides (three exceptions)

"yeah no clocks or timers." A fourth exception came on 2026-11-10. Stated in AGENTS.md §4 and [clocks](clocks.md).

### A failure goes to whoever can fix it

"An fatal error needs to be addressed a runtime fix that can be applied needs the error to apply the fix." Stated in [errors](errors.md) and [failure points](failure-points.md).

### Only documented config

"the only things that should stay are documented config schemas that we are porting from seon if they are relevant." Stated in AGENTS.md §5 and [config](config.md#the-file).

### Branches as in production; a separate store is a last resort

"separate stores should be a last ditch effort". Stated in AGENTS.md §5.

### A merge carries the program and the agents' data, never platform records

Root's ruling, not the owner's; replaced by his of 2026-11-11, a sync merges everything that does not conflict.

### Durability is continuous group commit

"Why have an arbitrary 10ms collection point?" Stated in [storage](storage.md#durability-is-continuous-group-commit) and AGENTS.md §5.

## 2026-10-20

The owner's rulings of this day, each with his words and where it is now stated.

### Estimates are proofs on tasks

"How long should something really take." Stated in AGENTS.md §5 and [cost](cost.md).

### Scheduling is reconciliation over task attributes; no chain entity, no separate scheduler

Quote not recorded. Stated in AGENTS.md §4 and [tasks](tasks.md).

### Effects go over channels to their owner procs; whoever asked records the outcome

"IO and effects in general I think we may want to send over channels to dedicated processing flows." Stated in AGENTS.md §5.

### Flow is the one model: every component is a step-fn proc, declared in one file, ordered by dataflow

"No point in using flow if we are going to use it the wrong way." Stated in [flow](flow.md).

### Data-centric, always valid: contracts on every function

"make this the universal thing that we hone and fix." Stated in [checks](checks.md#contracts-armed-at-definition).

### Each agent writes its own branch, in its own process; root writes only main

"Agents just take care of their own shit if they are in different branches". Stated in [storage](storage.md#who-writes-a-branch).

### It's a REPL into a real session; branches are true branches, merged per definition, never replayed

"We should be able to create new namespaces period. Just like a REPL." Stated in [agent](agent.md#the-repl) and [source](source.md).

### Get out of the way: fixes at the producer, no guards; failure points are named

"I want thoughtful fixes not fences and guards." Stated in AGENTS.md §§4, 5 and 9 and [failure points](failure-points.md).

### The transition goal: `` replaces Seon, consistent, contracted, about 10k lines

"have everything be spec'ed and have only good tests using our test harness". Its line target is replaced by 2026-10-23, src resists growth. Stated in AGENTS.md §0.

### Everything on Jolt; libraries fixed properly, measured, with no extra caches

"if it's faster to not use the cache why cache at all?" Stated in [forks](forks.md).

### Renders attach to schemas; the UI and the context are renders, ported not reinvented

"Everything has a schema right so we say this renders this." Stated in [render](render.md).

### Agent context comes from the plan's references

"If we need to give agents more context we should do so in the plan". Stated in [references](references.md#task-references).

### One messaging system; the user is an agent

"one simple messaging system that works everywhere." Stated in [users](users.md#messages).

### Outside agents reach any agent or root through a thin MCP server

"outside agents need minimal MCP wrapping so they can repl in and run statements against any agent or as root." Stated in AGENTS.md §6 and [parity](parity.md#the-mcp-server).

## Open: does this still stand?

The owner's rulings of this day, each with his words and where it is now stated.

### Each agent is a proc in its own Flow graph, or agents are procs of the one root graph

"the agent is its own process so having the flow controls be directly hooked up seems like it's ideal." Left to measurement; which stands?

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
