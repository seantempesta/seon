# Architecture: Static Flow, Structured Datahike Entities, Malli Contracts, and COW Forked Runtimes

Seon runs one static 8-proc `clojure.core.async.flow` graph over a durable multi-process Datahike LMDB store, multiplexing logical agents and isolated `clojure.test` + Malli generative runs across a bounded pool of Copy-on-Write (`fork`) OS processes.

## 1. Process, Memory & GC Topology

1. **System Fork Parent (`seon.fork`):** Spawned at line 1 of `seon.main/-main` after raising Chez's GC generations to `6` (`(jolt.host/gc-generations! 6)`) and promoting static/runtime roots into the top generation (`(System/gc)`). Holds `:main`'s program warm-compiled in shared pages (`:advance-image`) and forks agent runtimes and test workers in `~5 ms` over `jolt.async.remote` and `jolt.io-poller/exit-chan`. Forked children inherit `collect-ceiling = parent - 1` so child GC cycles never scan or COW-copy inherited `:main` pages (`untouched? = true`).
2. **Flow Process (`seon.main/start-flow-instance!`):** Runs the static 8-proc `clojure.core.async.flow` graph on 3 OS carrier threads (`:io` fibers, `:mixed`, `:compute`), with zero `flow/update!` calls and zero ad-hoc `atom`s.
3. **Persistent COW Agent Processes & Per-Namespace Test Workers (`seon.fork`):**
   - Active agents keep their forked OS process alive across multi-turn work and multi-tool `eval` calls (preserving live runtime vars, `*ns*`, and `4 MB` child store cache). Once an agent reaches `:merged` (or is LRU-evicted when `max-os-procs` is needed by another waking agent), its OS process exits cleanly (`0` idle OS bloat), reconstructing seamlessly from its Datahike branch commit (`d/changes`) in `~5 ms` when woken again.
   - Uncached `clojure.test` vars and `seed = 42` Malli generative checks are sharded by namespace into clean `~5 ms` COW forks so test namespaces never pollute one another.

## 2. Structured Entities & Generic 3-Way Merge (`seon.store`, `seon.code`, `seon.merge`)

All system knowledge lives as structured, indexed entities in Datahike (never opaque eval logs):

- **Program (`:ns/*`, `:form/*`, `:var/*`):** Lossless Edamame spans (`0`-byte diff round-trip) paired with compiler-accurate `:var/*` entities (`:var/name`, `:var/doc`, `:var/arglists`, `:var/schema`, `:var/schema-refs`, `:var/refs`, `:var/ticks-budget`, `:var/bytes-budget`, `:var/big-o`, `:var/test?`, `:var/platform-test?`). Answering *"which functions use schema `:x/y`?"* or *"all callers of `ns/fn`"* is a single indexed AVET seek (`0` live parsing).
- **Schemas (`:schema/*`):** First-class Malli schemas (`:schema/key`, `:schema/form`, `:schema/examples`, `:schema/render`). Every schema registered in Datahike is immediately validated to have a working `seed = 42` generator or valid `:examples`.
- **Docs (`:doc/*`):** Lossless Markdown section entities (`:doc/id`, `:doc/path`, `:doc/heading`, `:doc/body`, `0`-byte diff round-trip).
- **Config (`:config/*`):** Seeded once at cold bootstrap via `aero.core/read-config` from `resources/seon/config/default.edn` onto `{:config/key :system/root}`. Any `:agent/id` overrides any config key simply by placing that attribute on its own entity (`seon.store/config-val`, `~20 µs` lookup, `0 ms` fork overhead).
- **Generic Attribute-Level 3-Way Merge (`seon.merge/three-way`):** Discovers all `:db.unique/identity` entities from `seon.store/attributes` and merges `:db.cardinality/one` attributes by 3-way value comparison and `:db.cardinality/many` attributes by 3-way set diff/union in `O(|Delta_ours| + |Delta_theirs|)`.

## 3. Contracts, Generative Stress Testing & Cost Rails (`seon.schema`, `seon.code`)

- **Instrumentation (`~50 ns` / `20 ticks` per call):** Every public `defn` carrying `{:malli/schema [:=> ...]}` is automatically armed via `m/-instrument`, returning humanized errors plus a concrete `seed = 42` valid example call on any violation.
- **100-Sample `seed = 42` Stress Check:** Whenever a contracted function (or a `:schema/key` it references) moves in the Merkle reach graph, `:test` exercises it with `100` deterministic generated inputs (`seed = 42`, `~4 ms`) and caches the verdict under `:test/digest "gen:<digest>"` so unchanged functions never re-run.
- **Tick & Byte Budgets:** Every REPL/agent `eval` executes inside `jolt.host/run-interruptible` (un-swallowable even by `catch Throwable`), stopping runaway loops at `:config/eval-ticks`. Functions and `deftest`s declaring `:cost/ticks` or `:cost/bytes` fail honestly in `:test` on overrun.

## 4. Local Git Sync, Datastar Explorer & Zero-Downtime `--take-over`

- **Local Git (`seon.git`, `seon.git-proc`):** Branch commits auto-sync to `refs/heads/seon/<branch>` via `git fast-import` (`0` worktrees), and verified `:main` merges write touched files and record a true 2-parent local Git merge commit (no GitHub push).
- **Datastar System Explorer (`seon.web`):** Serves `resources/public/datastar.js` and streams pure server-side Hiccup via `datastar-patch-elements` over SSE, with data-driven `:ui/op` callbacks and `:schema/render` components.
- **Cold-Boot Reach & Live Binary Swap-Over (`seon.main`):** Static reach automatically identifies `:boot-affected?` changes and `:var/platform-test?` tests (`0` manual labels). Candidate binaries verify cold boot via `--self-test` and swap over a live server via `--take-over PORT` (`{:op :hand-over}`) with zero lost state.
