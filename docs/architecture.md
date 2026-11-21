# Architecture: Static Flow, Malli-Derived Datahike Schemas, and COW Runtimes

Seon runs one static `clojure.core.async.flow` graph over a durable multi-process Datahike LMDB store, multiplexing scoped agents and isolated `clojure.test` + Malli generative runs across a bounded pool of Copy-on-Write (`fork`) OS processes.

## 1. Malli as Single Schema Authority (`seon.schema`, `seon.schema.datahike`)

Every Datahike attribute (`:db/ident`, `:db/valueType`, `:db/cardinality`, `:db/unique`, `:db/index`, `:db/noHistory`, `:db/contentId`) is derived automatically from co-located multi-segment Malli schemas stored in `resources/seon/schemas.edn` (265 schemas, 467 derived attributes, including `:seon.code.*`, `:seon.agent.*`, `:seon.task.*`, `:seon.rule.*`, `:seon.health.*`, `:seon.test.*`, `:seon.summary.*`, `:seon.bench.run/*`, and `:seon.fix/*`).

- **Native `:db.type/data` & `:db/contentId`:** Malli forms (`:seon.schema/form`, `:seon.code.var/schema`), arglists, pull patterns, rule `:when`/`:assert` clauses, and benchmark run snapshots store natively via FASL (`:db.type/data`). Content-addressed identities carry `:seon.db/content-id true` so independent branches creating identical vars, schemas, rules, or tasks receive the same `:db/id`.
- **Co-located multi-segment naming:** Shallow `one/thing` schema keys are refused at registration; schemas live under the namespace hierarchy of the code that uses them.
- **Write-seam validation:** `seon.store/validate-tx-data!` validates every transacted datom against the branch's live Malli registry before writing to LMDB.
- **Reactive LRU cache (`seon.store/lru-get!`):** Datahike block/index cache hits compose transparently with `datahike.reads/with-reads` (`datahike.index.interface/*reads*`), capturing and replaying index read ranges so cached UI walks and incremental rule evaluation never freeze or miss live SSE invalidations.
- **Runtime installation & safe migration:** `(user/register-schema! k form opts)` validates a `seed = 42` generator, installs derived `:db/ident` declarations immediately, and `seon.merge/three-way` blocks any merge that retypes an installed attribute or invalidates stored datoms.
## 2. Static Flow Graph & COW Fork Parent (`seon.main`, `seon.fork`, `seon.agents`)

Seon spawns a 1-thread `ForkParent` at the very start of `seon.main/-main` before Flow starts any worker threads or loads multi-threaded native libraries, guaranteeing safe `~6 ms` COW forks.

- **Static Flow topology (zero `flow/update!`):**
  - `:server` (`seon.server`): Request router for `/op` EDN and `/mcp` JSON-RPC 2.0.
  - `:transactor` (`seon.transactor`, `seon.transactor.calls`, `seon.transactor.merge`): Sole serialized writer of `:main`; executes 3-way merges, syncs active coordinator branches, advances `ForkParent` (`:advance-image`), and hot-loads merged deltas into the standing process.
  - `:reconciler` (`seon.reconciler`): Pure `O(|Δ|)` derivation of agent status (`:runnable`, `:running`, `:testing`, `:merging`, `:waiting`, `:merged`) — never stores derived status in Datahike.
  - `:agents` (`seon.agents`): Multiplexes logical agents onto `max-os-procs` warm persistent COW processes with LRU idle eviction and zero-PID cleanup on `:merged`.
  - `:model` (`seon.model`, `seon.model.wire`): Streaming OpenAI-compat / DeepSeek client over fiber-parking `jolt.http.core` (`StringBuilder` SSE accumulator, `reasoning_content` replay, `prompt_cache_hit_tokens`, `::flow/input-filter` backpressure). API keys stay exclusively in `:model`.
  - `:test` (`seon.test-runner`): Transitive reach selection (`seon.code.reach`), 2-tier fail-fast (`<= 150 ms` fast shard first), `seed = 42` generative checks, content-addressed green cache, and anti-test-deletion checks.
  - `:git` (`seon.git`, `seon.git-proc`): Lossless `Datahike :main === Git main` write-back and zero-worktree `refs/heads/seon/<branch>` export via `git fast-import`.
  - `:web` (`seon.web`, `seon.web.actions`, `seon.web.styles`, `seon.render.*`): 100% non-blocking HTTP/SSE server, 120 fps `datahike.reads` incremental walk, Living Canvas & Chronicle, and in-process `/mcp`.
  - `:browser` (`seon.browser`, `native/browser/seon_browser.m`): Embedded macOS `WKWebView` host (`target/seon-browser`) for ephemeral end-of-context visual feedback.

## 3. Codebase Indexing, Cold-Boot Sync & `O(|Δ|)` Rails (`seon.code.*`, `seon.merge`, `seon.switchover`)

Every `.clj`, `.md`, and `.edn` source artifact is parsed losslessly into structured Datahike entities (`:seon.code.ns/*`, `:seon.code.form/*`, `:seon.code.var/*`, `:seon.code.doc/*`, `:seon.code.file/*`, `:seon.schema/*`, `:seon.task/*`, `:seon.rule/*`).

- **Modular `< 8k` tok namespaces:** Code is organized into focused sub-namespaces (`seon.code.{cst,cst.ns,edit,files,io,parse,reach,refactor}`, `seon.fork.{calls,container,helpers,worker-support}`, `seon.health.{contracts,efficiency,metrics,static}`, `seon.render.{canvas,canvas.blocks,canvas.live,chronicle,data,debug,lineage,markdown,ns-tile,tail,tiles,ui,wire}`, `seon.rule` + `seon.rules.{effects,context,seed}`, `seon.web.{actions,css,styles}`, `seon.bench.{cluster,helpers,proxy}`, `seon.transactor.{calls,merge}`).
- **Datom-native logic rules engine (`seon.rule`, `seon.rule.effects`, `seon.rule.context`):** Stratified forward-chaining engine over EAV working memory and live Datahike indexes (`[:db :branch|:main|:code|:task [?e ?a ?v ?tx]]`), executing inside `datahike.reads/with-reads` (`step-rules` incremental invalidation) with cached deterministic boundary effects (`:effect/ground`, `:effect/search`, `:effect/tree-discoveries`), GTA-6 modifier tags (`:view/tag`), and cycle-safe Graph->Tree projection (`:view/parent`). All 43 starter rules live as editable `:seon.rule/*` entities persisted 1-to-1 to `resources/seon/rules.edn`.
- **Cold-boot overlay & unloaded namespace hydration:** On startup (reboot or `--seed-store` isolated benchmark instances), `seon.main/sync-cold-boot-forms!` overlays all `:seon.code.form/*` entities (both `src/` and `test/`, pulling `:seon.code.form/deps`) modified after `aot-build-ms`, while `seon.code/apply-overlay!` hydrates unloaded target namespaces (`#ns` + sibling forms) in topological order before overlaying deltas.
- **Automatic atomic switch-over (`seon.switchover`):** When a merge modifies boot inputs (`seon.main`, `seon.fork`, `deps.edn`, `native/*`), Seon runs `bin/build seon` -> `--self-test` -> graceful drain -> `--take-over 9998` automatically with zero manual restarts.
- **Mid-session branch sync:** Warm agent workers automatically hot-load newly merged `:main` deltas (`seon.fork/sync-conn-to-latest-branch-head!`) overlaid with their own branch edits before each eval.
- **Declarative persistence (`seon.code.files`):** `resources/seon/schemas.edn`, `resources/seon/tasks.edn`, `resources/seon/rules.edn`, `resources/seon/agents/{root,examples}.edn`, `resources/seon/config/default.edn`, `AGENTS.md`, `docs/*.md`, `src/`, and `test/` import on fresh install and write back atomically on every green merge.
- **Thin outside footprint:** `bin/mcp` (stdio relay to `POST /mcp`) and `bin/seon` (CLI relay to `POST /op`) contain zero business logic; `bin/build` bootstraps native C/Chez/Jolt/LMDB artifacts.
