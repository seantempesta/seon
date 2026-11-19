# Architecture: Static Flow, Malli-Derived Datahike Schemas, and COW Runtimes

Seon runs one static 8-proc `clojure.core.async.flow` graph over a durable multi-process Datahike LMDB store, multiplexing scoped agents and isolated `clojure.test` + Malli generative runs across a bounded pool of Copy-on-Write (`fork`) OS processes.

## 1. Malli as Single Schema Authority (`seon.schema`, `seon.schema.datahike`)

Every Datahike attribute (`:db/ident`, `:db/valueType`, `:db/cardinality`, `:db/unique`, `:db/index`, `:db/noHistory`, `:db/contentId`) is derived automatically from co-located multi-segment Malli schemas (`resources/seon/schemas/seon.*.edn` and runtime `:seon.schema/key` entities).

- **Native `:db.type/data` & `:db/contentId`:** Malli forms (`:seon.schema/form`, `:seon.code.var/schema`), arglists, and pull patterns store natively via FASL (`:db.type/data`). Content-addressed identities carry `:seon.db/content-id true` so independent branches creating identical vars, schemas, or tasks receive the same `:db/id`.
- **Co-located multi-segment naming:** Shallow `one/thing` schema keys are refused at registration; schemas live under the namespace hierarchy of the code that uses them (`seon.code.*`, `seon.agent.*`, `seon.health.*`, `seon.test.*`, etc.).
- **Write-seam validation:** `seon.store/validate-tx-data!` validates every transacted datom against the branch's live Malli registry before writing to LMDB.
- **Runtime installation & safe migration:** `(user/register-schema! k form opts)` validates a `seed = 42` generator, installs derived `:db/ident` declarations immediately, and `seon.merge/three-way` blocks any merge that retypes an installed attribute or invalidates stored datoms.

## 2. Static 8-Proc Flow Graph & COW Fork Parent (`seon.main`, `seon.fork`, `seon.agents`)

Seon spawns a 1-thread `ForkParent` at the very start of `seon.main/-main` before Flow starts any worker threads or loads multi-threaded native libraries, guaranteeing safe `~6 ms` COW forks.

- **Static Flow topology (zero `flow/update!`):**
  - `:server` (`seon.server`): Request router for `/op` and `/mcp`.
  - `:transactor` (`seon.transactor`): Sole serialized writer of `:main`; advances the `ForkParent` image (`:advance-image`) and hot-loads merged deltas into the standing process.
  - `:reconciler` (`seon.reconciler`): Pure `O(|Δ|)` derivation of agent status (`:runnable`, `:running`, `:testing`, `:merging`, `:waiting`, `:merged`) — never stores status in Datahike.
  - `:agents` (`seon.agents`): Multiplexes logical agents onto `max-os-procs` warm persistent COW processes with LRU idle eviction and zero-PID cleanup on `:merged`.
  - `:model` (`seon.model`): Streaming OpenAI-compat / DeepSeek client over fiber-parking `jolt.http.core` (`StringBuilder` SSE accumulator, `reasoning_content` replay, `prompt_cache_hit_tokens`, `::flow/input-filter` backpressure). API keys stay exclusively in `:model`.
  - `:test` (`seon.test-runner`): Transitive reach selection (`seon.code/update-reach-graph`), 2-tier fail-fast (`<= 150 ms` fast shard first), seed=42 generative checks, content-addressed green cache, and anti-test-deletion checks.
  - `:git` (`seon.git`, `seon.git-proc`): Lossless `Datahike :main === Git main` write-back and zero-worktree `refs/heads/seon/<branch>` export via `git fast-import`.
  - `:web` (`seon.web`, `seon.render`, `seon.render.ui`): 100% non-blocking HTTP/SSE server, 120fps `datahike.reads` incremental walk, and in-process `/mcp` JSON-RPC 2.0 endpoint.

## 3. Codebase Indexing & `O(|Δ|)` Rails (`seon.code`, `seon.merge`)

Every `.clj`, `.md`, and `.edn` file and every REPL evaluation is parsed losslessly into structured Datahike entities (`:seon.code.ns/*`, `:seon.code.form/*`, `:seon.code.var/*`, `:seon.code.doc/*`, `:seon.schema/*`).

- **Namespace hierarchy refs:** `:seon.code.ns/parent`, `:seon.code.var/ns-ref`, and `:seon.agent/focus` let agents navigate their namespace subtree with standard Datahike pulls (no separate scope subsystem).
- **Mid-session branch sync:** Warm agent workers automatically hot-load newly merged `:main` code deltas (`seon.fork/sync-conn-to-latest-branch-head!`) overlaid with their own branch edits before each turn/eval.
- **Outside footprint stays minimal:** All logic lives in `src/seon/` (~7.5k lines). `bin/mcp` (53 lines) and `bin/seon` (112 lines) are zero-logic relays; `bin/build` bootstraps native C/Chez/Jolt/LMDB artifacts; `bin/worktree` is temporary and slated for removal once all sessions operate purely on Datahike branches.
