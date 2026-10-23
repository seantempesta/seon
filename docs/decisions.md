# Decisions

This file holds the owner's standing rulings, one entry each: the rule, the owner's words and date, and where the rule is stated now; current truth and mechanism live in `AGENTS.md` and the wiki (`docs/`), and superseded or done rulings are kept verbatim in notes/archive/DECISIONS-superseded.md.

## Tonight's rulings (2026-10-23, with the 2026-10-21/22 rulings they rest on)
### Reuse every unique, immutable result that is proven the same and expensive to make
Owner, 2026-10-23: "I want reuse of all unique and immutable data we are sure is the same and is expensive to render". A pure computation's result is reused wherever its inputs are the same by content: keyed by the digest of exactly what it reads (content ids, the reach walk's digests), shared across branches, sessions and worktrees, and only for units measured as expensive (AGENTS §5). Anything that reads the clock, the network or a model, or launches a process, is not reused; the reach walk classifies it. One mechanism serves every such result, in `src`, `test` or `bin`, and it replaces the per-case caches it covers. Stated in: not yet (owed AGENTS §5 and the section of task `seon.derive/one-content-keyed-derivation`).
### Caching is never an excuse for slow code: the cold path is fast on its own, and a hit is visible
Owner, 2026-10-23: "caching isn't an excuse for writing shitty code. If the cold version (can we return whether there was a cache hit in repl evals?) is fuckign slow as shit it's still not okay". A reused result's computation carries its own cost proof, measured cold (no reuse), at the work it needs; reuse is added on top, never instead. Owner, same day: "if it's fast enough we don't need caching then don't use caching." Reuse is added only where the fixed cold path is still measured as expensive; a cold path that is fast enough gets none. Every reuse is recorded where it happens: a REPL eval's result says which results it reused and which it computed, and the cost of each miss. Stated in: not yet (owed AGENTS §5, beside "Results are reused by input digest", and task `seon.derive/one-content-keyed-derivation`).
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
Owner, 2026-10-21: quote not recorded. Stated in: docs/parity.md#the-agents-repl-my (decided, not built).
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
### Each agent is a proc in its own Flow graph, or agents are procs of the one root graph
Owner, 2026-10-20: "the agent is its own process so having the flow controls be directly hooked up seems like it's ideal." Per-agent graphs, the leaning and root's "Assembly choices" (archive) were left to measurement; which stands?

## Open owner questions (pending rulings; the plan's blockers are also in plan.md)
- D6 names for the merge model: `:seon.task/carry`, `:seon.task/source`, `:seon.task/target`, `:seon.merge/kept`, `:seon.tx/changed`.
- D7 task config: keep the config keys as top-level task attributes (today `seon.task/create-tx` merges `:seon.task/config` into them and `d/pull` of the key throws) or a real `:seon.task/config` attribute.
- What a merge carries and who wins a conflict: the whole branch with main winning (`:seon.merge/kept`, v1 MERGE-STEP 5a) or an explicit `:seon.task/carry` list; parity.md still says "ours is kept".
- Changed-since: a Datahike tree diff by node address (port v1 fork `771a0c4b`, prototype first) or a `:seon.tx/changed` stamp against the "no stamps" ruling.
- Proofs along `:needs`: keep `seon.task/accumulate` (2026-10-20 ruling) or never carry proofs, since an accumulated designed-red check blocks implement (DESIGN-tasks §3.4, §11 #14); possibly moot once templates become held task data.
- No progress: is a byte-identical repeated reply a recorded fact only, or does it end the run.
- What is a stall once an eval is bounded by its work: three tasks (`seon.audit/a-stall-is-past-the-requests-own-bound`, `seon.root/one-clock`, `seon.turn/a-form-has-a-time-bound`) still plan a clock; `seon.agent-proc/stall-ms` (30000) remains.
- The per-run turn cap: lower `:seon.config.agent/max-turns` (40) to about 2x the measured p90 turns-to-green once 20 real tasks give one.
- A dropped model connection (`:seon.model/transport`): retry once after a wait, or end as now.
- File and shell roots: a config key with a permissive default, or none; and whether `:git` `:build` `:deps` `:source` effects are root-only.
- EMBED: allow the embedding model download (v1's EmbeddingGemma server) for `similar` and `:found` RRF.
- A `jolt/<topic>` fork of `jolt-lang/ring-chez-adapter` (needed by the `seon.web` listener-as-fiber task).
- Upstream: when to push the `upstream/*` Jolt branches and open PRs, and whether to send the Chez 0006 report and a 0007 issue.
- Profiling tooling: which to build (Jolt symbol map per generation for `sample`/xctrace; per-op LMDB counts; a `bin/check profile` mode; `mdb_stat` from `bin/build lmdb`).
- A go for the lmdb-ffi helper reading `mti_semid` that the semaphore-set task needs (a dependency change).
- `seon.audit.edn`: nine entries read "Done:" yet load as tasks; move the open ones to their owning files and delete the file?
- Root's external channels (`:to-writer`, `:back`, `:ext`, `:events`, passed as `:bind`) as declared Flow `:conns`?
- The render proc's render-once caches (`:event-rows`, the zoom history's `:rows`) as units with a `:container`?
- Do `seon.cost` and `seon.profile` belong in the release binary or with the drivers?
