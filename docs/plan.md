# The plan

What Seon is building, in stages. Each stage has one goal, the measure that says it is done, and its work in order with an
owner. A stage starts when the one before it is done; work outside the current stage waits unless it unblocks it. The why
is in [principles](principles.md); the owner's rulings are in [decisions](decisions.md).

## The goal

A user's roughly specified task becomes merged code in as few turns as possible. The measure is taken on rounds of two or
three backlog tasks drawn at random and worked by live agents: one row per task (turns, runs, merged or not and why),
reported to the owner daily. A task that never merges is a failure, not a low number. The draw stays random, because agents must take any task, however
unspecified: a task's specification is its first proof, the page says so reactively while it is missing, and an agent that
improves its task without finishing it has the improvement synced to disk once a Claude session's review finds it better,
so repeated rounds carry every task from start to end (owner, 2026-11-14). Beyond it, the owner's system goal
(2026-11-11): the system improves its own code (correctness, cost, how interwoven it is, the turns its agents take) by
"lots of base hits rather than swinging for homeruns".

## The system we are building toward

The target is a system that, once booted, has no dragons or sharp edges: complexity is pushed to the edges, and every problem
comes back to us as data without breaking anything (owner, 2026-11-14). Five guarantees define it; each names what breaks it
today and the work that makes it true.
1. **Booting is the only place we check.** A process starts through one path, which proves once that it holds exactly what
   its branch names (program, registry, config, environment variables, libraries, pin, binary); past startup that is
   guaranteed. Broken today by test children forked from root's agent, scripts that take the caller's shell, and merges on
   a branch's stale program. Made true by Stage 0.5, Stage 1.6 and Stage 2.1.
2. **Everything inside is plain:** immutable values and Flow procs; no locks, clocks or guards; derived data re-derived by
   the system when its inputs move, never by a caller remembering to. Broken today by cites, the reach graph and imports
   going stale, and by two three-way merges in one. Made true by Stage 1.5, Stage 1.6 and a registry of stated and derived
   attributes (Stage 2).
3. **Complexity sits at the edges, behind one owner each:** the shell, files, network, the model, processes, git and the
   dependency forks are effects, bounded and killable, whose failures come back as typed data. Broken today by a reasoning
   overrun ending a run, a stop with no bound, and git and forks outside the store. Made true by Stage 0.3, Stage 1.7 and
   Stage 3.2.
4. **Self-modification cannot cut the floor out:** an agent may redefine anything, and the machinery it runs on (its turn
   loop, its answers, its test children) runs from the program, not from the REPL's redefinitions. Broken today by an
   agent that redefined how every eval answers and hung its own turn loop. Made true by Stage 0.6 and Stage 4.3.
5. **Every problem comes back as data:** every wait ends with a value or a typed error; every error carries its fix and its
   causes, is drawn on the page of whoever can act on it, and is counted. Broken today by a hang that recorded no stall and
   by a board of rows dug out of logs by hand. Made true by Stage 0.3, Stage 1.8 and Stage 3.1.

**Measured by:** of a week's failures, the share that arrived as classified data rather than found by a person or session in
logs, rising to all of them; and zero hangs, zero false reds, and restarts only for a binary change. Then the turns metric
measures the agents, not the plumbing.

## Why it is slow today

A process often runs on state its branch doesn't name (stale code, pins, schema registry, config, caches), so verdicts are
wrong or flaky, landings fail on what the change didn't touch, and the merge a task needs runs on that stale state. Around
it, the work itself lives outside Seon: blockers, deletions, the landing queue, the turns ledger and the owner's rulings are
files in root's notes and messages between sessions, which no agent reads and nothing checks. Sessions land through `bin/land` and git; one agent task has merged through Seon's merge (`seon_done`, pilot run 15,
2026-11-16), the one path every agent's task must take. The priority (owner,
2026-11-14) is one view: fix the broken processes that slow us down, and move Claude sessions off files and git onto the
system, working, proving and merging as Seon agents do. Everything else in this plan stays, in order behind it.

## Where things stand

What a fresh root reads first: what works now, what to do next, and the lessons that cost time.

- **The self-merge path works end to end once.** Pilot run 15 took `seon.db/an-eval-responses-parts-are-its-schemas-keys` through `seon_done` (judge, gate, merge) into main in the store. Seon wrote it back to git (85cc745c06 on jolt/merges), root reviewed the sync, and it landed as 7875387c3e.
- **Restarts and the store.** Lands keep root running again (09e83a2d59). Boot's sweep syncs, so freed pages are reused (1a7c7d8526). Each boot still grows the store ~2 GB from writes made before the writer's first sync (`seon.system/a-write-before-the-writer-syncs-its-own-steps`). The 64 GiB store copy `runs/store-pre-compact-20261116.mdb` is kept until the owner says otherwise.
- **Redraw.** A render a land redefines now redraws every kept walk (2409c17de3).
- **THE FOCUS (owner, 2026-11-16, [decisions](decisions.md#2026-11-16-rulings)): the new test system, deployed for every agent, and the old one deleted, as fast as possible.** This is the one reference every session builds from; a task plan that differs from it is corrected to it, or the difference is brought to root.

  **What it is, when done.** An agent (Seon's, or a Claude session through the MCP tools) edits on its branch; `seon_prove` runs only the tests its change reaches, each in a fork of its branch's warm process, reusing every result whose reads did not move, from any branch; `seon_done` is a land request on main, answered once: root's writer merges main into the branch, re-proves only the tests whose reads main moved, and merges into main in one step, results riding the merge. No `bin/land`, `bin/prove`, `bin/test` for sessions, worktree land path, fast lane, land count or names file.

  **Its invariants** (each a constraint that makes the bad case impossible, never a check at a caller):
  - A test's reads are recorded at datom level (`[:ea e a]`, the recorder's own shape), never a whole entity; a schema change reruns only the readers of the keys it moved.
  - A result is a fact identified by its content: test, reads digest, status. Two branches' results never conflict; flaky is a query. Results live on the branch, are read from it and its parents by one function (`seon.agent/results-at`), and reach main with its merge. The results branch and the file cache go.
  - Derived data is computed once from immutable inputs and kept with them (a form's keywords, the program's var digests, the reach graph); nothing is recomputed per call, per start or per eval.
  - Every branch, main included, has a warm process holding its program reference `{:branch :commit :t}`, kept current by `load-diff!` from its parent branch's; each runtime test forks from it, writes speculatively (the fork's existing `:test` mode), and reports its own ticks, bytes and stage; a child that dies answers why.
  - Two rails, derived from what a test reaches: boot tests (start, boot, restart, the binary, the switch-over) and runtime tests (everything else, handed its environment by the fork). No disk read after boot.
  - A test states its estimate at design time; its result is red past estimate × a config leeway; per-rail config ceilings on ticks and memory end a runaway; ticks are the test's own work (Jolt, deps). Inputs come from the function's malli schema with a fixed per-test seed; a deep run with many seeds is on demand.
  - A merge is one writer step; an incompatible schema or pin change is an unmerged entry the agent resolves; a red the change reaches blocks it, a red main also holds is main's, fixed forward by its owner.

  **The order, each step deleting what it replaces:** (1) datom-level read keys, `:form/keywords` from one producer, `results-at`, root's results db wired (tests, data); (2) the warm process per branch and the test fork, the import hook landing a session's edits on its own branch (runtime, context); (3) results on the branch, the plan's `:run` as the one selection (tests); (4) rails by reach and the tests moved onto them, by tests' helpers and each area; (5) the land request through `:prove` and data's one `:merge`, then every session through `seon_done`, then the old tools deleted (tests, web, data); alongside: ticks and ceilings (deps, tests), the switch-over (flow, runtime, deps, web), the write-back mirror (data).
- **After the focus:** the page walk's remaining work on the reactive core (`seon.ratom/web-reads-on-the-core`, whose STATUS says what is left); runtime's slice 2b `:update` proc; the store's ~2 GB a boot.
- **How root found things (lessons):**
  - Inboxes lapse at every restart: re-attach, and wake idle sessions directly.
  - Name one owner the moment a cause is found.
  - A land that restarts root needs `runtime-for` checked first.
  - Before ordering any fix, ask which standard mechanism already does the job.
  - Memory and notes files are per-login: state lives in tasks, schemas and this wiki.

## The order of work now

Agreed 2026-11-14 by every area and the owner ([decisions](decisions.md#2026-11-14-rulings)). Each session works its items
in this order, on its one branch, and lands when green (`AGENTS.md` §3). Correct and simple first; cost later (owner: "Right now we dedupe and
upgrade everyone").

### The root concept: one reaction over the store

Almost everything Seon keeps is derived: a test's verdict, a page and a request, health, readiness, a task's status, the
store's copy of the files. Each subsystem built its own cache, key, index and "did it move?" check (a survey of main found
6+ read recorders, 4 dependency graphs, 3 who-read-what indexes, 5 hand-kept "moved?" lists, 4 import paths, 5 stores for
one test result, 73 staleness functions), and the bugs are those copies disagreeing. They become one mechanism:
- **Reagent's own API, nothing foreign** (owner: "Most agents are trained on reagent and I don't want to teach a foreign
  concept"): `seon.ratom` already ports Reagent's `atom`, `Reaction`, `make-reaction`, `reaction` and `run!`, and gains
  Reagent's `track` (a reaction cached by function and arguments, compared by `=`). A component is written as in Reagent:
  it derefs a reaction or `@(track f args)`, and the store is a source it derefs. What changes is only the tracking: a
  deref of the store records its datom keys through Datahike's recorder, so a reaction re-runs when a datom it read moves.
  Values that are not datoms (an agent's live values, streaming output, root's runtime values) are ratoms or `track`
  arguments, as in Reagent.
- **One tracker:** Datahike's recorder (`di/record-read!`). Every read records through it as a datom key: a store read; a
  slice a component reads of a pulled value (`[:ea e a]`); a code read as its form's datom (once per var per run, at
  Jolt's var read, macros at load); config, files and pins as their entities. `seon.profile`'s set and ratom's path
  tracking stop being trackers.
- **One index, stored:** `:seon.reads/key-set` (indexed), the rename of `:seon.proof/read-keys` (on main with
  `seon.db/results-moved`, but written and read by nothing in src), on every durable derived entity; a page component's
  key set lives in its walk value; one match, `(touches? key-set datoms)`. It deletes the four live in-process indexes:
  the walk's `:readers` and `:tree-readers`, the render proc's `:read-index`, and ratom's `sources`. A reaction's identity
  is a content id of [fn, reads digest, program].
- **Re-run by the commit:** the writer sends each commit's datoms once; the readers of moved keys re-run as effects on
  their kind's owner, bounded and admitted (never inside the writer's step). A pin change answers `:restart`.
- **The outside enters by one catch-up:** seed = catch-up from empty; import = catch-up of paths; landing = catch-up after
  `bin/update` moves root's tree; pins stated as the whole set. Inside, every read is a datom read.

### Claude sessions move onto the Seon path (Stage 1, now)

A session works as a Seon agent: one branch mirrored in Seon, REPL first, file edits imported by a thin hook
(`my.branch/import!` on each write; runtime builds it and proves it on one edit end to end: file, branch, `seon_prove`,
`seon_done`, main, disk and git), proving and merging with `seon_prove`/`seon_done` once admission (item 2) and the one
start path (Stage 0.5, tests with runtime) are on main. `bin/land` lands what is in flight, then only the tasks that build
this path, and is deleted when the path carries a week of landings.

### The self-merge path (owner: "make merging as simple as a passing proofs")

The steps by which an agent merges its own branch once its proofs pass, with no release and no human in the loop, each owned by the area named.

1. A verdict reads only its own work: a red that depended on load, a cold fixture or a shared file is a platform problem,
   never a verdict against a change, so main is never shown red by another run's measurement
   (`seon.prove/a-verdict-reads-only-its-own-work`, tests).
2. Proving goes through the one effects owner, admitted by the owner's dials (tests; root shepherds landings by hand until
   then).
3. One merge (`seon.merge/merge-main`): a conflict at any entity and attribute applies nothing; it costs what changed
   (data, runtime, deps).
4. The merge's check is a prove of the candidate (the branch with main merged), reusing every green whose reads did not
   move: all green and no conflict merges, with no release (`seon.reconcile/the-gate-proves-the-candidate-branch`, tests).
5. Code loads live (load-diff at the one start, O(d)); a binary is swapped only when a build input changes (pins,
   native code, `deps.edn`), once, through root's online takeover (deps, runtime).
6. Agents self-merge with `seon_done`; `bin/land` is deleted.

The goal is boring, continuous integration (owner: "make merging boring so it happens more and agents are just always
integrating with main"): every turn syncs main down (cost O(d)); every green, conflict-free change merges up at once,
never batched; a code change loads live. The test is each branch's drift from main (time and changes since its last
sync), shown on the dashboard: drift is a problem, not a merge-time surprise.

### The order

The overhaul halves the code (owner, 2026-11-15: "I want our codebase cut in half when we are done overhauling everything so de-duplication of ideas is essential"): `src` goes from 43,312 lines to about 21,600 (45,804 on 2026-11-16, so the overhaul has not shrunk it yet), each landing's `Deletes:` line counting toward it, and no functionality removed.

The owner adopted this order on 2026-11-15: eighteen landings over six primitives that already exist in the code, each finished and made the one way to do its job. The primitives:
1. what a commit changed: Datahike keeps each commit's tx-data (`d/changes`), and the writer's notice delivers it live;
2. who read what: the read-key index (`seon.db/reads-add`, `readers`);
3. a decision stored once, never re-derived from a past point (a task's reasons, a turn's assembly, a verdict);
4. ask and answer: `seon.step/pend` with `end-all`, so every end answers every waiter;
5. one queue and one admission (`seon.prove`, the `:process` owner);
6. one loader and one identity (`seon.program/diff-plan`, `load-diff!`, content digests).

A session takes its first unblocked landing; areas run side by side; rounds of real agents run continuously and re-rank what follows by the turns they lose (`my.root/run-losses`). The first real `seon_done` merged on 2026-11-16 (pilot run 15); it is tried again after 6, 7 and 10. Every piece first finds the code that does its job and wires it; a complicated design steps back to a pillar, data model first (`AGENTS.md` §4).

1. **`:done` waits on the judge's own end** (runtime). Deletes `merge-ms` and the wait on root-wide quiet. Done: `seon_done` ends while another agent runs.
2. **The status read apart from the page walk; a run with a RED line or no result line fails** (context, tests). Done: a cold status read makes no as-of read, and a run printing RED fails.
3. **An import compares form ids and is the one import** (runtime, data). Deletes the tree-digest shortcut that recorded files it never wrote, and the whole-tree import paths, onto `catch-up` from the empty tree.
4. **A task's cited code is in its agent's context** (context; round 1 lost 13 of 16 turns finding it). Done: a round's agent edits in its first turns.
5. **One loader; an agent loads main's changes in place** at its turn boundary, re-forking only for a build input (runtime; owner, 2026-11-15). Deletes `load-all!`, `reload-dependents!`, `load-changes!` onto `diff-plan`, and the cold re-fork per turn.
6. **`d/changes`, with the merge as its first caller** (deps, data). The merge is each side's change from `d/merge-base` as `[e a v]`, replayed by source transaction, each a new later transaction with its metadata and `:seon.tx/source`, through the checked write (owner's ruling; measured). Deletes the stamp-ownership rules (`tx-branch`, `shared?`, `owned?`, `overtaken`), `delta-three-way`, `carried-tx`, `merge-main`, `judge-tx`'s own three-way and the data-attrs argument; `datoms-diff` stays only for commits older than the log.
7. **The armed fact; a recorded `q` reads its entity-bound keys** (tests, data, deps). Done: a mutation of a reached function turns its test red.
8. **The done rule computed once and stored as the task's reasons** (context, data). Deletes the second input builder and readiness inside the status render.
9. **The hand-back as steps; `end-all`** (context, flow). Deletes the hand-back's in-form chain and the second pending tables (`await-reply`'s `:waits`, the reconciler's `:waits`/`:judged`, the agent proc's `:inflight`).
10. **One `:merge` op that re-proves by itself:** main moving after a proof merges when no reached digest moved and re-proves the moved items (data, runtime). Deletes the octopus batch, `:behind`, `max-behind`, and the stored config entity (derived from its entries). Done: lb76 merges and the end-to-end proof is green.
11. **One admission by weight** (runtime, tests). Deletes `grant`'s budget, `batch-of`, `held-processes`, `memory-jobs` and the hand load rule; `max-workers` and `max-load` stay as dials. Done: three agents and two sessions prove with load within the cores.
12. **Test results are entities with their read keys; recorded keys decide reuse** (tests; the first landing after the Jolt pin, its reviews ahead of the reviewers' own work, and its next slice moves `bin/test` onto the same results so every session switches over at once (owner, 2026-11-15); before 11 and 13: a test plan costs 263M ticks warm against a ~20-30M bound, ~90% reading result files and re-indexing every test [M, tests, 2026-11-15]). Deletes the result files, `.recent`, `reads.edn`, `:seon.test/*`, `reuse-audit`, then the reach digest as reuse key; the reach graph stays as the program's index.
13. **`bin/test` and `bin/prove` are thin clients; steps are deftests; tests fork from the standing system** (tests, deps). Deletes `run-on-checkout`, `run-tree-item`, `arm-wall!`, the worker modes, `bin/stalls`, `bin/check`'s units and the booted root per test process; a test's own ticks judge it again.
14. **The pilot: git follows the store, one area lands through `seon_done`** (data, runtime; owner: pilot early). Deletes `jolt/merges`, `sync!` and the separate write-back repeat, onto the one write-back (`merges_catch_up` is gone: an update's merge is the `:git` effect's, the merges tree's one writer).
15. **The reactive core live, one landing per reader** (runtime, then web, context, flow): the render proc's own index and second diff, the render's and `seon.ctx`'s own engines, health and laws as live classes, the reconciler index's four holders.
16. **Each turn stores its assembly and the model's output** (context; owner, 2026-11-15): components by content digest, in order; a person's tab and the debug view show what was sent (owner). Deletes `basis-value`, the as-of fallbacks, the parts walk and the proof story's replay.
17. **Updates lose nothing, then the control plane is carved** (runtime, deps, flow): flights and requests are facts, evals resume at their turn boundary, one restart predicate; then what is left unused in `seon.system`.
18. **`bin/land` goes** (data, runtime): with the bridge, `landed-tx` and `bin/prove --out`, onto `seon_done`.
- **Beside it:** focused docstrings (the per-var metric), one exception-to-data path, one citation resolver, hand lists as schema properties: random-round tasks reviewed by each area; estimating made automatic (web's plan).

Designed so far, the order removes about 13,500 of the ~21,700 lines; the rest needs design, starting with `seon.agent`, `seon.repl` and `seon.turn` together, then `seon.render.ui`, the `*-tx` builders, `seon.fx` and `seon.program`.

## Stage 0: one happy path

**Goal:** one real backlog task merged by an agent. **Done when:** a task drawn from the backlog is on main, with its turns
recorded.
1. Landed 579d62547d: a test child holds the config its checkout's shard loads, and every runner digest moved (tests).
2. One schema registry per process, and a schema entry main deleted merges as its deletion; the memory gauge's proof reads
   its block alone (runtime, one landing). The
   cause behind both, a process running state its branch doesn't name, is `seon.program/a-process-runs-its-branchs-program`
   (Stage 2.1).
3. A reasoning overrun is the model call's typed error, not the end of the run: the effect answers it as data, the system
   reads it for the agent on its next turn, and the agent's page shows its recent errors with their causes, one render
   (owner, 2026-11-14). `:seon.config.ai/max-tokens` holds reasoning and reply together; one run of two died of it this
   round (runtime for the effect, context and web for the render).
4. A fork waits for other threads to park, never races them: landed 168a472aba; its wait then missed the wake of a thread
   entering a collect-safe call and hung a test child for good (runs/evidence/gate-stall-1346), fixed in Chez 3ec3794d
   with gate forkbesidenative (deps).
5. A test child forks only from a process already on its branch's environment, never from root's agent: today the service
   runs another checkout's tests in a fork of root's long-running agent, which carries root's config, environment, heap,
   threads and loaded libraries, and each fix so far swapped one of those (owner, 2026-11-14: make it impossible, not
   another check). The agent `seon.system/prove-tree!` spawns on a branch starts on that branch's environment through the
   one start path, which checks once, at startup, that the process holds exactly that environment (config, environment
   variables, libraries, pin) and refuses to start otherwise; past startup nothing re-checks it; the import of a checkout's files onto a branch and the live route stay, since Stage 1 builds on them
   (runtime, tests; moved up from Stage 4.3).
6. An agent's redefinitions cannot break its own machinery: its turn loop, its eval answers and its test children run from
   the program, and a wait in the turn loop ends with a typed error that is recorded as a stall (round 1: an agent that
   redefined `seon.repl/response` hung at turn 24 with no stall recorded) (context, runtime; moved up from Stage 4.3).
7. `seon.repl/an-uncited-public-defn-is-told-at-its-eval` (proof green at turn 48, its agent now lost) restarted on a fresh
   agent, or the first of the random round's tasks to reach green, merged by `seon_done` (context).

## Stage 1: Claude sessions work through the system (the priority)

**Safe to start only when all of these are on main,** each proved red first (2026-11-14: a merge gate on the standing system
booted ~74 test roots at once and took the owner's machine to load 70; a prove waited 17 minutes on that gate):
- proving runs as Flow: a test, a process test and a merge's check are messages to the effects owner's procs, whose
  bounded channels admit work from the cores and memory by backpressure, so load stays within the machine with no
  admission check, worker count or gate beside it; the merge gate's git worktree and rebuild go, and a merge's check is a
  prove of the candidate branch in the system (`seon.reconcile/the-gate-proves-the-candidate-branch`) (tests, runtime);
- every wait ends with its value or a typed error, so no eval waits on a check with no end (tests);
- an update that must restart root waits until nothing is in flight, and a wall-time end is a platform problem, never a
  cached red (web, runtime);
- the MCP relay answers each call on its own (web);
- one session's edit, proved and merged through the system end to end, keeps the machine's load within its cores
  (`seon.server/a-sessions-file-edit-merges-through-done`, with its cost proof).
Until then sessions land through their worktree and `bin/land --fast` (`AGENTS.md` §3); `seon_done` has merged one agent
task (pilot run 15, 2026-11-16), and moving every session onto it is step 7 of the checklist in
`seon.proofs/a-test-forks-from-the-standing-system-and-a-binary-test-reruns-only-for-its-binary`.

**Goal:** a Claude session changes Seon the way a Seon agent does: its change enters the one system as a valid branch, is
proved there by what it reaches, and merges into main through Seon's own merge, with git following the store. Everything a
session needs to get work done is a task or the wiki. **Done when:** one area's session lands a week of work with no
worktree, no per-checkout system and no `bin/land`, and root's notes hold no board, ledger, queue or ruling. Then the
infrastructure this replaces is cut, each removal brought to the owner as an audit.
1. **Approved designs are tasks:** each approved, unbuilt design (the one-unit spec, the Flow standard, process start, the
   effects plan, the online takeover and resume, data's 11-13 interface agreements, main keeping its own proofs, the
   landing queue as data) is one task whose plan holds the design, so no compaction loses it (owner, 2026-11-14) (each
   design's area).
2. **A task's specification is its first proof:** a task whose plan lacks what the work needs is shown so on its page,
   reactively, and specifying it is the agent's first step; an improved task reaches disk after a Claude session's review
   (context, data).
3. **A session is an agent:** each Claude session holds one agent keyed by its area's name on one branch of the one store
   (`seon_attach` exists), with its REPL and its branch's program; `seon_eval` and `seon.repl/edit!` change code, docs,
   tasks and `bin/` scripts as writes on that branch (runtime).
4. **Pushing a change is easy:** a file a session edits is imported as a transaction on its branch (the same path an
   agent's eval takes), so a session that works on files gets a valid branch with no extra step; a read-only copy of the
   branch is written out for reading (runtime, data).
5. **Proving is on the branch, in the system:** the session's agent proves by reach, live, and by a full build only when
   the change may differ (a pin, native code, `deps.edn`). Results are entities in the store, shaped for how they are read
   back (owner, 2026-11-14: "pull first"): each holds its test, its status (written once), everything its run read
   (datoms through Datahike's read recorder; config entries, files, environment variables and the pin as recorded outside
   reads), the environment and process it ran in, and its cost. Reuse, "why is this red", "what ran where" and "flaky" are
   then queries, not keys chosen in advance; the result files go (tests, data, with runtime for the outside reads).
   **The target, a lower bound worked from the work** (tests, 2026-11-14): T(d) = plan(d) + R(d) × t_test ÷ C + t_fork +
   B × t_boot, unchanged tests costing nothing. With t_fork 65 ms and t_test 21 ms (`docs/cost.md`), R ≈ 216 tests for a
   typical change and N ≈ 2,000 in all: a typical change proves in about 1.3 s on 18 cores (1.6 s on the 8-core target),
   and a pin change that reuses nothing in about 25 s, one shared boot for the few genuine boot tests included. Each
   test's declared work stays within its estimate (median 10 ms), its fixtures warm before timing, and only a test of
   booting boots. Within 2x of this is met (owner: "pretty close ... is good enough"); past it, the cause is found and fixed. Today: 85 ms a test in a warm prove, and 223 tests booting a system for 1,541 s.
6. **Merging is Seon's:** `seon_done` runs the gate and Seon's three-way merge on main's program; conflicts go back to the
   branch; one three-way merge, not two (``seon.task/carry-tx`` goes); a merge costs what moved (today one merge of main
   costs 62 s, 9.9B ticks and 47.5 GB, measured) (data, runtime).
7. **Git follows the store:** a merge is written back, and git's main moves after root reads its diff (`my.root/sync!`);
   main is pushed to GitHub (runtime, root).
8. **The work lives in Seon:** a blocker is a task in the owning area's namespace with its causes (`:seon.task/causes`,
   a set of schema keys each with its evidence, re-derived, owed a schema entry by data), named in the blocked task's `:seon.task/needs`; a deletion is a proof that the old path is gone; the
   merge queue is tasks waiting to merge, in `:seon.task/needs` order; turns to merged is a query shown on the task and the
   dashboard; the owner's rulings are in [decisions](decisions.md); a message between sessions is about a task and is
   written there, and one that names no task becomes one; when a landing completes what a task needed, the sessions waiting on
   it are told (`my.root/landed!` sends `seon_message`); a flaky result is a derived problem class, never a hand-filed row
   (context, tests, root).
9. **Pilot, then all:** one area moves first after two or three merged tasks; then the rest. The audit to cut follows:
   worktrees, per-checkout systems and ports, `checkout-env!`, `bin/land`'s local prove and git bridge, the shell test
   runner and its shards, the result files, the boards as files (root, with each owner).

## Stage 2: the database is the one source of truth

**Goal:** no process can run on state its branch doesn't name. **Done when:** a law goes red on any program load outside
the one start path, and no failure in a week comes from a stale or mismatched environment.
1. An environment is one reference, `{:branch :commit :t}`; the program, its config, its files and its pins are data on the
   branch; one start path brings a process to it and refuses only a pin or runtime mismatch, as a typed error; only start,
   fork and merge make an environment. A result read under one environment is then keyed by it with no extra input
   (runtime, deps, data).
2. A build writes the branch's files from the store and builds from them; a git tree matters only when files are imported
   (runtime, deps).
3. Deleted, after an audit to the owner: tree ids and tree diffs, per-tree program caches, health's base tree.

## Stage 3: live, and nothing hangs

**Goal:** Seon restarts only for a binary change, and every wait ends with its value or a typed error. **Done when:** a week
of landings with no restart except for a pin, native code or `deps.edn`, and no hang.
1. Every restart reason classified: forms load in place, procs swap through Flow's `update!`, schemas and config reload,
   boot-only code waits for the next build; a pin change goes through an online takeover, with a clean shutdown, resume, and
   a path back after a crash that reports what was lost; a stop that waits on `held --end` ends with a typed error
   (runtime, deps, data).
2. One effects system: each effect's bound declared on its request schema (malli `:default`, overridden by config), a call
   only shortens it, Flow enforces ticks and bytes and the effects owner wall time and child memory; effects routed at
   Jolt's host primitives; concurrency admitted from cores and memory; every outside read (a file, an env var) recorded as a
   read (runtime, deps, data).
3. Time is data on channels: no polling; the only clock is an effect's deadline, arriving as a message. The Flow standard
   and its examples; the ten parts that should be procs become procs (runtime).

## Stage 4: agents finish in few turns

**Goal:** median turns to merged falls across random rounds. **Done when:** set from Stage 0's first measurements.
1. Every stored, synced or public shape a named schema; the schema graph chooses what an agent is shown, each item
   explained; duplicate schemas and unnamed public shapes flagged on change (data, context).
2. What an agent reads: a cache-stable prefix and a fresh tail; one walk gives the page and the request; "What your task
   still needs" (built as edits after one whole page; the whole list every turn is a trial); assembly costs what changed at
   1M-token pages, and the reactive walker is deleted; reference sessions in the native wire format; compaction: deleting, editing, compacting
   or forgetting an eval is a change of data the next request is rendered from, with page controls for each (only
   unlinking has a test today) (context, web).
3. REPL redefinitions: the REPL may do anything; only definitions become program; each eval's answer says what was saved
   (runtime, context).
4. The loop closes at a task's end: work, then refine (the worker's conversation continued, curating its own task and the
   docs it pulled), then review (a separate agent that judges and files tasks, never editing what it reviews; green merges a
   change to the system itself). A refinement is tried by rerunning from the earlier point in history beside the original,
   and a reviewed session is written to an import area in the repository (context).
5. Gold sessions and templates: a template is a function from a saved gold session's EDN plus parameters to a new task
   carrying that session as its example; similar earlier tasks offered as precedents; a problem class with one solved
   instance becomes a swarm of tasks, docstrings over fifty words first (context, data).
6. Measures as a system: a measurement is one call whose precision accumulates from use; a measure past its threshold is a
   task with its evidence (tests, context).

## Stage 5: the user's system

**Goal:** the owner works with Seon itself rather than with Claude sessions. **Done when:** set when Stage 4 is done.
1. A session is an agent: it names the agent whose conversation it continues, a fork's branch is the cut, the hierarchy is
   `:seon.agent/parent` at any depth, and a question goes to whoever started the task (data).
2. The user talks to the agent at the top; it picks direction and presents; a focus agent per area is an ordinary
   long-running agent. Every page is a render over pulled data, with no hand-rolled layout (web).
3. Merging branches syncs all non-conflicting data, with no list of attributes; each source transaction becomes a new, later
   transaction of the receiving branch (data, runtime).

## Enforcement owed

The principles each name what enforces them; these are not yet enforced, and each is owed a law or a test, filed as a task
in the stage that builds its mechanism: turns counted by the system (Stage 1.8); no design starting outside the Seon agent;
a proof seen red first; a duplicate schema or unnamed public shape seen twice (Stage 4.1); `Deletes:` checked (Stage 1.8);
effects routed at Jolt's host primitives (Stage 3.2); no program loaded outside the one start (Stage 2.1); atoms; no test
reading wall time, load or system memory; a second status at one digest filed as a defect; claims marked measured or
hypothesis.

## Throughout

What holds in every stage.

- Context runs the random rounds and reads every run's reasoning; system problems become tasks for their area.
- Every claim is marked measured or hypothesis; a research round lands a fix or stops for review.
- The code shrinks as mechanisms merge until it reaches a healthy weight; every landing reports its `src` delta, and useful
  functionality is removed only after an audit to the owner.
- A retrospective closes each stage; the principles and this plan are pruned then, never appended to between. A rule is
  removed only with its reason: the incident or ruling behind it, what holds it now, and why it no longer earns its place.
