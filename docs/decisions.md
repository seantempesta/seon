# Decisions

This file holds the owner's standing rulings, one entry each: the rule, the owner's words and date, and where the rule is stated now; current truth and mechanism live in `AGENTS.md` and the wiki (`docs/`), and superseded or done rulings are kept verbatim in notes/archive/DECISIONS-superseded.md.

## 2026-10-25 rulings
The owner's rulings of 2026-10-25, each with his words and where it is now stated.

### Message delivery: compare, then the better design or the simple one
Owner, 2026-10-25, on main-first delivery (agent loop) against delivery by merging a fact from the sender's branch (ctx-atom §11): "Launch a comparison. I would prefer real flow semantics where processes are independent, but not at the cost of stability and performance and complexity. We either have a better design or we do the simple one that works". Result (agent loop, 2026-10-25, design §3a 16066cc0b): main-first, with `:seon.run/taken`; 4.96 ms vs >=16.79 ms a delivery, the reconciler reads only main, no lost wakes in either over 2,000 schedules. Stated in: notes/design-agent-loop-2026-10-25.md §3a on ``jolt/agent-loop``.
### The related-section count is a config key; one problem-row helper's bounds accepted
Owner, 2026-10-25: approved one new config key, how many related wiki sections the screen shows (0 turns it off); accepted raising 5 derived-problem cost proofs' call bounds by one contracted call per row, for one helper in place of 8 copies. Stated in: this entry.
### Dogfood rulings: design registers proofs; each form's value; stale means meaning changed; pilot budget $5
Owner, 2026-10-25, on the dogfood findings (notes/dogfood-and-docs-2026-10-25.md on ``jolt/dogfood``): `my.task/update!` takes `:seon.task/proofs`, so a design stage registers the red proofs it writes; an eval call's response lists each form's value in order under the call's one handle (`result/eN` holds the last, like `*1`); a wiki section is stale when a cited var's contract, arglists or docstring changes, not its body; the pipeline pilot may spend up to $5 ("use up to $5 even I want to learn and fix the design"). Stated in: not yet.
### The printer has a generous budget; the fix for noise is not generating it and not rendering it
Owner, 2026-10-25, on the printer audit: "make sure the value printer is working good everywhere" and "And that it has a generous context budget. Id rather us focus on not generating crap and then not rendering crap." The printer's budget stays generous; noise is fixed at its producer first (it is not made or stored in that shape), then by not rendering it (no placeholder-per-row elisions, repeated paths, empty keys); truncation is the last resort. Stated in: not yet (session printer, ``jolt/printer-everywhere``).
### A change to a schema is an explicit fact the eval shows, never a hidden digest
Owner, 2026-10-25, on root's approval of a `:form/meaning` digest for the stale check: "I feel like this should be runtime eval that shows the change to the schema so they know about the change and learn how to update schemas. I want explcit changes in the system to modeled accurately". A contract or docstring change is already a transaction (`seon.program/def-tx` asserts and retracts `:var/contract` and `:var/doc`, history kept): the eval that makes it shows before → after and the sections citing the var; staleness reads those facts since the section's read t. The digest approval is withdrawn. Stated in: not yet (agent view r3 revising).
### Starting and resuming are one mechanism; replay is told and switchable; agents work in the UI
Owner, 2026-10-25: "we are building the same muscle here to load any session from any point in time too right? I don't want a separate system for running agents. We don't autoplay the things we know are effects and we inform the agent at every eval if it was replayed so they understand the env they are in ... I want a very 'real' environment for our agents with live feedback. Instruct agents to think about and use the UI and to get it working for me so I can follow along with experiments." And: "Optionally config to run everything even effects so it's easy to switch on (it's just a filter of the forms right?)". Starting an agent is loading a session at a point in time, the same path as resume; replayed evals are marked as replayed in what the agent sees; replaying effects is one config switch on the replay filter; agents' instructions say to use and improve the UI. Stated in: not yet.
### No new linking attributes where :needs and the record do it; everything survives resume
Owner, 2026-10-25, on the agent loop's proposed attributes: "What are these attributes doing? We have the :needs way of connecting the graph literally together ... Every agent should have a stable key of course that's essential. ... namespaces that 'survive' resume seems like the wrong idea -- of course everything should survive if we can resume it." A stable agent key: yes. Links between stages and sessions come from `:needs` and the record, not new attributes; resume restores everything. The design re-justifies each remaining attribute or drops it. Stated in: not yet.
### Messaging is my.agent/message!
Owner, 2026-10-25: chose `my.agent/message!`. Stated in: not yet.
### No restrictions; any agent can be root; agents edit their own dynamic forms
Owner, 2026-10-25: "No restrictions right now. I want to focus on not building separate systems for everything. All agents can be root. Root is just a name we give them and I think we'll need multiple ones responding to different things so we have their id be generated just the same. The task can just be instructions that say 'you are root running on main etc' ... I think we should have a way for agents to edit what forms are dynamically being run each turn so they can easily self modify those too". Any agent may release a held task; root is an agent with root's instructions on main, id generated like any agent's, and several may run; an agent can change its own per-turn (screen) forms. Stated in: not yet.
### A request's stages are separate steps in one session
Owner, 2026-10-25: "I want the investigation and implementation and merging steps to be separate steps so we can instruct the agent through some reliability steps but their previous context will be available so I do want the context to transfer (just have it be the same session right?)". Design, implement and merge stay separate tasks with their own instructions and checks, worked in one session: the next stage continues the same conversation on the same branch. Also: "Errors can be customized again ... Maybe that is a good easy task to investigate?" A candidate first request for the pipeline. Stated in: not yet (owed the agent-loop design).
### Every agent parks; resume replays pure forms and asks for effects
Owner, 2026-10-25: chose "Always park" (measured: a parked agent is 1 thread, 0% CPU, wakes in 7-35 ms; 47-300 MB each) and "Auto-replay pure, ask for effects". An agent parks until its task finishes. On resume, effect-free forms are evaluated again automatically and effectful ones (sends, writes, spawns) are listed for the user's approval; every eval records the functions it called so effects are known, not guessed; each task's agent namespace is stable, so forms never name an agent id. Stated in: not yet.
### A session continues across turns and resumes; its context is generated from the db at a commit
Owner, 2026-10-25: "a resume where the agent wakes and responds to a message it should have the same previous context ... If a 'run' is too tightly coupled and we need to decouple the concept of N turns then lets design something to address this. We want to be able to resume any session which means continuing and not losing context. Again we should not be saving context and piecing it together we should be generating context from the db at a commit point and it should be immutable (except for the dynamic parts and the current eval)". The conversation an agent sees is its whole session, generated from the db at a commit point, never stitched from saved pieces; a turn budget is not what bounds a session. Stated in: not yet (the loop design owes it).
### Root's special cases go, all four
Owner, 2026-10-25: chose all four of the one-system audit's first items: root as an agent on main (no unbounded root eval), delete the dead merge path, root's work as tasks (merge conflict, dead agent, health, red proofs, update) run on main's current commit, and one prove path. Stated in: not yet.
### Agents stay running, parked, between messages; any commit can be resumed
Owner, 2026-10-25: "Maybe we should just keep the forks running and just parked. Flow can do this so do this and we don't have to worry about restarting. I still want a resume that can take any transaction commit point and we can get an agent back to the mostly same env even if it means re-evaluating the forms (I think if user approved this is fine)." A live agent's process parks on its input (Flow) when it has nothing to do and wakes on a message; a separate resume takes any commit point of a branch and brings an agent back to nearly the same environment: its conversation from the db, its data results from the store, and, with the user's approval, its forms evaluated again. Stated in: not yet (owed docs/processes.md and docs/flow.md with the build).
### Correctness and performance come before lines of code
Owner, 2026-10-25: "prioritize correctness and perf over lines of code. Lines of code is not my main metric". A change is judged first on whether it is right and on its measured work (ticks, bytes, turns to green); a smaller diff never wins over a correct or faster one. Resistance to growth (2026-10-23) still means reusing and improving the core rather than adding one-offs. Stated in: AGENTS §1 (owed).
### Root is main; everything else is the same system with different config
Owner, 2026-10-25: "Root is main and is in charge with keeping main stable and working well. Agents can explore different ideas safely in their own forked processes ... and then we merge to main via the same task system. If a merge fails root needs a root specific merge task ... other tasks for root to handle like when agents die or health checks fail. All of these I want to run on main CURRENT ... we need to refactor to make this possible not build a separate system for root. It's one system where we use data and flows to have a runtime, db, and agent running against a task. Who and what they are doing on what branch they are is all just config options." Root's own work (a failed merge, a dead agent, a failing health check) is tasks run by agents on main's current commit; root is not a separate code path. Stated in: not yet (owed AGENTS §4 and docs after the one-system audit).
### Agents restart from their record; the lifecycle is Flow's
Owner, 2026-10-25: "it's only 0.08s to start a fresh agent and we can resume the context since it's immutable and generatable from any T id? Hell yes. Wake the agent once it's up ... Can we just have the flow step function and the lifecycle handle the forking and killing?" A message wakes or restarts an agent; how, built on Flow's step-fn and lifecycle, is being researched concretely (sources, then REPL experiments, then an Opus design and build) before it is built. Stated in: not yet.
### The screen is sent as diffs between turns
Owner, 2026-10-25: chose "Diffs between turns". `(seon.screen/show {:since t})` answers what changed since the agent's last screen at db t, the unchanged sections named; the first screen of a run is whole; a section about to be cut by the history budget is sent whole again. Stated in: not yet (owed the design note and docs/context.md).
### Merge the agent-view branch to main once its review is done
Owner, 2026-10-25: "873 lines isn't that much if you think it's good lets merge to main. ... I want to see the web work too so if merging to main makes that easier lets do it". Stated in: this entry.

## 2026-10-24 rulings

The owner's rulings of 2026-10-24, each with his words and where it is now stated.

### A failed model call keeps what it sent and its typed error
Owner, 2026-10-24: "Approve :seon.turn/error." A turn is written with its sent refs as the request goes; when the call ends it gains its reply and usage, or `:seon.turn/error` (the typed error as data, as the run's end has `:seon.end/error`). One turn entity either way. Stated in: not yet (owed docs/context.md with step 3).
### A turn records what it sent as refs; texts are read as of the turn
Owner, 2026-10-24: "Approve :seon.turn/sent only; refer to the system prompt's wiki section and the task entity as of the turn's t instead of new :seon.text attributes." One new attribute, `:seon.turn/sent`: the ordered refs to what the turn's request carried (its evals, messages, the system prompt's wiki section and the task entity), resolved as of the turn's t (history is kept); no separate text store. Stated in: not yet (owed the design note §3 and docs/context.md with step 3).
### A config key does what it says; a provider's schema refuses a combination it forbids
Owner, 2026-10-24: "have the config do what it says and if there's a combo that's not allowed we can just have a schema validation for deepseek that confirms the right settings are set". Whether earlier reasoning is sent back is a config key (resolved task -> model -> provider -> config) that is obeyed as written; a provider's config schema (Malli, under `seon.config.*`) refuses a combination the provider rejects (DeepSeek's docs: thinking on with tools requires all earlier reasoning sent back), so a bad setting is an invalid config at load, explained, never a silent override. Stated in: not yet (owed resources/seon/config/default.edn and docs/model.md with the agent-conversation work).
### The agent view and context design is approved
Owner, 2026-10-24: "Approve the design as written, including the owner items in section 11." notes/design-agent-view-2026-10-24.md (revision 2, commit 2067be8df): the conversation is function-calling messages of evals and responses; the screen is evals run before each request and replaced each time; views are plain HTML hiccup rendered once per set of reads and shared; reads recorded by Datahike at its index. Section 11: root renders every browser view and agents commit after each eval; new attributes `:seon.screen/*` and a user agent entity; a prose-only reply ends the turn and waits for the user; paid runs of about $1-2 for steps 0-2; the standing store is erased when the record changes land. Built in that order, steps 0-2 first with numbers before step 3. Stated in: the design note (owed docs/render.md and docs/context.md as it merges).
### Names tell a story; the printer shows every value well
Owner, 2026-10-24: "Beautiful naming and the render tree should tell a story and not just be full of random words" and "We can adjust the value printer too as we want a general printer which shows all values well". Records and views are named by what they are, in Clojure's, nREPL's, Reagent's or the chat API's words (an agent's conversation of turns, each eval's response, the user's screen); a fix to how a value prints goes in the one printer, for every value. Stated in: notes/design-agent-view-2026-10-24.md "Names" (owed AGENTS §7a when it merges).
### A prove run is keyed by the digest of what it reads
Owner, 2026-10-24: "okay lets try the digest". The test service's run key (`seon.prove/run-key`) names the digest of the code and tests the run reaches, as each test's result is already keyed (AGENTS §5), not `branch@t`: a write to main that touches none of it never starts a second run. Stated in: not yet (owed docs/merging.md#the-test-service, task seon.proofs/a-prove-runs-key-does-not-move-between-two-asks).
### Root merges verified fixes as they come in
Owner, 2026-10-24: "merge fixes as they come in". A fix branch whose report carries its numbers, its self-review rating and a green bin/prove is merged by root without asking (bin/prove and smoke on the merged tree, then bin/update); a branch that adds a namespace, config key, schema attribute, proc or route still needs the owner's gate. Stated in: AGENTS §2, root's working rules (owed).
### One render per schema, declared where the schema is registered
Owner, 2026-10-24: "Okay I'm willing to try a single render function -- so it's specified during the register! for the malli schema?" and "If we can nail a compositional design and the function naming the hiccup could be extremely readable and concise to build complex frontends (this is important for me)… if it hasn't changed we aren't re-rendering". A schema names one render function (`:seon.render/fn`, set by `my.schema/register!` or the schema resources), replacing the two faces `:seon.render/html` and `:seon.render/ai`; views compose as hiccup (`[f props & children]` calls f; plain data renders by its schema; the db is passed when the function's contract asks for it); an unchanged value is not rendered again. Views are plain HTML hiccup with Tailwind classes, function symbols and data, with no house set of special tags or symbols (owner, 2026-10-24: "I want less hardcoding and special symbols and more easy to compose things together with function symbols or data and normal tailwind css and html"). Stated in: not yet (owed docs/render.md and its task).
### The debug view shows exactly what each turn was sent, styled, with where each part came from
Owner, 2026-10-24: "Yes in terms of content, but I would like it styled and clear to read. Maybe columns and we can show the form and eval result that created it or can explain the rendering?" The content a model call was sent is recorded, so the view reproduces it exactly; it is shown styled, beside the form, eval or render that made each part. Stated in: not yet (owed docs/render.md and THE PLAN step 3).

## Tonight's rulings (2026-10-23, with the 2026-10-21/22 rulings they rest on)

The owner's rulings of 2026-10-23, with the rulings of 2026-10-21 and 22 they rest on, each with his words and where it is now stated.

### Learn from earlier work, then write it clean; remove a worktree once it holds nothing unsaved
Owner, 2026-10-23: "don't forget to clean up the old work trees once they aren't needed and don't throw away good work have agents learn from it if you want a clean implementation. No crappy slow repetitive code." Stated in: AGENTS §9.
### Reuse every unique, immutable result that is proven the same and expensive to make
Owner, 2026-10-23: "I want reuse of all unique and immutable data we are sure is the same and is expensive to render". A pure computation's result is reused wherever its inputs are the same by content: keyed by the digest of exactly what it reads (content ids, the reach walk's digests), shared across branches, sessions and worktrees, and only for units measured as expensive (AGENTS §5). Anything that reads the clock, the network or a model, or launches a process, is not reused; the reach walk classifies it. One mechanism serves every such result, in `src`, `test` or `bin`, and it replaces the per-case caches it covers. Stated in: not yet (owed AGENTS §5 and the section of the task one-content-keyed-derivation (on ``jolt/one-content-keyed-derivation``, not merged yet)).
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
Owner, 2026-10-23: "don't just port. We moved away from text output too since the renders kept doing weird shit and it's out of context. Data is better so just improve the data so relveant info is there and you can also calculate additional info from it or rewrite it completely". What a model reads is Clojure data: each value carries what is relevant, derived values computed from it are added as data, and a value that isn't fit is reshaped or rewritten at its producer. v1's renders are a reference, not a template. Owner, same day: "The one value printer is still printing it so render functions need to think about how to properly frame their output so it isn't clipped. The value printer needs to remain general so improve it for the general case not one offs". A render shapes its data so that what matters survives the printer's budget (the important keys first, long parts as their own values); the printer improves only for the general case, never with a special case for one kind of value. Stated in: not yet (owed docs/render.md and seon.context/evaluations' task).
### Sessions research on their own; a new session needs root's approval, unless the owner says otherwise
Owner, 2026-10-23: "sessions can do web research with haiku I think on their own and should ask you for approval before launching larger sessions. I want to be able to instruct any agent to override that". Stated in: AGENTS §2.
### The embedding work goes ahead, the model download included
Owner, 2026-10-23: "yeah I approved the embedding work" (in agent-view's thread; relayed by root). Settles the open EMBED question: the embedding model is downloaded and run in-process for `similar`, keyed by each form's content id. Stated in: not yet (owed docs/parity.md and the task seon.context/similar-finds-helpers-by-embedding, on its branch).
### No live agent runs until the context has a format agents respond well to
Owner, 2026-10-23: "I think the current agent context isn't great. I'm not sure how helpful it is to run live agent runs until we get a sane format that agents respond well too". Paid end-to-end runs pause. The context's format is rewritten (agent-view's pull-and-walk render) and judged by its first move: real task contexts, one model call each, thinking off, scored blind, until a model acts on turn 1. Live runs resume after that, on a new real task. Stated in: docs/plan.md (THE PLAN step 1); extends "Agents run only on a sound platform" (2026-10-22).
### The reuse design is approved: one memo keyed by what the process runs, for the walk's renders first
Owner, 2026-10-23: "Approved: build the revised reuse design, walk node renders only, with its proofs." The design is notes/reuse/design.md at 17ed5728 (``jolt/one-content-keyed-derivation``), after the independent attack (notes/reuse/attack-2026-10-23.md): the key is a full SHA-256 of the boot digest (the build plus the config in force), the digest of everything loaded since boot, and the args' canonical EDN; non-EDN and rebound dynamics are refused; eviction is deterministic. Stated in: not yet (owed AGENTS §5 at its merge).
### bin/test branches a kept program db per git tree (option B); each worktree's root is the end state
Owner, 2026-10-23: chose option B of notes/test-fork-main-2026-10-23.md. One durable test store per checkout holds a branch per git tree id (`C^{tree}`, content only); a run branches from the kept tree with the fewest changed files and applies that diff with `seon.source/refresh-tx`, never a whole re-import. Option A (each worktree's own root answers bin/test through seon_prove) stays the end state. Stated in: not yet (owed docs/source.md#tests-run-by-reach and the task).
### Reuse is memoization: `(= (f x) (lookup f x))`, so return it
Owner, 2026-10-23: "It's simpler than you think. (= (fn-a x) (cached-lookup `fn-a x)) they are literally the same so just return it". A pure function applied to the same immutable value gives the same result, so the one mechanism is a lookup keyed by the function (its var and the digest of its code and what it reaches) and the value it is given (its content). On a hit it returns the result, and nothing else changes. Owner, same day: "we shouldn't be caching on a key that needs to be updated. So key selection and however we are implementing this needs to be solid". A key is made only of content: a digest of the function's code and what it reaches, and of the argument's immutable value (a db value by its commit's content, never a branch name, a var name alone, a path or a time). So a key never goes stale and nothing is invalidated: a change makes a new key. Proved both ways: every change to the code or the input changes the key, and no other change does. Stated in: not yet (owed AGENTS §5 and the reuse task).
### Reuse is transparent and simple: a reused value is shown as it is, never marked
Owner, 2026-10-23: "I wanted transparent and simple caching. The whole reuse thing is making me rethink showing it to the agent. Let's just show it as is if it's cached and don't state whether its generated or cached or not." A reused render, context or result reads exactly like a freshly made one: no reused/computed field in an eval's reply, a turn's record or the context. Its effect shows only in the cost proofs and in work-done facts recorded for the owner's views. Owner, same day, corrected: "the model should get cost numbers in the context. I just want them stable across turns in the run so we don't bust the cache. We want to get them used to seeing how expensive things are and so when they get warnings or limits it's easy to understand where they stand". Each eval's cost (ticks, bytes, calls, time) is shown with it, rendered from its recorded fact, so it never changes after the turn it happened in; what moves (the run's totals against its budget) is shown only in the turn's tail, after the cached prefix. Supersedes the hit recording of "Caching is never an excuse for slow code" below. Stated in: not yet (owed AGENTS §5 and the reuse task).
### Everything is fast; a render or a context whose inputs have not changed is never made again
Owner, 2026-10-23: "In general I want everything to just be fast, but something are just dumb to not cache like web rendering and even ai context if the underlying data/functions haven't changed". The cold path is fast first (below), and some results are reused even when they are fast, because making them again is pure waste: a web render and the model's context are reused while the data they read (content ids) and the functions that make them (reach digests) are unchanged. Stated in: not yet (owed AGENTS §5 and the task one-content-keyed-derivation (on ``jolt/one-content-keyed-derivation``, not merged yet)).
### Caching is never an excuse for slow code: the cold path is fast on its own, and a hit is visible
Owner, 2026-10-23: "caching isn't an excuse for writing shitty code. If the cold version (can we return whether there was a cache hit in repl evals?) is fuckign slow as shit it's still not okay". A reused result's computation carries its own cost proof, measured cold (no reuse), at the work it needs; reuse is added on top, never instead. Owner, same day: "if it's fast enough we don't need caching then don't use caching." Reuse is added only where the fixed cold path is still measured as expensive; a cold path that is fast enough gets none. (Its hit recording, "a REPL eval's result says which results it reused", is superseded by the entry above: reuse is transparent.) Stated in: not yet (owed AGENTS §5, beside "Results are reused by input digest", and the task one-content-keyed-derivation (on ``jolt/one-content-keyed-derivation``, not merged yet)).
### Push pinned fork commits without asking
Owner, 2026-10-23: "You don't have to ask me to push public repos anymore." A session pushes the fork commits its branch pins to ``seantempesta/<lib>`` on a ``jolt/<topic>`` branch: no force, never a fork's main. Stated in: AGENTS §8; docs/forks.md.
### Fork URLs are the GitHub forks
Owner, 2026-10-23: quote not recorded; relayed by root. `.gitmodules` names the forks at `seantempesta/*` over https, not a local path or the module store. Stated in: docs/forks.md (owed its rewrite when the URLs change).
### The project root is `~/src/seon-next`; worktrees live inside it at `.worktrees/NAME`; nothing refers to the old prototype folder
Owner, 2026-10-23: quote not recorded; relayed by root. The root is a fresh clone with its history kept (the outer repo's history is the tag `history/prototype`), and `next` stops reaching outside itself: the plan is `notes/self-contained-plan-2026-10-23.md`. Stated in: docs/machine.md (what lives in the root and beside it), docs/merging.md#a-worktree-session-works-in-a-checkout-of-its-own, and `bin/worktree --path NAME`.
### Cost is work done: ticks, bytes and calls, never wall time
Owner, 2026-10-23: quote not recorded; relayed by root. Stated in: docs/cost.md (ticks, bytes and calls are exact; wall time is not a measure).
### Process tests are not automatic: the reach walk classifies them
Owner, 2026-10-23: quote not recorded; relayed by root. A test that launches a binary is deferred by what the reach walk finds, not run on every change. Stated in: docs/turn.md#who-runs-which-tests; docs/merging.md#a-merge-runs-what-the-change-reaches.
### Ids are derived from content; addressing is global
Owner, 2026-10-23: quote not recorded; relayed by root ("content-derived ids; global addressing"). Stated in: not yet (owed docs/storage.md, with the ``jolt/global-ids`` branch).
### Tests run on change, through Seon
Owner, 2026-10-23: "I want the testing to happen on demand. When a change happens. When data flows through the system." Root's file watcher sees a change and runs the tests the change reaches at the new digest, reusing greens and recording results as facts (``jolt/health``). Until it lands, `bin/prove` once per finished step, never after each edit, no full runs without root's go ("I want less useless runs. Agents are slowing everything down doing full checks after every little change."). Stated in: AGENTS §3 (Testing); docs/merging.md#a-merge-runs-what-the-change-reaches.
### Renders are `(value opts)` functions with auto-render
Owner, 2026-10-23: quote not recorded; relayed by root. Stated in: not yet (owed docs/render.md, with the ``jolt/printer-eval`` and renders branches).
### Thinking is off by default
Owner, 2026-10-21, restated 2026-10-23: "if we can't have intuitive function calling based on the context our context is bad and instructions need help". Stated in: docs/model.md#thinking-is-off-by-default; `:seon.config.ai/thinking` in `resources/seon/config/default.edn`.
### No automatic retries: a refused or failed run is held for root
Owner, 2026-10-23: "no automatic retries, send it to root to decide if it should be retried" (and 2026-10-22: "We need a way to mark the task as failed and root should be notified"). Stated in: docs/tasks.md#a-refused-run-is-held-for-root.
### The standing store is test data: erase it, no migration; history within a store is kept
Owner, 2026-10-23: "why are you migrating 6 facts? isn't it just test data just nuke it and move on". Stated in: AGENTS §9 (the exception to "Runs are kept"); docs/machine.md#disk-stays-bounded; docs/storage.md#history (history kept).
### Agents share greens: a test green at its digest never runs again
Owner, 2026-10-23: "tests that are the same (especially when freshly branched!) should not run again if they are already green". Stated in: AGENTS §5 (results reused by input digest); docs/merging.md#a-merge-runs-what-the-change-reaches (agents' greens are shared once ``jolt/brief3`` merges).
### Output quality is decided by blind scoring on real data
Owner, 2026-10-23: "Those decisions should override any of my decisions. This is up to the agent really". Stated in: docs/render.md#one-printer (the scored result goes there; not yet).
### The context speaks Clojure, with the agent's thinking beside its forms
Owner, 2026-10-23: "We should explain the task in terms of clojure code executing and thinking comments and encourage the agent to do the same." Stated in: no section yet; owed to docs/render.md#the-models-context-a-cached-prefix-then-the-turn and docs/agent.md#your-turn.
### waste stays (`:seon.run/waste`); stuck went with the budget
Owner, 2026-10-23: quote not recorded; from root's note "Owner answers, 2026-10-23 morning" (archive) and the triage's C5. Stated in: AGENTS §7a ("Waste stays our word"; the stuck row is gone).
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
Owner, 2026-10-22: quote not recorded. Stated in: AGENTS §4 (A value), §5 (One store, many branches); docs/storage.md.
### Our Datahike is Jolt-first, and storage is tuned for our workload
Owner, 2026-10-22: "one really good datahike with jvm specific features only in a jvm branch and jolt specific features as the main version going forward". Stated in: docs/forks.md; docs/storage.md.
### Agents work at the REPL through tool calls: no git, no source-file editing
Owner, 2026-10-22: "Agents shouldn't be editing their own source code files directly. The point is to use the REPL and to not do file editing". Stated in: docs/source.md#an-agent-changes-code-at-its-repl; docs/agent.md#the-repl.
### Tasks are unrestricted; behaviour follows the data; a task's branch, model and config are derived along `:needs`
Owner, 2026-10-22: "tasks shouldn't be restricted in what they can do". Stated in: AGENTS §4 (A task). Proof protection is deferred.
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
Owner, 2026-10-21: "get rid of all of it". Stated in: AGENTS §3 (Every ready task runs now).
### Every live run is better than the last; new real tasks, no rerun matrix; DeepSeek works every stage, measured
Owner, 2026-10-21: "we are NOT doing a matrix on the same run". Stated in: AGENTS §1 (turns to green, tasks merged).
### The BUILD, SCHEMAS and PKG plans are approved; direct linking stays
Owner, 2026-10-21: "EDN for offline compiled into program and register at the repl and that can write to the edn if it's merged to main." Stated in: docs/source.md#a-merge-loads-in-place; docs/source.md#schemas-at-the-repl; docs/source.md#dependencies; docs/source.md#shared-build-caches.
### Minimal code: extend the existing systems
Owner, 2026-10-21: "If we are building shit we don't need to build and not using the existing systems or extending them for minimal code we are doing the wrong thing." Stated in: AGENTS §3 (Integrate small and often); docs/merging.md#every-merges-src-diff-stat-is-read.
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
Owner, 2026-10-21: "Make anything like this an effect and we treat it as strongly typed at the edges." Stated in: AGENTS §5 (Effects go through their owner).
### Every definition happens once, where it is defined
Owner, 2026-10-21: "Why are we redefining things?" Stated in: AGENTS §5; docs/resolution.md.
### Programs are known from the compiler, not from text
Owner, 2026-10-21: "I don't want regex's I want to know exactly what something is so there are no hacks." Stated in: AGENTS §9; docs/source.md.
### Runs are kept, history and all; until there is real user data the standing store is test data (above)
Owner, 2026-10-21: "keep it so we have a perfect record frozen in time." Stated in: AGENTS §9; docs/storage.md#history.
### Agents stay forked processes, each named by role, task and checkout
Owner, 2026-10-21: "But fucking name the processes if they really have to be different." Stated in: AGENTS §4 (A process).
### Work is admitted by its measured workload, through flows
Owner, 2026-10-21: "I don't want slots I want smarter systems where we understand the workload and they are flow based." Stated in: AGENTS §4.
### Embeddings join the context: fused, small and measured
Owner, 2026-10-21: quote not recorded; 2026-10-23: "We can build bindings to some fast C library for running it ourselves right? maybe as a flow so it's just processing requests". 2026-10-24: "keep embeddings in the context; bring a measured version back in 4d", then "If it's not working well for now leave them out but we haven't fully explored embeddings to drop them": a small embedding-found list joins the task story only while measurement shows it helps; the embedding machinery (seon.vector, its search and config) stays for exploration either way. Stated in: docs/vector.md.
### Patterns live in the topic sections, linked
Owner, 2026-10-21: "I want us to learn how we can detect patterns in our code and reuse these solutions". Stated in: docs/README.md#how-the-wiki-is-kept.
### If nothing has changed, we do nothing; no clock or timer decides (three exceptions)
Owner, 2026-10-21: "yeah no clocks or timers." Stated in: AGENTS §4; docs/clocks.md.
### A failure goes to whoever can fix it
Owner, 2026-10-21: "An fatal error needs to be addressed a runtime fix that can be applied needs the error to apply the fix." Stated in: AGENTS §4 (An error is data); docs/failure-points.md.
### Only documented config
Owner, 2026-10-21: "the only things that should stay are documented config schemas that we are porting from seon if they are relevant." Stated in: AGENTS §5; docs/config.md#the-file.
### Branches as in production; a separate store is a last resort
Owner, 2026-10-21: "separate stores should be a last ditch effort". Stated in: AGENTS §5.
### A merge carries the program and the agents' data, never platform records
Root, 2026-10-21 (reversible): no owner quote. Stated in: docs/tasks.md#task-content-reaches-main-at-the-runs-end; docs/config.md#facts-on-main.
### Durability is continuous group commit
Owner, 2026-10-21: "Why have an arbitrary 10ms collection point?" Stated in: AGENTS §5.

## 2026-10-20

The owner's rulings of 2026-10-20, each with his words and where it is now stated.

### Estimates are proofs on tasks
Owner, 2026-10-20: "How long should something really take." Stated in: AGENTS §5; docs/cost.md.
### Scheduling is reconciliation over task attributes; no chain entity, no separate scheduler
Owner, 2026-10-20: quote not recorded. Stated in: AGENTS §4 (A task).
### Effects go over channels to their owner procs; whoever asked records the outcome
Owner, 2026-10-20: "IO and effects in general I think we may want to send over channels to dedicated processing flows." Stated in: AGENTS §5.
### Flow is the one model: every component is a step-fn proc, declared in one file, ordered by dataflow
Owner, 2026-10-20: "No point in using flow if we are going to use it the wrong way." Stated in: AGENTS §4, §5; docs/flow.md.
### Data-centric, always valid: contracts on every function
Owner, 2026-10-20: "make this the universal thing that we hone and fix." Stated in: AGENTS §5.
### Each agent writes its own branch, in its own process; root writes only main
Owner, 2026-10-20: "Agents just take care of their own shit if they are in different branches". Stated in: AGENTS §4 (A value); docs/storage.md.
### It's a REPL into a real session; branches are true branches, merged per definition, never replayed
Owner, 2026-10-20: "We should be able to create new namespaces period. Just like a REPL." Stated in: docs/agent.md#the-repl; docs/source.md.
### Get out of the way: fixes at the producer, no guards; failure points are named
Owner, 2026-10-20: "I want thoughtful fixes not fences and guards." Stated in: AGENTS §4, §5 (No guards), §9; docs/failure-points.md.
### The transition goal: `` replaces Seon, consistent, contracted, about 10k lines
Owner, 2026-10-20: "have everything be spec'ed and have only good tests using our test harness". Stated in: AGENTS §0, §1.
### Everything on Jolt; libraries fixed properly, measured, with no extra caches
Owner, 2026-10-20: "if it's faster to not use the cache why cache at all?" Stated in: AGENTS §5 (no other caches); docs/forks.md.
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
- What is a stall once an eval is bounded by its work: three tasks (`seon.root/a-stall-is-past-the-requests-own-bound`, `seon.root/one-clock`, `seon.turn/a-form-has-a-time-bound`) still plan a clock; `seon.agent-proc/stall-ms` (30000) remains.
- The per-run turn cap: lower `:seon.config.agent/max-turns` (40) to about 2x the measured p90 turns-to-green once 20 real tasks give one.
- A dropped model connection (`:seon.model/transport`): retry once after a wait, or end as now.
- File and shell roots: a config key with a permissive default, or none; and whether `:git` `:build` `:deps` `:source` effects are root-only.
- A `jolt/<topic>` fork of `jolt-lang/ring-chez-adapter` (needed by the `seon.web` listener-as-fiber task).
- Upstream: when to push the `upstream/*` Jolt branches and open PRs, and whether to send the Chez 0006 report and a 0007 issue.
- Profiling tooling: which to build (Jolt symbol map per generation for `sample`/xctrace; per-op LMDB counts; a `bin/check profile` mode; `mdb_stat` from `bin/build lmdb`).
- A go for the lmdb-ffi helper reading `mti_semid` that the semaphore-set task needs (a dependency change).
- Root's external channels (`:to-writer`, `:back`, `:ext`, `:events`, passed as `:bind`) as declared Flow `:conns`?
- The render proc's render-once caches (`:event-rows`, the zoom history's `:rows`) as units with a `:container`?
- Do `seon.cost` and `seon.profile` belong in the release binary or with the drivers?
