# Merging

How a branch is proved and landed on main: `bin/prove`, the verdict cache by digest, the test service, `bin/land`, conflicts and pins, and what a merge message carries. Why is [AGENTS.md §3](../AGENTS.md); the machine the proofs run on is [machine](machine.md).

## A worktree session works in a checkout of its own

`bin/worktree NAME` (run from the main tree) makes `.worktrees/NAME` on branch ``jolt/NAME`` from main: its own submodule checkouts at their pins, an APFS clone of `target/` (so `nice -n 15 bin/build seon` is the only build), its own ports (`config/local.edn`, `bin/ports`) and its own stores under its `runs/`. In it:
- `bin/system start | status | stop` runs that worktree's root, never the standing one; a Claude session there reaches it through the committed `.mcp.json`;
- `bin/prove` runs what the change reaches ([below](#a-merge-runs-what-the-change-reaches)), `bin/test NS` one namespace, `bin/check MODE` one step, on the checks' own store ``target/check-store`` ([checks](checks.md)), never `runs/store`;
- `bin/worktree --sync NAME` moves its submodule checkouts to the pins its branch records; never `git submodule` in a worktree;
- a submodule it adds (``reference-code/X``) is a `.gitmodules` entry and a gitlink; the main tree initializes it after the landing.

`bin/worktree-check` proves this workflow end to end with a throwaway worktree; run it after a change to `bin/worktree`, `bin/system`, `bin/mcp` or the build.

## A branch lands with bin/land

A session lands its one branch with `bin/land [-F MSGFILE]`, run in its one worktree (AGENTS.md §3); the main tree stays on main and clean. `bin/land` (its header is its manual):
1. merges main into the branch (a conflict stops it) and syncs its submodules; refuses a message whose `Completes: ns/name` names a task the tree still holds ([below](#a-merge-says-what-it-completes));
2. runs `nice -n 15 bin/prove --out DIR --merge` as a process group of its own: the owner's fallback for proving a branch (a binary built from the branch's source, its tests forked from it), since the ruled way, a run that forks main and loads the branch's whole program in order in one eval as replay does, is not built yet; the standing system's items are forks of an agent started in the main tree and carry its loaded config and libraries, so it proves no landing until then; a tree a bare `bin/prove` already proved green answers at once, and otherwise only what main's new commits reach runs;
3. moves main to a merge commit whose tree is the proved tip's (`git commit-tree`, then `git update-ref` against main's old value: no checkout of main). If another landing moved main meanwhile, it merges main in and lands without proving again, saying so in the message; only a conflict stops it, and the daily full run covers the combination;
4. brings the change into the standing root's main as data (the bridge, ```seon.system/landed-tx```, [source](source.md#a-landing-enters-main-as-data)), then runs the main tree's `bin/update`, which keeps root running when ```my.root/landed!``` allows it and otherwise builds and restarts it on main, printing why. The `bin/update` that runs is the one from before the landing, so a change to it takes effect at the next landing;
5. prints how far main is past the last full run (`bin/full-run`, [below](#the-full-run-is-the-net));
6. keeps the session's worktree and branch, the branch fast-forwarded to the landed main, where its next change starts. A diff of docs and tasks only runs `bin/check health` and `seon.doc-test` in place of step 2; the log and the run's directory are kept in the main tree's `target/land/`.

It ends on `LANDED` or `LAND FAILED`; a TERM, INT or HUP ends its whole prove group. The message is MSGFILE plus `bin/prove`'s merge line.

`LANDED, ROOT NOT UPDATED` means git's main moved but the standing root's main refused the bridge's write, so no `bin/update` ran (a new binary on an older program would fork no agent). Fix the refusal's cause on a new branch if it is the landing's, then bring root's main to the tree by one of the acts in [files and the db](source.md#files-and-the-db-two-directions), then `bin/update`. Running `bin/update` alone first ends on `UPDATE FAILED: ROOT STALE BINARY`.

The standing root follows main only through `bin/update`, never `bin/system stop` and `start`: root imports main's program from `.worktrees/merges`, which only `bin/update` moves, and it says `UPDATE OK` only once that tree's HEAD contains main (`seon.source-test/an-update-never-says-ok-on-a-merge-that-did-nothing`).

A landing that changes what the store holds (an attribute moved, a schema key removed or renamed) erases the standing store in that landing, never saved up; the store is test data while it holds no user's data ([machine](machine.md#disk-stays-bounded)). Nothing in a worktree is edited, built or started while its `bin/land` runs.

## The managing session lands a batch

There are no batches. Each proved tip lands alone with `bin/land -F MSGFILE`, a dependency pin first ([conflicts and pins](#conflicts-and-pins)). A red is the tip's own: it goes back to its session with the failure, and is never rerun on the same tree to see whether it passes.

## A landing message carries its audit

The landing session reads its whole diff, and the message states:
- the step of THE PLAN it moves ([plan](plan.md)) and each task it completes;
- its `src` line delta (`bin/laws growth`), and what it replaces or deletes: a branch that grows `src` names what it deletes;
- for each function, key, proc or path it adds, which reused piece it is (an entity and its schema, a pull, a render, a request over Flow, the one merge, the reconciler), and every place it recognizes data by keys in code rather than by schema, removed or owed;
- its estimate (n, big-O, the number expected) beside the number measured, and main's number before it;
- for a fix: what allowed the bug, why its class is now impossible, and what the sweep found ([decisions](decisions.md#a-bug-is-a-class-remove-what-allowed-it-owner-2026-11-03)).

What the audit holds a branch to:
- **Every change earns its place.** A new namespace, config key, attribute, proc, route or second way to do something waits for the gate's four questions (AGENTS §3) with its evidence.
- **No regression without a named reason** and who follows it up; a cost bound moves only with the owner's yes.
- **Measured before and after** on the same input; a perf claim is checked independently.
- **What a new design replaces goes with it**: tasks, wiki text and pointers, in the same change. Code with no caller is cut. "No caller" is shown by `seon.main-test/every-name-a-form-uses-is-a-var` green after the cut (the compiler's own facts, aliases included, about 1.4 s), never by a grep: a grep for `ns/name` missed `r/done?` and hung the system tests on 2026-11-12.

A branch that grows `src` or adds a mechanism is also reviewed against the laws: a guard ([failure points](failure-points.md)), a fake default ([errors](errors.md#unknown-is-never-a-default)), a clock deciding ([clocks](clocks.md#if-nothing-has-changed-we-do-nothing)), a second mechanism, a fix at the reader, a test that cannot fail ([testing](testing.md#a-proof-is-focused)). Each finding carries file:line and its fix; the proof reported is the run after the fixes.

## Main goes first

A merge's verification is one command, `bin/prove --merge`: every test and `bin/check` step with no green at its digest, process tests and slow tests included (the plan's `:merge` mode, ```seon.proofs/plan-of```), `contracts` and `smoke` among the steps. It ends on `MERGE OK` or `MERGE FAILED` from its exit status alone and prints the line for the merge commit: sha, the plan's counts, the reds, the `src` delta against the first parent, and the keys left out against main's last merge line. No lock: nothing waits on main's verification, and load is kept down by never running the same work twice. How many heavy runs go at once is the machine's cores and memory ([machine](machine.md#builds-and-checks)).

`bin/update ARGS` hands its arguments to the restarted root (`bin/update --profile deepseek`, [config](config.md#layers)). A root mid-restart refuses connections, so a run that meets it prints `TEST FAILED` for the connection: run again once root is up.

## A merge runs what the change reaches

One rule selects for everyone: a key (a test, a check step, a contract) runs exactly when no green is recorded at its reach digest (```seon.proofs/plan```) in the verdict cache every checkout shares ([verdicts](#verdicts-are-a-cache)). An unchanged tree runs nothing; a green proved in any checkout is reused in every other.

- **Tests.** A test's key is its reach digest (```seon.program/graph-index```: the forms it reaches through refs, macros, quoted names, methods, fixtures and schemas, the config values they read, `:env`, and the input classes they name; [source](source.md#tests-run-by-reach)). It is the tree's, never the process's: one commit gives one set of digests in any checkout (`seon.program-test/the-same-tree-gives-the-same-digests-in-any-checkout-and-any-run`). A test takes only what its reach names, never all of `src`; a test that reads the program's own text takes the `:seon.env/src` class ([source](source.md#what-a-test-takes-of-a-forms-text)). What a reach cannot see is the full run's to catch.
- **Check steps** are keyed by the `seon.check` mode functions their bodies run (```seon.proofs/check-steps```, ```seon.proofs/step-digests```, [checks](checks.md#modes)), plus the program's source and shipped config whole (`:code`, ```seon.fx/inputs```), since a step runs the whole binary. A change to bin/, native/, resources/ or a pin moves `:env` and reruns every step; a change only to the wiki, tasks or tests skips the binary steps. `laws` and `health` read the whole tree and run first beside the rest; a red in either ends the run at once.
- **Generative checks** are one key per pure contracted function ([checks](checks.md#the-generative-check-is-per-function)).
- **A green counts only with its plan.** The plan's line (keys, tests, left out, to run) is compared with main's; a jump in what is left out is red until explained.
- **What reaches a process runs when the run names nothing too.** `bin/prove` (bare or `--merge`) leaves nothing out. A run naming namespaces, and an agent's turn, leave out the tests and steps that reach a process of their own (```seon.proofs/left-out``` over ```seon.program/graph-index```'s `:process?`, from `:seon.config.source/process-vars`); those run when named, in `bin/prove`, in the full run, and at the merge gate.
- **Shards.** `bin/test` reads the program of the checkout's git tree, kept per tree in one LMDB program store for the repository, in the main tree's `target/` (```test-runner/program-db```, `:seon.config.proofs/program-store`, which `bin/env` sets for every checkout: a tree any checkout analyzed is read by the rest), selects, and deals what runs into `:seon.config.proofs/jobs` shard processes by each test's median measured ms (```seon.proofs/shards```, ```seon.fx/typical-ms```), longest first, a namespace longer than a share split; a run of a few seconds stays one process (```seon.proofs/worth-shards```, `:seon.config.proofs/shard-min-ms`). Check steps run beside the shards through `bin/check --steps`, each on an APFS clone of the one seeded store. Each run writes under `target/prove/<run-id>`, removed when green (red, killed and `--merge` runs keep theirs).
- **The binary is built only when a selected key uses it** (```seon.program/graph-index```'s `:binary?`, ```seon.proofs/step-needs-binary?```; the plan writes `out/binary` and `out/steps-binary`), and only when it is not current for the tree (```seon.system/binary-current```, [source](source.md#the-binary-records-its-program)). `bin/test` asks `bin/build libs` first so native libraries are never stale. The process tests' roots branch one kept store, `target/runs/test-store-<schema digest>` (```seon.test-store/make-process-store!```), made once before any shard starts.
- **A shard's agent tests share one root** (```seon.test-store/shared-root!```, used by ```seon.agent-test/in-agent```): each test loads its own task namespace, attaches its own agent, and cancels and cleans up at its end. A test's scripted model is its task's `:seon.task/config`, never a root argument. A test whose subject is a root's life, or whose agent merges into main, keeps a root of its own.

## The merge gate

The tests a branch's change reaches that start a process (`:seon.task/deferred`, recorded by the judge) run before it merges, through root's one `:gate` request (```seon.system/ext-op```), asked by the reconciler and by ```seon.agent/judge!``` alike. It runs on a worktree of `:seon.config.source/gate-checkout` (undeclared: the root's own source root) at the merges head with the candidate written over it (```seon.writer/written```, the assembly the write-back uses), root's binary and kept stores cloned in (```seon.fx/run-on-checkout```), named by exactly the deferred tests, and read back by ```seon.fx/gate-answer```. A red, or a run that could not run (`:error`), refuses the merge; a test the run never reached stays deferred. A red that is main's at the task's base (```seon.fx/reds-at```, ```seon.proofs/main-red```) refuses nothing and is reported as the system's problem (`:seon.prove/full-run-red`). A namespace the gate cannot read from the branch is named on the task (`:seon.task/gate-unread`). A test is never its own gate: the test that started a root (`--started-by`) is left out of it.

Every merge's candidate is also analyzed where it is computed (```seon.writer/unanalyzable```): an unresolved name or a missing alias refuses the merge with the form's words.

A red on the merged tree is the agent's to clear, whoever caused it; the status says when it is also red at the merge base (`:also-red-on-main`).

## The full run is the net

The full runs are explicit: `bin/test --all` and `bin/check 100`. They catch what a digest misses (a file read by a path built at run time, a binary run that no reached form names). The daily one is the cron proc's (`seon.cron`, at `:seon.config.proofs/full-run-at`, UTC; "" is never): it queues a `:full` request for main on the test service, run by the `:tests` effect (`bin/test --all` in a worktree of main, ```seon.fx/full-run-command```). A red there is a `:platform` problem naming each test (```seon.prove/full-answered```); it gets an owner and a task within the hour. Sessions do not run full runs; a pin bump and a cutover do.

`bin/test --all` writes `full-run.edn` in `:seon.config.proofs/results-dir` when it ends (commit, `:green` or `:red` with each red, logs). `bin/full-run` prints how many landings main is past it; `bin/land` and `bin/system status` print that line. A count, not a time ([clocks](clocks.md#if-nothing-has-changed-we-do-nothing)).

The same path is a branch's fallback run: ```seon.fx/run-tests``` with `:built?` and named tests takes a checkout of the branch's commit, builds its own binary and runs those tests by its own runner (```seon.fx-test/a-built-run-judges-the-named-tests-on-the-checkouts-own-build```).

## A test child is a fork of a live agent

An agent runs its tests in children forked from itself (```seon.agent/run-tests-forked```), dealt with the same function and medians as `bin/test` into at most `:seon.config.proofs/jobs` children, no more than the work needs (`:seon.config.proofs/child-min-ms`). A child that dies costs only its test. Each child disposes the walk it inherited, and a test reads nothing of its runner: a config test resolves the config it names, a file test names its file from ```seon.config/checkout```. The `agent-tests` step holds this ([checks](checks.md#modes)).

A test that waits on something nothing can deliver is ended by Jolt's all-asleep check, which throws a typed deadlock naming every wait; a child watches for its agent's death without waiting on the signal (```seon.intr/exit-at-eof!```) so the check can fire. A test parked on a descriptor it holds itself is not yet caught (task `seon.proofs/a-test-parked-on-a-descriptor-holds-every-agent`).

A fixture is a delay a test realizes on first use, made once before tests run so no test pays it in its own budget (```seon.proofs/warm-fixtures!```). It has one of two fixture types: `:seon.test/fixture` (the test store), made by an agent before its first test fork and by a `bin/test` shard whose tests reach it; and `:seon.test/shard-fixture` (```seon.test-store/shared-root-default```, the shard's shared test root), made only by a shard, never by an agent, where it would boot a test root. A shard whose tests reach neither makes nothing.

## A branch merges main in

An agent merges main when it chooses (`(my.branch/merge-main!)`); an outside agent's reply and a model's turn merge main first when it moved, so no agent runs old code, and a conflict is an unmerged entry on its status. Main's tests judge a branch whether or not it still holds them: a test the base holds that the branch removed is defined in the test child from the base's source (```seon.agent/test-plan```'s `:removed`) and still runs (`seon.agent-test/a-branch-that-deletes-mains-test-it-breaks-is-not-merged`). A work task is judged on its branch and never told it is behind main; its merge task merges main in and the gate tests what that writes ([turn](turn.md#what-a-turn-merges-from-main)).

## The test service

One proc, `:prove` (```seon.prove/prove-proc```, declared in `resources/seon/system.edn`), admits every request to run tests: a session's (the server's `:prove` op, `seon_prove`, ```seon.system/prove!```), an agent's turn (```seon.agent/prove!```), and root's judge (mode `:judge`). So a change is proved once.
- **The key** is ```seon.prove/run-key```: `branch@digest`, plus the namespaces a request names, and `!turn` for a turn's run. The digest is the branch's program digest (```seon.program/program-digest```), which no data write moves; main's is main's.
- **One call proves a reference.** ```seon.system/prove!``` takes `{:branch b}` (its head) or with `:commit`/`:t` a past point ([storage](storage.md#a-kept-db-value-is-a-reference)): main as main, a branch through the agent that holds it (or one spawned onto it), a past point on a new branch by an agent spawned there and ended once answered.
- **Items, askers and workers.** A request is planned once by the agent holding its branch (```seon.agent/run-plan```): what it reuses, what it defers to the gate, and the items to run, one test at its digest each. Items join one queue (```seon.prove/ask-items```); an item already queued gains the asker as a waiter, so ten askers of one program run each test once. Admission (```seon.prove/admission```) is bounded by any combination of `:seon.config.proofs/max-workers`, `:seon.config.proofs/max-cores` and `:seon.config.proofs/max-load`; with nothing running one item is always granted, and the load is read only at a step the service already takes ([clocks](clocks.md#the-machines-load-admits-work)). A free worker takes the most valuable item (```seon.prove/grant```: a turn's first, then the item of the asker nearest done, by its measured work left, so askers finish one by one; waiters and age break a tie) with its batch, a share of that agent's queued tests over the free workers and at least `:seon.config.proofs/child-min-ms` of their measured work (```seon.prove/batch-of```), and asks the holding agent to run them in one fork (```seon.agent/item-selection```), each batch on a fiber of its own. A process test is such an item too: it runs in the fork on the checkout's built binary and seeded store (its tree's build and seed items), and a batch's system tests share the one test root its first starts (about 20 s, then about 0.5 s a test; a cold `bin/test` process was about 10 s more a test). A turn's asker is answered at its last proof (stopping at the first red when it named none); a whole run's asker hears every red (```seon.prove-test/a-whole-runs-asker-hears-every-red-and-a-turns-its-first```). Every waiter is answered exactly once. A request for a slot (branch, names, mode) already in flight at another digest supersedes that run: its waiters are answered `:seon.prove/superseded` and what only it queued leaves the queue (```seon.prove-test/a-newer-ask-of-a-slot-supersedes-the-run-in-flight```).
- **Facts.** Verdicts go to the cache by the agent that ran them; the asker's agent writes the run's facts on its own branch once answered (```seon.agent/record-answer!```).
- **Main on commit.** With `:seon.config.proofs/prove-main-on-commit` (on), a commit that moves main's program (```seon.program/moves-program?```) is a run of main keyed by its program digest; a burst is one run.
- **What an agent runs.** A turn runs its task's proofs and the tests its branch changed or added, in process, under the one per-test bound; once those are green it runs the rest of what its change reaches (```seon.agent/judge!``` in ```seon.turn/work!```), then asks the gate for each deferred process test with no verdict at its digest (```seon.proofs/gate-tx```). A red its change made is a reason on its status. `(my.agent/prove)` runs the in-process tests its change reaches and says how many process tests wait (`(my.agent/deferred)`). What no gate can run (```seon.task/unjudged```) stays `:deferred`, and the status says why.
- **An agent's merge on the standing system is judged without pins and config in its digests** while its binary's tree is not the one main was brought to; the gate's process tests on a real checkout are what make such a merge sound.

## A session proves through root

A bare `bin/prove`, one naming tests and `bin/prove --merge` ask the standing system (the main tree's `runs/root.port`), and a tree already proved green answers at once from its marker; no standing system answering is `PROVE FAILED`, never a fallback. `bin/prove --root PORT [NS ...]` (```seon.main/prove-client```) asks the root on PORT to prove this checkout's files with `:stream true`: the server's `:prove` with `:checkout` and `:tree` (```seon.fx/tree-id```, uncommitted files included), served by ```seon.system/prove-tree!```. The files become a branch of main keyed by the tree (```seon.system/tree-branch```, `tree-<id>`), made once by the writer's `:branch-for-tree` with the checkout's catch-up written on it (a file the catch-up leaves out refuses the run); every session on one tree shares its branch, agent and run. Once no run of it is in flight its agent ends and the branch is dropped (`:drop-tree-branch`); a boot drops any left (```seon.db/drop-trees!```).

The process tests, check steps, build and seed are items of the same run (```seon.agent/process-plan```), granted by the service to root's `:item` effect (```seon.fx/run-item```) on the checkout: the build once, the seed once after it, a step through `bin/check --steps`; a process test is a test item, run in a fork of the agent holding the branch on the build and seed. A red build or seed is `:seon.prove/build-red` for every item that needs it. A step past its bound is a signal, never a red. A checkout whose files moved is refused (`:tree-moved`).

The client prints each verdict as it lands (`PASS`, `RED` with failures, `NO-VERDICT` with why) and ends on `PROVE OK` or `PROVE FAILED`, or the typed error that ended it (no root on the port; `:seon.server/unanswered` at the connection's end). The answer carries every verdict (```seon.server-test/a-streamed-prove-writes-every-verdict-its-answer-names```). In-process verdicts run the tree's code loaded over the system's binary (```seon.program/load-diff!```), so a tree that changes a baked file (a schema resource, the shipped config) is named in the answer's `:baked` (```seon.system/baked-paths```) and those verdicts read main's bake.

A branch that changes the prover itself cannot be proved by the standing system, which runs main's prover: it is proved through a system running its own code (`bin/system start` in its worktree) with `bin/prove --root PORT --merge` (```seon.main/prove-client```): mode `:main`, every key with no green at its digest, against no base, since that system's main is the branch. Green, it writes the tree's marker, and `bin/land` lands on it.

## A branch proves its in-process tests, main's batch runs the process tests once

A branch proves everything its change reaches, process tests included, with one bare `bin/prove` at its tip. A tip that holds main and proved green lands with nothing run on main; a tip one merge behind merges main and proves again, reusing every green at its digest. Main is never re-verified for work a branch already proved.

## Every merge's `src` diff stat is read

The landing message states the net `src` lines (`bin/laws growth`, which also lists the definitions the branch adds), against the goal of about 10k lines of our own code. A new namespace or mechanism where an existing one could be extended goes back ([the audit](#a-landing-message-carries-its-audit)).

## A merge says what it completes

A merge that completes a task's work says so, `Completes: ns/name` (one name, or a comma-separated list of them, anywhere in a line, read by ```bin/land```'s one parser, `completes`), and removes that task's entry from `resources/seon/tasks/` in the same commit. `seon.task-files-test/no-shipped-task-is-merged-work` reads those lines on main's first-parent merges and is red on any that names an entry still in the files; `bin/land` refuses such a message before proving. Nothing else in a message counts. The reverse holds at the landing: an entry the landing drops lands only with a `Completes:` line naming it, in its message or a commit it brings (```bin/land```'s `dropped`, run on any two commits by `bin/land --dropped OLD NEW MSGFILE`): the store cannot tell completed work from an entry lost by an edit, since a session's completion writes no outcome.

## Conflicts and pins

A conflict is resolved by copying each side's lines, never retyping, keeping both sides' intent; when why a side differs is a guess, its owner is asked. Each resolved file's forms balance as its parents' do. Stage explicit paths (`git add -A` stages lagging submodule checkouts as gitlinks).

A dependency pin moves every digest (the runtime's gitlinks, `:seon.config.source/runtime-pins`, are in every `:env`), so:
- it lands alone, ahead of other tips, and proves whole on its own branch first: its session runs `bin/prove` there and sends the tip with the prove's last lines; it also runs the cost proofs a prove may leave out (`bin/test seon.program-cost-test seon.record-cost-test`);
- a Jolt pin is ready only with every Jolt gate's verdict, run in the worktree as `make -o submodules GATE` (plain `make` runs `git submodule update`); fiberspoll's socket test is red on stock Chez too;
- it is committed before `bin/build`, which resets every submodule checkout to the pin in HEAD;
- it is verified with `git ls-tree HEAD reference-code/<lib>` and `gh api repos/seantempesta/<lib>/commits/<sha>`; commits in the forks ([forks](forks.md)) carry no AI trailers;
- `bin/update` moves the write-back tree and its submodules to the new pins before the build, since a tree behind its pin makes a stale binary ([health](health.md#a-stale-binary));
- an LMDB bump that changes the lock format needs every root on a store stopped before the new build opens it (``MDB_VERSION_MISMATCH``, [LMDB locks](failure-points.md#lmdb-locks)); `bin/update` does this for the standing root.

## Seon's merge is a three-way merge of the program

Seon's own merges (a branch into main, main into a branch) are a three-way merge over the program as data, never over files. A db's program is a map from keys (``[:form id]``, ``[:var sym]``, ``[:schema k]``, ``[:section address]``, ``[:script path]``, a declared datum) to content; ```seon.merge/three-way``` compares base, ours and theirs, and ```seon.merge/delta-three-way``` reads only the keys where the dbs differ ([storage](storage.md#merges-read-what-changed)). It is the one derivation behind the writer's merges, the done rule's unmerged entries, an agent's merge of main and the dashboard. What merges is written as the branch's own datoms for those keys (```seon.merge/merge-tx```); an entity whose identity the source retracted is retracted whole.

The wiki merges as code does: a section is ``[:section address]``, so main's page changes reach a branch, and a branch's doc change merges and is written back (`seon.merge-test/a-branch-takes-mains-wiki`, `seon.merge-test/a-branch-doc-edit-merges-and-its-page-is-written-back`). A conflict stays on the branch as an unmerged entry (```seon.merge/unmerged```) until the branch redefines the key or resolves it (`my.branch/resolve!`). Written-back files are [source](source.md); a merge task's turns are [turn](turn.md#a-merge-tasks-loop).

An agent's own records never merge: its branch is kept and queried ([decisions](decisions.md#agent-branches-are-kept-and-queried-owner-2026-11-04)). Merge tasks run one at a time (`:seon.config.merge/max-running` 1); the octopus merge of several is built and off. Sessions' branches land by git through `bin/land`; unifying the two is task `seon.merge/one-merge-system`.

## A merge leaves no name unbound

A merge into main is judged against main with the earlier members of the same octopus merge taken first (```seon.writer/judged-members```). A merge that would leave a live form calling a definition it removes is refused as `:seon.merge/dangling` (```seon.merge/dangling```, from the forms' recorded `:form/refs`), by the octopus merge and the gate's candidate alike (``seon.reconcile-test/a-merge-leaves-no-name-unbound``). It reads facts and loads no code. Not covered: a removed namespace another form still requires, and a changed macro's callers.

## Root repairs a failed merge from its REPL

Root's REPL (`seon_eval` as root, or one EDN request a line to its port) repairs these:
- **Merge refused (`:hairy`, `:merge-failed`):** `(my.root/problems sys)`; pull `[:seon.task/outcome :seon.task/reason :seon.task/conflicts :seon.task/unmerged]` of the task. Attach (`{:op :attach :task "T"}`), in the agent resolve each `(my.branch/unmerged)` entry with `my.branch/resolve!` or redefine the var, then `{:op :done :agent "A"}`.
- **Green on its branch, red once merged (`:semantic`):** attach, `(my.branch/merge-main!)`, rerun the proofs, fix, `:done`. The writer refuses a branch behind main.
- **A held task:** raise the limit that held it on the task (`:seon.config.merge/max-conflicts`), then `(my.root/run! sys ["T"])`. A task stuck `:behind` has no repair yet (task `seon.system/a-behind-merge-ends-in-a-fact`); `(my.root/cancel! sys ["T"])` ends it.
- **A failed write-back** (`:seon.git/commit-failed` on the task): remove the cause (a stale `.git/refs/heads/jolt/seon-merges.lock`), then `(my.root/write-back! sys ["T" ...])`.

## Main moved is said at every eval

Every `seon_eval` answer an outside agent gets carries one line saying how main differs from its branch since the merge base, or none (```seon.agent/main-line```, put on the answer as `:main` by ```seon.repl/eval-string```, live, never recorded): how many names main changed, those the branch changed too, those its changed functions call, those a merge would conflict on, and `(my.branch/merge-main!)`. It is made from main as the agent last heard it, and costs what changed: O(1) while main is the base, main's diff once per new main (```seon.agent/main-changes```), then only the branch's (```seon.agent-test/main-line-costs-what-changed```). The `seon_done` answer (```seon.server/verdict-line```) names the same difference. Proofs: `seon.agent-test/main-moved-is-said-at-every-eval`, `seon.server-test/the-done-answer-is-a-line-and-a-handle`.

## Verdicts are a cache

A test's or a step's verdict is derived data, a function of its reach digest, so it is an evictable cache by digest outside the store ([derived data](data-modeling.md#derived-data-is-a-cache-never-relied-on)).
- **Where.** One directory, `:seon.config.proofs/results-dir`, which `bin/env` sets to the main tree's `target/results`, shared by every worktree, agent and gate. A file per key and digest: `<ns>/<test>/<digest>.pass.edn` or `.red.edn`; a step's under `steps/<step>/`, a contract's under `steps/mg/<ns>/<name>/`.
- **Who writes.** Whoever ran it, with no round trip: ```seon.fx/write-verdicts!``` stages and renames (```seon.fx/write-file```), so no lock and no half file. A file the cache will not take is `UNRECORDED`, red, and runs again.
- **What is written: only what ran.** Status, failures, cost report, ms, ticks, bytes and per-function costs, once a digest. Green and red at one digest are two files: that digest is flaky ([health](health.md#a-flaky-test)).
- **When.** A shard writes a green as its test ends and checks the cache just before a test starts, so concurrent runs share what each finishes. A shard whose tree changed under it takes its greens back (```seon.fx/forget-greens!```). A stopped shard stores its greens, never its reds; one that dies stores nothing.
- **What reads them.** A plan checks a green by its file's existence (```seon.fx/stored-results```); a red's file is read for its failures, and an unreadable one is an error (```seon.fx/read-verdict```). A green is anyone's; an agent reuses a red only if it wrote it. Each key's `.recent` holds its newest `:seon.config.proofs/recent-verdicts` `[ms ticks]` (```seon.fx/history```) for ordering, tick budgets and the slow-test read; it is derived and remade when gone.
- **A red verdict is never deleted or moved.** A fixed tree gets a new digest. To see a test red first, run it on the unfixed tree and leave its verdict.
- **A sample of reused greens runs again every run** (```seon.proofs/reuse-audit```): `:seon.config.proofs/reuse-audit` greens that start a process and as many that do not, chosen by the tree's digest, never a red, flaky or step. A red among them is `REUSE MISMATCH`: an input the digest missed.
- **The bound.** The cache holds at most `:seon.config.proofs/results-max-bytes`; a write past it evicts least recently used verdicts and their recorded reads (```seon.fx/evict!```; the last full run's record, `full-run.edn`, is no verdict and never evicted) to `:seon.config.proofs/results-evict-percent` under. Reads touch mtime. The size lives in `.size`, so a write under the bound counts nothing (`seon.results-test/a-write-under-capacity-costs-nothing-of-the-cache`).
- **Facts on the agent's branch.** An agent's run also writes the results worth a fact (```seon.proofs/facts-worth-keeping```: its task's proofs, tests not green, tests the branch already holds a fact of) on the test's var entity (```seon.proofs/result-tx```): `:seon.test/digest`, `:seon.test/status`, `:seon.test/failures`. Main's prove and `bin/test` write no facts (task `seon.proofs/test-results-are-a-few-facts`).
- **Cost questions** ([cost](cost.md#what-a-test-run-measures)): ```seon.proofs/exercising``` and ```seon.proofs/per-call``` over ```seon.fx/details```; an agent asks ```my.agent/cost```.

## A result is a function of its digest

Who runs a test moves no digest: each key that says where or as what a process runs (ports, store, source, profile, what `bin/test` sets for itself) is `:seon.config/place`, out of every digest (```seon.config-test/what-bin-test-sets-for-itself-moves-no-digest```), so an agent's and a checkout's digests match and reuse each other's greens. A branch's config is its own input: an agent's digests take its root's boot inputs with the branch's config laid over key by key (```seon.agent/branch-inputs```, ```seon.fx/config-input```), so a branch's value for one key moves only the tests reading it. A test whose namespace reads the agent's own branch with no db handed in (```seon.program/branch-reads```) also takes the branch's point (`seon.agent-test/a-proof-over-the-branchs-data-is-keyed-by-its-data-too`).

## What a digest holds, and what it does not yet

A green is reused when its digest matches, so whatever its run read outside the digest can change under it. Known gaps, each covered for now by the full run and the reuse audit:
- **Schema entries chosen at run time.** ```seon.db/entity-index``` picks an entity schema by the attributes an entity holds, so a test reaching it takes the whole schema class (task `seon.program/a-tests-verdict-holds-the-schema-entries-it-read`).
- **Names in data outside a test namespace** (a render named in a schema, a proc in system.edn) are quoted names, not references.
- **Names resolved from computed values** (`seon.cost`'s `requiring-resolve` of a proof's `:of`, a task-named model script).
- **A docstring read through `(:doc (meta v))`** is not seen; `clojure.repl/doc` and `:var/doc` are.
- **Whether contracts are armed** changes a cost proof's count but is in no digest.
- **A run that died** writes no verdict, so its run must end red on it.

## A slow test runs last

A test measured at `:seon.config.proofs/slow-test-ms` or over (```seon.fx/slow-tests```: the fastest of its newest greens and its green at its digest) is left out of an agent's run, and `bin/prove` runs the slow tests with no green at their digest last, once everything else is green (the plan's `out/slow`), so a fast red answers first. A slow test is a bug: ```my.root/file-slow-tests!```, which `bin/update` calls, files each as a held task (```seon.template/slow-test```) whose proof is itself. Run one by name (`bin/test NS/TEST`); once fast, its green is under the bound.

## A test's tick budget

Every test runs under a budget of engine ticks of its own (work done, never time; owner, 2026-10-29: "I don't want long running tests period and I want to catch the problems sooner"), set in one place, ```seon.proofs/run-tests!``` (bin/test's shards, an agent's test child, the judge): ten times the ticks its deftest states it should cost, `^{:seon.test/estimate {:ticks N}}`, worked from what the test must do and never from its last run (owner, 2026-11-12: measured over ten times the estimate is the finding), and never past `:seon.config.agent/test-ticks`. A test that states no estimate runs under `test-ticks`, and a branch that adds or changes one is refused by health (`:unestimated-test`, ```seon.problems/unestimated-tests```), so the count of tests with none only falls. The estimate is the test's source, so it is in its digest, and the budget used is on its result (`:budget`, in the verdict). A test past it is red with the typed `:seon.test/ticks-spent` `{:ticks :budget}` naming the budget, and the run goes on ([failure points](failure-points.md)). A budget never reads a test's history: a history in the budget would move a digest with each green written, so no verdict would reuse.

A test that states no estimate runs under `:seon.config.agent/test-ticks` until it does; `bin/check health` lists every such test with what its newest greens spent (```test-runner/unestimated```, ```seon.fx/typical-ticks```), costliest first, its count and top ten printed and the whole list in `target/unestimated-tests.edn`, for each area to state its tests' estimates from what each must do. The list is a report: nothing is budgeted from what a test spent.
