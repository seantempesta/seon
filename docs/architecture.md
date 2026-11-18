# Architecture: Static Flow, Datahike Branches, and COW Forked Processes

Seon runs one static `clojure.core.async.flow` graph over a durable multi-process Datahike LMDB store, multiplexing any number of logical agents and `clojure.test` runs across a bounded pool of Copy-on-Write (`fork`) OS processes.

## Process Topology

1. **The System Fork Parent (`seon.fork`):** Spawned at line 1 of `seon.main/-main` while the process is 1 OS thread and holds zero open LMDB environments. It keeps `:main`'s program warm-compiled in shared pages (`:advance-image`) and forks agent REPLs and `clojure.test` workers on demand (~5 ms per fork) over `jolt.async.remote` and `jolt.io-poller/exit-chan`.
2. **The Flow Process (`seon.main/start-flow-instance!`):** Runs the static 8-proc `clojure.core.async.flow` graph across `:io` fibers, `:mixed` threads, and `:compute` threads, with zero `flow/update!` calls and zero custom socket framing.
3. **Ephemeral Agent & Test Worker Processes (`seon.fork`):** Forked on demand when an agent has runnable work or when uncached `clojure.test` digests need execution. Waiting agents hold `0` OS processes.

## The 8 Static Flow Procs

- **`:server` (`seon.server`, `:io`):** Single external boundary for `bin/seon`, MCP tools, and HTTP `/op` requests. Routes validated EDN maps directly to `:transactor`, `:agents`, or `:test`.
- **`:transactor` (`seon.transactor`, `:mixed`):** Sole writer of `:main` on LMDB (`d/transact`, `store/branch!`, `merge/three-way`, `store/sync!`). Enforces Malli schema rails at the write seam, hot-loads merged code diffs, advances the Fork Parent's warm image, and emits `{:prev-commit :commit :tx}` on `:db`.
- **`:reconciler` (`seon.reconciler`, `:compute`):** 100% pure state. Holds zero host handles and stores zero `:status` or `:want` flags in Datahike. Incrementally evaluates touched agents on `:db` and `:exits` (`O(|Delta|)`), deriving `run`, `batch` (test), and `merge` actions. Cold boot `init` is identical to crash recovery.
- **`:agents` (`seon.agents`, `:io`):** Multiplexes autonomous agent turns and interactive branch REPL evaluations onto `<= max-os-procs` COW forked processes using `::flow/input-filter` backpressure.
- **`:model` (`seon.model`, `:io`):** External LLM boundary (`jolt.http.core` OpenAI-compatible chat completions + deterministic local synthesis). Holds API credentials exclusively inside this proc and enforces slot/rate limits via `::flow/input-filter`.
- **`:test` (`seon.test-runner`, `:io`):** Maintains the incremental reverse-dependency Merkle reach graph (`seon.code/update-reach-graph`) in `O(|Delta| + |callers|)`. Skips any `deftest` whose `:test/digest` is already green in Datahike, executes uncached `clojure.test` vars and Malli generative checks in clean forked workers, and sends `:test-failure` messages back to agents on failure.
- **`:git` (`seon.git-proc`, `:io`):** Keeps disk files and git `main` 1-to-1 with Datahike `:main` on every merged change (`seon.git/write-and-commit-main!`), and exports unmerged DB branches to `refs/seon/<branch>` via `git fast-import` with zero worktrees.
- **`:web` (`seon.web`, `:io`):** Serves `POST /op` (`application/edn`) and the live SSE Flow Monitor UI (`GET /`, `GET /events`) over `ring-chez.adapter` fibers, tapping `::flow/report` and `::flow/error` directly with zero `atom`s.

## The Four Structural Rails

1. **The `O(Δ)` Law:** Only cold `init` scans `:aevt` once. Every runtime transition (`load-delta!`, `three-way`, `update-reach-graph`, `reconciler`) reads `(d/changes store from-commit to-commit)` so cost is invariant to total DB size `N`.
2. **Zero `:main` Drift at Fork:** `:transactor` pushes every merged `:form/*` delta to the System Fork Parent immediately, so newly forked children start `0` commits behind `:main`.
3. **Scale-Invariance Gate in `:test`:** `seon.core-test/scale-invariance-rail-test` measures deterministic Chez `(jolt-ticks)` at `N = 100` vs `N = 1,000` forms and fails if tick growth exceeds `1.5x`.
4. **One Inside Path (`bin/seon`):** External sessions and internal agents share the exact same Datahike branches, forked REPLs, `:test` runner, and `:transactor` merge gate.
