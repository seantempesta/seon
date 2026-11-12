# The machine

The machine Seon is built on and for, and how builds, memory, disk and processes stay bounded. The short form, with the git rules, is [AGENTS.md §8](../AGENTS.md); how a branch merges is [merging](merging.md). The numbers each change measured are in its commit message.

## Seeing what runs

`bin/worktrees` shows everything running across every checkout: each worktree's branch, root, processes (count, CPU, memory, threads) and stores' size, then orphaned seon processes (parent 1, or a checkout that is gone), the heaviest processes, the load and free memory; `-w` repeats it every 5 s.

Native profiling needs no root: `sample PID SECS -file F` and `xcrun xctrace record --template 'Time Profiler' --attach PID`. Native frames resolve (liblmdb through ``target/lib/liblmdb.dylib.dSYM``, the probe library); Chez and Jolt code shows as `???`, so Clojure-level counts and times are `seon.profile`'s. `vmmap` and `vm_stat` need no root (`vmmap` is slow on the store's 64 GiB map); `dtrace`, `fs_usage` and `spindump` need root with SIP on, so do not plan on them.

A `bin/check` run still going after 600 s (`SEON_CHECK_EVIDENCE_AFTER_S`) leaves `at-checkpoint/` in its directory (`bin/evidence`): the process tree, each Jolt process's sample and files, and a fiber dump on its stderr.

### Reading a number

A wall time is only as good as what else ran: compare ticks, bytes and calls, and before believing a time read `uptime` and the top of `ps -axo rss,pcpu,command -r`. A big cost is usually one constant nobody looked at: ask how the number can be what it is, and split it by phase before guessing (a temporary `println` in `test/test_runner.clj`; a `bin/check` step under `SEON_CHECK_NICED=1 PS4='+%D{%s.%.} %N:%i> ' zsh -x bin/check --steps DIR`; a spawn's `:ready` event's `:phases`).

Check that a check ran what it claims:
- `bin/test NS` printing `TEST OK` with no `SHARD` line ran nothing (its digests did not move); `SEON_RESULTS_DIR=<fresh dir>` forces a run. A mutation counts only when it goes red with a `SHARD` line.
- `bin/check MODE` does not run bin/check's `u-MODE` body; `bin/check --steps DIR`, with the step in `DIR/steps` and no `DIR/steps-binary`, does ([checks](checks.md#running-steps-by-hand)).
- Commit in a separate step from the check, after reading its output.
- A submodule checked out at a new commit and not committed as the pin goes back to the old pin on the next build: commit the pin before measuring.
- A digest does not see a form with no var (seon.program/a-top-level-form-with-no-var-is-in-a-digest), so a mutation there runs nothing.
- `with-interrupts-disabled` stops the tick counter, and a bare script counts no ticks.
- `du` counts an APFS clone (`cp -c`) in full: free space is `df` before and after. Summed RSS counts forked pages once per process.
- Two sides of a "shared cache" may not share a key: compare the digests themselves on one tree.
- A task's stated cause, a helper's report and your own theory are hypotheses until measured.
- Never make load to reproduce something: read the code, find the deterministic form, or record what the next real run did.
- To measure what a process holds, build and return from a function (a local in scope keeps its value), with a control that keeps nothing; a walk not disposed (seon.render/dispose-walk!) stays alive through its watches.

### Reading a hang

A hang's evidence is gone once it is killed, and `timeout` signals the whole group. Before killing a process stuck at 0% CPU, capture `kill -INFO PID` (a Jolt process's fiber dump on stderr; a run that goes on after it was stuck on a lost wake), `sample PID 5`, `lsof -p PID` and `ps -M -p PID`, and put them on the defect's task. Read the threads in condition waits and the parked fibers less the idle Flow procs on alts. A `:jolt/deadlock` or `jolt-main-carrier: stuck` is the detector's verdict and may be wrong. `bin/evidence` also asks a live root for ```seon.system/stall-state``` (read-only): root's stall check (`::stall-armed`, `::busy`, `::lost`) and each agent proc's (`:inflight`, `:fx-out`, `:stall-seen`, `:last-frame-ms`), with every proc that answered the ping.

### Reading a red

When a red has no cause, read the data it left (the store, the logs of every failed call) before forming a theory; a guessed cause is said as "not bisected" ([merging](merging.md#the-managing-session-lands-a-batch)).

## The target: a 16 GB MacBook M1

Seon must run on a 16 GB MacBook M1: root, the fork server and `:seon.config.agent/max-running` agents stay within `:seon.config.machine/memory-mb` (4096), and `bin/check scale` fails past it. A process's memory is what macOS charges it, `phys_footprint` (`seon.lib/real-mb`); a forked child is charged every inherited page it touches though only its writes are copies, so `bin/check scale` charges each agent the machine's drop in free plus inactive memory (`seon.lib/sysmem`) and root and the fork server their footprints ([cost](cost.md#how-we-measure)). A cost that grows with something other than the work (a walk of the 64 GiB map reservation) is found by a cost proof sized by what it walks ([storage](storage.md#the-map-is-address-space)).

The development Mac is larger (18 cores, 128 GB): a run there is judged by its free cores and memory, not by the target's numbers.

## The clock

Seon has one clock: `seon.profile/now-ns`, the machine's monotonic CLOCK_UPTIME_RAW read by a Chez foreign procedure (about 9.5 ns, nothing allocated), and `seon.profile/ms-since`. Every process reads the same timeline, so a stamp one process sends is a t0 another can subtract from. `System/nanoTime` and Chez's `current-time` are not used. A cost proof measures the calling thread alone: `seon.profile/cpu-ns` (CLOCK_THREAD_CPUTIME_ID) and `seon.profile/alloc-bytes`, the bytes it allocated (Chez's per-thread count, branch ``jolt/thread-bytes``) ([cost](cost.md#what-is-measured)). Time is measured and recorded, never acted on ([clocks](clocks.md#if-nothing-has-changed-we-do-nothing)).

## Builds and checks

Builds need the Xcode command line tools, git, curl and cmake (llama.cpp's, `bin/build embed`, [vectors](vector.md#the-model)); nothing else is installed, and every download lands under `target/`. Each worktree builds into its own `target/`, so builds share no mutable state and need no lock; a build peaks under ~2 GB. Every process on a store uses the same LMDB build (`target/lib/liblmdb.dylib`); stop a store's processes before a build that changes it. Every step decides by its stamp (``target/NAME.stamp``, the pin of its sources), never by its output existing, and calls the steps it needs, so `bin/build seon` after a Jolt pin move rebuilds Jolt first. `hello` and `seon` are stamped by the tree (`bin/tree`: HEAD's tree plus every changed and untracked file) and the chez and jolt pins.

The release binary is the program's: `bin/build seon` runs `jolt build -m seon.main`, and ```seon.main/-main``` serves `bin/system` (```seon.main/root-serve```). The check drivers (`seon.check`, `seon.e2e`, `seon.fixtures`) are in `test/`, outside the binary: every `bin/check` step loads them from the checkout at run time ([checks](checks.md)), and a `:scripted` root adds the checkout's source (```seon.main/checkout-roots!```) and loads its script by name (```seon.model/scripted```).

**Heavy runs** (`bin/build`, `bin/test`, `bin/prove`, a repro loop) run under `nice -n 15`, each under its own `target/prove/<run-id>`, and nothing tracked is edited in a tree while a run reads it. Proves go through the standing system's one test service, which admits work by the machine's cores ([the test service](merging.md#the-test-service)). A timing taken under load says so.

**A long run is looked at; only its evidence lets a clock end it.** No run is wrapped in `timeout`. A check step or a test far past its own measured time is captured by `bin/evidence`, then ended as a signal with no verdict stored ([clocks](clocks.md#time-decides-the-owners-exceptions)); its slowest step is a defect, filed as a task with what it measured. The full checks (`bin/test --all`, `bin/check 100`) are the daily run's and a pin bump's ([merging](merging.md#a-merge-runs-what-the-change-reaches)).

A check mode is its body over one harness, `seon.check/run-check`: boot root, run the body, print its report and checks map, stop root, end on `NAME OK` or `NAME FAILED` (`:then :kill` makes it phase 1 of a kill/reopen pair). A mode waits only on what the system emits, never by polling or a deadline:
- root's events on a tap taken before their cause (`seon.check/ready!`, `seon.check/await-agents`, `seon.check/await-event`), or a watch on a reference (`seon.check/await-ref`, `seon.system/await-db`);
- the producer's own word that a thing happened: an eval began (`:started` from `seon.repl/agent-step`, `seon.check/started`, several at once by `seon.check/await-each`), a test child forked (`:tests-started`), a proc took a message (`seon.check/probe-proc`), a tab's view is current (``: settled`` on the feed), the fork server caught up (`seon.check/fork-server-caught-up`);
- an agent commits a request's writes before it replies, so a mode reads them from the store after the answer (```seon.db/head```), with no wait.

A wait on main runs its predicate in main's writer at every commit, so it reads only the fact it waits on, never a derived whole such as the problems report (`seon.problems/stalled-agents`, `seon.check/await-provoked`; `seon.check-test/the-problems-steps-waits-read-no-whole-report-per-commit`), and each wait prints its name as the step enters it. The time a check may hold is named in [clocks](clocks.md#time-decides-a-checks-measurement-and-a-checks-ping). An outside client a mode drives reads its stdin from a file, so its EOF cannot hang on a pipe end held elsewhere (`mcp-proof`).

## What a process owns in memory

Root and the fork server hold their own pages; an agent's real cost is the machine's drop in free plus inactive memory, since its footprint counts inherited pages it only read ([target](#the-target-a-16-gb-macbook-m1)). Root, the fork server and ten idle agents come to about 1 GB. An agent's start-up writes tens of MB of pages scattered over inherited objects (16 KB pages on Apple silicon), not garbage it allocated; contracts are not the cause.
- **Inherited data is static.** ```seon.fork-server/server-step``` makes the heap static (`seon.lib/static!`) before a fork when ```seon.fork-server/static-due?```: main's program moved, or what an agent would inherit outside static passed `:seon.config.agent/inherited-bytes` (16 MiB) after a collection. A collection traces only what is outside static, so it never privatizes inherited pages, and the fork server's static grows with main's live data, not with every fork (`seon.agent-test/a-fork-servers-static-grows-only-with-the-program`). Only the fork server's death frees it.
- **The agent's heap cap bounds growth, not memory.** ```seon.lib/heap-cap!``` sets `JOLT_MAX_HEAP` to `(bytes-allocated)` (static included) plus `:seon.config.agent/heap-extra-bytes` (1 GiB); a collection runs when the total passes max(0.75 of the ceiling, live + a quarter of the room) and raises only if still over afterwards, so garbage never trips it. It pins Jolt's nursery at `:seon.config.agent/trip-bytes` (`JOLT_GC_TRIP_BYTES`; `seon.agent-test/an-agents-collect-trip-is-the-configured-one`). A test child sets its own ceiling from its heap at its fork (`seon.agent-test/a-test-childs-ceiling-is-its-own`). The heap-reserve ratio is fitted to bytes including static, so Chez may keep free pages up to about half the inherited heap.

### How to read an out-of-memory error

An `OutOfMemoryError` from Jolt's heap ceiling carries what holds the memory: `{:jolt/heap {:live :static :dynamic :ceiling :top [{:type :count :bytes :gens} ...]}}`. `:top` is the 20 Chez types holding the most collectable bytes, descending; `:static` is the boot image (never the leak), `:dynamic` the collectable total. An eval's error map keeps it as `:jolt/heap` (```seon.agent/error-map```). One type holding most of `:dynamic` is the culprit (a held db shows as its index nodes). `(jolt.host/heap-histogram 20)` answers the same shape at any REPL at the price of one full collection; the ceiling pays that only when it is about to raise. The GC overhead limit's error carries no histogram.

### Root's heap

An idle root is its boot image, its program and the node cache. Read a forked process's own cost from its SM=PRV pages (`vmmap -wide`) or the machine's available-memory drop, never its footprint, which counts pages shared copy-on-write.
- **The image keeps no inspector information.** The boot image is about three times its file. `bin/build` strips each release unit with our Chez's `inspector-expressions` and `inspector-variables`: a frame keeps its name and file:line and loses its variable names; eval errors read the same.
- **A namespace's init is data, not code.** Jolt emits a def's call-site registrations as one quoted table (`jolt-register-callsites!`), a constant collection literal as one quoted datum (`jolt-datum`) and its keywords, var cells and cache cells as one call over a table (`jolt-consts`); `seon.contract/checked` is `:redef`, so the contract wrapper is called, not spliced into each definition.
- **Root evaluates no test code.** Its boot and writer pass `{:tests? false}` to `seon.program/load-all!` and `load-changes!`; the fork server loads the tests, which agents inherit ([source](source.md#a-process-holds-the-tests-only-when-it-runs-them)).
- **Root does not preload main.** The fork server reads every node of main's indexes once, after its fork (`seon.fork-server/main`, `seon.db/preload!`), so agents inherit them decoded.
- **Root returns its free pages.** Once the fork server is forked, root sets Jolt's `JOLT_MAX_HEAP_FREE_RATIO` (the JVM's `-XX:MaxHeapFreeRatio`) to `:seon.config.root/max-heap-free-ratio` (10) through ```seon.lib/max-heap-free-ratio!```; the fork server and agents keep Jolt's default. Chez returns pages only in a full collection: root makes one at boot and at each quiescence (the writer's `:collect`, asked by the reconciler once per quiet spell), never by a clock.
- **The node cache is bounded by what its nodes retain.** `:seon.config.store/node-cache-bytes` (128 MiB) is weighed per node by `datahike.index.node-cache/node-bytes` (our Datahike fork). persistent-sorted-set's Jolt port keeps no restored child under the `:weak` ref type Datahike passes, so the cache is the one holder of nodes read back and a branch retains only its keys and addresses (`datahike.test.node-cache-weight-test/a-cache-weighs-what-the-nodes-it-holds-retain`). Eviction is a clock (second-chance): a hit sets a flag and allocates nothing. A root's hot set is main's index size, about 0.7 KB weighed a datom, so the bound holds main up to about 190,000 datoms.
  - **A hit costs a seek one more branch lookup, not a map update.**
- **A held db value costs what its version does not share.** The writer's commit notice (`seon.writer/main-commit`) is `{:t :prev :ops :commit :ns}`, a reference, never a db; each consumer makes the db with `seon.db/at-commit` when it takes the notice and lets it go when the next replaces it ([storage](storage.md#a-kept-db-value-is-a-reference)). The conn is shared by several procs, but only the writer transacts through it.
- **A kept older db is a point, and the guard reads every proc's state for one.** What a process derives from an older db is a cache keyed by `seon.db/point` (`[commit-id max-tx]`), and the db is made again when read: the agent's `:base-graph`, `:branch-graph`, `:branch-changes`, the fork server's main index (`seon.fork-server/index-for-agent`). Kept as values on purpose, each replaced by the next commit: the reconciler's, fork server's and render proc's main db, the render's per-view branch read, the agent's `:db`, `:base` and `:main`. Guards: `seon.procs-test/no-proc-keeps-a-db-older-than-its-branchs-current`, `seon.agent-test/an-agent-keeps-no-db-older-than-its-branchs-current` (both walk state with `seon.test-store/stale-dbs`), `seon.render-test/a-dropped-page-lets-go-of-its-branchs-db`. A db inside a closure, and the writer's union (`seon.union`), are not walked.
- **A view keeps handles, not values.** Root's view of an agent keeps its last `:seon.config.root/view-window` evals as `seon.system/eval-head` (the handle, the printed head, the cost, an error's type and message), never the value, out, err or error data (`seon.procs-test/root-view-of-an-agents-evals-holds-handles-not-values`). The REPL's `*e` is kept by `seon.repl/error-kept`: a new exception carrying the error map, with no cause. `*1 *2 *3` hold their values, as at any REPL.

### An agent's first write

**What an agent's start reads of main, the fork server reads first, once, so an agent's start costs the same however long its root has run.** Before each fork ```seon.fork-server/warm-start``` makes the agent's start reads itself: its first write (```seon.agent/begin-run-tx```) applied with `d/with`, its record built on that (```seon.ctx/build```), and once per move of main the schema changes; the agent inherits those leaves decoded. An eval's id is issued by the store (``seon.db/take-eid``), so nothing reads every recorded eval. ```seon.repl/branch-registry``` answers the registry the agent holds until a write moves a `:schema/` datom (```seon.agent/schema-moved```; `seon.agent-test/an-eval-reads-the-schema-entries-only-after-one-moved`). The cost proof is `seon.agent-test/an-agents-start-costs-the-same-however-long-its-root-has-run`, constant across 10, 100 and 1,000 agents' history. What it still holds: each eval's commit (```seon.agent/commit!```, the index paths it rewrites), `begin-run!`'s two transactions and its record's build.

### An agent keeps every result

An agent keeps every eval's whole response for its process's life, in its state's `:results` and as the var ``result/<hash>``, so ``result/a3f9c1d2`` and `(my.agent/$ :e3)` always read it; no window evicts a result ([no cuts on agents](processes.md#an-agent-is-never-gated)). Other processes keep a reference ([root's heap](#roots-heap)). The agent's page walk keeps no db, taking the datoms its db moved by (`seon.ctx/take-moved!`), and an eval's reply carries its error as data (`seon.repl/error-data`, [errors](errors.md#an-error-keeps-data)).

### Ten agents at once

Ten agents cost root, the fork server and about 100-170 MB each by the machine's drop; each may grow `:seon.config.agent/heap-extra-bytes`. The embedding model runs in a helper process only while embedding work is queued ([vector](vector.md#the-model)). The target is root idle at or under 650 MB, the fork server under 300 MB, ten working agents under 3 GB ([plan](plan.md)).

### Ten agents that work

`bin/check scale-work [N] [no-search]` (`seon.check/scale-work`) starts N agents (10) on a main that holds Seon's own program; each does five evals (a namespace with a test, a read of main's vars, `my.agent/tests-reaching`, transactions, an edit of a function many tests reach) and then all prove at once. It samples the machine every `:seon.config.check/sample-ms` and every process under the fork server, and reports the drop against `:seon.config.machine/memory-mb`. The reading is moved by every other process on the machine, so `fits-the-machine` is read over runs on a quiet machine. At most four test children run at once whatever the agents' number.

### What a spawn costs

A spawn on an unmoved main costs the agent's own start, tens of ms. Commits and forks reach the fork server on one FIFO pipe, so a fork after a commit waits for the server to load what the commit changed (`seon.program/load-changes!`, a few ms a form) and to bring the reach graph to main at the next fork: a spawn pays per move of main, never per fork (`bin/check spawn-phases N`, `seon.agent/spawn-phases`).

### What a turn costs

A turn's cost is the agent's work eval plus root's allocation from release to quiescence. What keeps root's part small:
- root's render proc prints a context or reads health only for a reader: ```seon.render.proc/with-context``` (a tab on `/root/debug` or a `:context` request) and ```seon.render.proc/health!``` (kept with its `seon.db/point`, moved by the datoms between commits);
- a plan checks a verdict by its file's existence (```seon.fx/stored-results```, [merging](merging.md#verdicts-are-a-cache));
- the reach graph carries its tests' index (```seon.program/reach-indexed```, [source](source.md#tests-run-by-reach)), moved with main by the fork server (```seon.fork-server/graph-at-fork```); an agent on an older base recomputes only stale tests (```seon.agent/base-graph```).

`seon.sock/recv!`'s bytes in a profile are other fibers' allocation while it is parked: a call's bytes are not its cost where fibers share a thread.

## A root boots from a seeded store

A boot resumes its main and reads no file: no import, no `load-all!`, no wiki ([source](source.md#a-boot-resumes-from-the-db)). What is left is the image and a digest of main's program (one read of five attributes' datoms), about 1 s from `bin/system start` to `ROOT LISTENING`. A seed (`bin/system seed STORE`, ```seon.system/seed-program!```, or the first boot of a fresh store) pays the import (`seon.system/import-tree!`: only what the tree changed since its `:seon.system/source` fact), the wiki and the task files once; `bin/test` seeds its process store once after it builds. With `:seon.config.root/wiki-baseline` on, a store's wiki baseline (`seon.fx/wiki-history`, `git blame` of every page) is also paid once. `bin/build` records the program its binary was built from by an incremental index ([source](source.md#the-binary-records-its-program)).

### The restart tests

Tests whose subject is a restart or a resume keep a store of their own; `seon.test-store/clone-process-store!` is the one way a test makes one from the seeded store (an APFS copy-on-write of `data.mdb`). A test root's processes end with the runner: `bin/held`'s watcher waits on the starter's fifo with `select`, which wakes every watcher when the last writer closes.

## Processes

Every shell Seon runs goes through ```seon.lib/sh```, which runs `clojure.java.shell/sh` on a fiber, so no thread is started (```seon.server-test/a-done-shells-on-no-thread-of-roots```, ```seon.main-test/every-shell-runs-through-lib-sh```).

Anything that makes threads or processes starts at n ≤ 100 under `nice -n 15`; watch `ps -M -p PID | wc -l` and kill your own process past 200 threads; one experiment at a time.

A child process costs what starting it costs: `/usr/bin/git` is the Xcode shim, so `bin/env` puts `target/git-bin` (a link to the real git) first on `PATH`, and a child spawned from Jolt costs 20-35 ms. A path that spawns children per call is a cost to count (```seon.fx/tree-id```, ```seon.fx/inputs```).

A process that must outlive a tool call is double-forked, as `bin/system` does. Kill only verified PIDs: read the full command and working directory (`lsof -a -p PID -d cwd`) first, never a pattern (`pkill`, `killall`, `pgrep -f`); `./seon` runs from `target/` with a relative command line. A root's processes end with it: kill the root, or its fork server's process group (`kill -9 -PGID`), never a fork server by pid ([failure points](failure-points.md)).

## Disk stays bounded

Disk stays bounded by these rules, and by removing garbage on sight (a merged worktree, a leftover process, a finished experiment's output) after checking by PID, path and working directory that nothing live uses it. A worktree is live while a session works in it or a config names it (the main tree's `config/local.edn` names `.worktrees/merges`).
- A worktree is removed by the landing that merges it (`bin/land`, [merging](merging.md#a-branch-lands-with-binland)); one found merged and clean later by `bin/worktree --remove-at DIR`.
- A session's scratch lives inside its worktree (`target/`, `runs/`) and goes with it; nobody adds a top-level directory. Gitignored and shared: `target/results` (the verdict cache, [merging](merging.md#verdicts-are-a-cache)), `config/worktree-ports` (`bin/ports`), `.worktrees/NAME`.
- What is kept is what git and the store record. While Seon holds no user's data, the standing store (`runs/store`) is test data, erased (never migrated) by whoever's change needs it, after warning the other sessions; once it holds a user's data, a change is a migration. Build output, compile caches and finished experiments' stores are regenerable.
- `target/prove/<run-id>` is removed by its run when it ends green; a red or killed run's, a `--merge` run's and an `--out` directory are kept as evidence and removed by whoever fixes the defect (`bin/remove-tree`), merges, or named them.
- Nothing prunes ``target/check/`` or a root's old generations (~200 MB each): removing them is a manual act that keeps the running generation and the one before it.
- `target/check-store`, which every step clones, is remade past `:seon.config.proofs/check-store-max-bytes` (```seon.check/remove-store-past!```); a green step's clone is removed, a red one's stays under `target/check/<run>/stores`. A clone's real cost is what it changed.
- `target/cache/aot`, the shared compile cache, is kept under `JOLT_CACHE_MB` (`:seon.config.build/jolt-cache-mb`, read by bin/env), least recently used first, and by generations (`JOLT_CACHE_GENERATIONS`, one per runtime) ([shared build caches](source.md#shared-build-caches)).

## Secrets and paid runs

Secrets are never printed. Check the context before any paid model call. Paid runs spend from the owner's standing budget ($50), on productive work only, and root may start them without asking inside it; past it, they need the owner's go ([model](model.md#paid-runs)). Install nothing system-wide. Never write in `~/src/seon`.
