---
type: research
status: completed
tags: [research, database, flow, web]
---

# JVM archive boundary (2026-07-13)

## TL;DR

The paused JVM application is not the foundation of the future server. The
future server already has a much smaller, coherent foundation: the Datahike
sole-writer process, transaction feed and replay, multi-database connection
registry, schema bridge, branch/restore work, and embedding/secondary-index
service. Its current production dependency closure is only thirteen
namespaces, one of which (`seon.server.reactive`) is a dead second reactive
system and should be deleted.

Archive means preserve one pre-removal commit through an annotated tag or
archive branch, add a concise historical pointer, then delete the paused source
from the active branch. Moving it under an `archive/` source tree would keep it
in searches, invite accidental reuse, and leave two apparent implementations.
Git is the archive.

The clean target is:

- JVM: authoritative Datahike writer, durable storage, branch/restore,
  differential replication, embeddings, and explicitly selected heavy
  database operations;
- CLJS on Node or Tauri: agent runtime, local read replica, context derivation,
  canvas/surfaces, Datastar web UI, and user interaction; and
- one database-change channel. The current Unix-domain-socket transaction feed
  remains the correctness baseline until a source-proven Kabel/konserve-sync
  experiment can replace it for remote/browser clients. It must not become a
  second permanent production path.

The old JVM agent loop, Integrant application, core.async flow topology,
session/context system, HTML renderer, HTTP/SSE server, nREPL MCP server, and
test harness do not implement that target. Keeping them “for future JVM
rendering” would preserve the wrong contracts. Server-side agents should run
the same CLJS pod beside the JVM writer. Any future JVM rendering must consume
the same pure surface data and Datastar contract as CLJS; it should not revive
the old Chassis/http-kit stack.

Two immediate hazards must be fixed during the cutover:

- `build.clj` says `writer-uber` includes `:simd` and `:fork-deps`, but its
  `create-basis` call selects only `[:writer]`. The production artifact build is
  not using the dependency composition its comments promise.
- `.claude/settings.json` and `.codex/hooks.json` still invoke `bin/seon-hook`.
  Its pre-edit Edamame syntax gate and direct Gemini review work in Babashka,
  but its normal post-edit pipeline still calls the paused JVM nREPL on port
  7888. The hook therefore silently loses reload, test, markdown, docstring,
  compliance, and repair behavior when that JVM is absent.

## Scope and evidence

This is a read-only dependency and entrypoint audit. No pod, writer, browser,
test process, or ACME process was operated. The working tree was not changed
except for this document.

The audit covered:

- all 117 `.clj` and `.cljc` files under `src/`;
- all 77 active JVM/shared `.clj`/`.cljc` test files plus all 17 `.disabled`
  tests;
- `deps.edn`, `build.clj`, `shadow-cljs.edn`, `tests.edn`, `bin/*`, Docker,
  environment resources, root config, and current hook configuration;
- require reachability from `seon.server.boot` and compile-time reachability
  from active Shadow builds;
- every JVM wire operation and its CLJS caller;
- the vendored Datahike distributed, CLJS, branch, storage, and Kabel source;
  and
- active architecture/runbook references to the paused process and ports.

The important source evidence is:

- Datahike's single-writer/DIS model in
  `reference-code/datahike/doc/distributed.md`;
- browser IndexedDB and TieredStore behavior in
  `reference-code/datahike/doc/cljs-support.md`;
- backend capabilities in
  `reference-code/datahike/doc/storage-backends.md`;
- Kabel client ordering and cache hydration in
  `reference-code/datahike/src-kabel/datahike/kabel/connector.cljc`; and
- the actual dependency packaging in `reference-code/datahike/deps.edn`.

## Target process boundary

The process boundary should be based on authority and cost, not language
symmetry.

| Responsibility | Authority | Target runtime |
|---|---|---|
| Serialize and commit transactions | Datahike writer | JVM server |
| Publish committed database coordinates and changes | Datahike writer | JVM server |
| Own durable branch heads and restore transitions | Datahike writer plus supervisor | JVM server |
| Durable cloud/tiered storage | Konserve/Datahike configuration | JVM server and read replicas |
| Embeddings and secondary indexes | Committed database facts | JVM server |
| Other measured heavy database work | Explicit typed server operations | JVM server |
| Query ordinary application state | Immutable local database value | CLJS reader |
| Run agents and their eval loop | Durable agent facts plus process handles | CLJS Node/Tauri |
| Derive context and render surfaces | Pure functions of database values | CLJS Node/Tauri |
| Serve the current Datastar UI | Canonical CLJS renderer | CLJS Node, or packaged client |

This gives a simple local deployment: one JVM writer plus one Node CLJS pod.
It also gives the remote deployment the owner described: a strong server and
potato clients. A Tauri client can hydrate a local IndexedDB-backed Datahike
reader, submit writes to the server, and apply differential updates. A hosted
agent is the same Node CLJS pod colocated with the writer, not a revived JVM
agent application.

## What “archive” means

Use source control as the archive:

1. Commit a clean, known working pre-removal boundary.
2. Create an annotated tag or protected archive branch such as
   `archive/jvm-main-app-2026-07-13` at that commit.
3. Add one concise document that identifies the tag, former entrypoints, and
   reason for retirement.
4. Delete paused source, tests, aliases, scripts, resources, and active runbook
   instructions from the working branch.

Do not move the old tree to `archive/src`. Historical research documents may
remain because they record evidence, but they must carry completed/archive
status and must not be linked as current operation guidance.

## Exact active JVM server closure

Static require reachability from `seon.server.boot` currently yields:

```text
seon.ai.tokens
seon.db.datahike.schema
seon.db.id
seon.embed
seon.schema
seon.schema.internal
seon.server.boot
seon.server.broadcast
seon.server.codec
seon.server.reactive
seon.server.registry
seon.server.store
seon.server.wire
```

After deleting the unused query-subscription engine, the closure is twelve
namespaces. The exact disposition is below.

### Keep active server

| Namespace/file | Disposition | Required correction |
|---|---|---|
| `src/seon/server/boot.clj` | KEEP ACTIVE SERVER | Keep composition, embedding registration, and paginated `replay-tx`. Delete durable query-subscription schema, engine registry, hook, and register/unregister operations. |
| `src/seon/server/broadcast.clj` | KEEP ACTIVE SERVER | Keep framed socket fanout and dead-subscriber cleanup. Delete the in-process per-database subscriber map/API used only by the dead reactive engine. |
| `src/seon/server/codec.clj` | KEEP ACTIVE SERVER | Keep as the one JVM Transit-JSON length-frame codec mirrored by `seon.store.internal.wire-node`. |
| `src/seon/server/registry.clj` | KEEP ACTIVE SERVER | Keep connection lifecycle, database-name routing, and keyed connection hooks. Delete the unwired `!agents` map plus register/unregister/resolve/list-agent functions when `seon.session` is archived; active CLJS requests route by database name. Replace physical store-copy forks with Datahike branch coordinates. |
| `src/seon/server/store.clj` | KEEP ACTIVE SERVER | Keep one server-owned Datahike/Konserve config compiler. Remove the currently unsupported fake SQLite choice and extend the same compiler for selected file/cloud/tiered backends. |
| `src/seon/server/wire.clj` | KEEP ACTIVE SERVER | Keep sole-writer transaction serialization, durable receipts, tx augmentation, raw broadcast, replay support, and typed heavy operations. Prune unused read/filter/batch operations after the protocol cut below. |
| `src/seon/embed.clj` | KEEP ACTIVE SERVER | Keep embedding-on-write, backfill, KNN, Proximum integration, and bounded heavy work. This is intentionally JVM-only heavy infrastructure, not a duplicate of the CLJS search client. |
| `src/seon/embed/preflight.clj` | KEEP ACTIVE SERVER | Keep the production artifact/environment preflight. |
| `src/seon/db/datahike/schema.clj` | KEEP ACTIVE SERVER | Keep the Malli-to-Datahike schema bridge used by the writer and embedding installer. Consolidate only if the shared schema compiler gains identical behavior. |

### Keep shared or port

| Namespace/file | Disposition | Reason/action |
|---|---|---|
| `src/seon/agent/fs/match.cljc` | KEEP SHARED/PORT | Active CLJS filesystem matching and replay harness contract. |
| `src/seon/ai/tokens.cljc` | KEEP SHARED/PORT | Canonical token estimator used by writer logs and CLJS. |
| `src/seon/code.cljc` | KEEP SHARED/PORT | Active CLJS code/data utilities. |
| `src/seon/db/id.cljc` | KEEP SHARED/PORT | One cross-runtime identity and serialized-allocation contract. |
| `src/seon/dev/runtime_id.cljc` | KEEP SHARED/PORT | Active Shadow/MCP runtime discovery. |
| `src/seon/diffusion/grammar.cljc` | KEEP SHARED/PORT | Active CLJS diffusion/eval grammar. |
| `src/seon/error/instrument.cljc` | KEEP SHARED/PORT | Active cross-runtime instrumentation envelope. |
| `src/seon/instrument.cljc` | KEEP SHARED/PORT | Active CLJS instrumentation owner. |
| `src/seon/repair.cljc` | KEEP SHARED/PORT | Canonical delimiter repair; supersedes `seon.dev.repair`. Remove the stale comparison to that deleted namespace from its active docstring. |
| `src/seon/repl/internal.cljc` | KEEP SHARED/PORT | Canonical parser used by CLJS and portable tests. |
| `src/seon/schema.cljc` | KEEP SHARED/PORT | Current Malli registry and schema API used by both runtimes. Delete the old `:seon.flow/dynamic` and `:seon.db/namespace` registrations when their only consumers are archived; continue the database-restored registry refactor in the active PRD. |
| `src/seon/schema/internal.cljc` | KEEP SHARED/PORT | Pure schema-form mechanics. |
| `src/seon/ui/components.cljc` | KEEP SHARED/PORT | Pure hiccup components. “Card” remains a visual CSS component only. Remove its obsolete promised merge with `seon.web.components.clj`. |
| `src/seon/ui/html.cljc` | KEEP SHARED/PORT | Canonical pure hiccup serialization contract; a future JVM adapter may consume the same input. |
| `src/seon/indexing.clj` | KEEP SHARED/PORT | Compile-time Shadow macro required by active `seon.client`, test preload, and downstream builds. It is `.clj` but not the paused application. |
| `src/seon/server/client.clj` | KEEP SHARED/PORT | Move to `test/` as the integration-test client; do not ship it as server production source. |
| `src/seon/dev/markdown.clj` | KEEP SHARED/PORT | Pure useful linter, but port behind a direct Babashka/CLI door rather than the retired nREPL hook. |
| `src/seon/dev/docstring.clj` | KEEP SHARED/PORT | Same: retain behavior only if invoked directly by the one lightweight developer check. |

### Delete dead duplicates and scratch code

| Namespace/file | Disposition | Evidence |
|---|---|---|
| `src/seon/server/reactive.clj` | DELETE DEAD DUPLICATE | A second durable query-subscription/invalidation system. CLJS consumes raw tx/replay and never consumes its changed summaries. |
| `src/seon/server/transit.clj` | DELETE DEAD DUPLICATE | String Transit helper used only by its property test. `seon.server.codec` is the whole-frame codec. |
| `src/seon/server/registry.clj` agent-routing subset | DELETE DEAD DUPLICATE | Only old `seon.session` and JVM tests populate `!agents`; no live wire operation registers an agent. The pod sends `:seon.store.wire/db-name` on ensure, transact, and replay. |
| `src/seon/experimental/sci_exploration.clj` | DELETE DEAD DUPLICATE | Orphan scratch namespace. |
| `src/seon/ns/example.clj` | DELETE DEAD DUPLICATE | Old JVM namespace UI sample. |
| `src/seon/phase2/demo.clj` | DELETE DEAD DUPLICATE | Old app demo. |
| `src/seon/dev/analysis.clj` | DELETE DEAD DUPLICATE | Orphan in-process kondo analysis; current lint command uses the external tool. |
| `src/seon/dev/clojure_replace.clj` | DELETE DEAD DUPLICATE | Backend only for the retiring JVM MCP server. |
| `src/seon/dev/codebase.clj` | DELETE DEAD DUPLICATE | Used only by the retiring nREPL hook. |
| `src/seon/dev/compliance.clj` | DELETE DEAD DUPLICATE | Old JVM convention checker; the active CLJS warning/instrumentation path owns runtime checks. |
| `src/seon/dev/context.clj` | DELETE DEAD DUPLICATE | Ephemeral hook/edit state for the retired JVM hook pipeline. |
| `src/seon/dev/hook.clj` | DELETE DEAD DUPLICATE | nREPL-hosted hook orchestrator; retain only direct Babashka behaviors in `bin/seon-hook`. |
| `src/seon/dev/instrumentation.clj` | DELETE DEAD DUPLICATE | Integrant/JVM sibling superseded by `seon.instrument`. |
| `src/seon/dev/lint.clj` and `src/seon/dev/suggestions.clj` | DELETE DEAD DUPLICATE | Old in-process hook lint/suggestion path; use one direct syntax/kondo door. |
| `src/seon/dev/repair.clj` | DELETE DEAD DUPLICATE | Superseded by `src/seon/repair.cljc`; the old cljfmt side effect is not part of repair. |
| `src/seon/dev/review.clj` | DELETE DEAD DUPLICATE | Duplicate Gemini reviewer; `bin/seon-hook` already has one runtime-independent bounded reviewer. |
| `src/seon/dev/test.clj`, `src/seon/dev/test_select.clj`, `src/seon/dev/verify.clj` | DELETE DEAD DUPLICATE | Old all-JVM test selection/execution framework. Focused CLJS and writer test doors replace it. |

### Archive paused application

Every file below is removed from the active branch and recovered through the
archive tag if historical investigation is needed.

| Area | Exact namespaces/files |
|---|---|
| JVM agent and providers | `src/seon/agent/env.clj`; `src/seon/ai.clj`; `src/seon/ai/agent.clj`; `src/seon/ai/agent/log.clj`; `src/seon/ai/agent/views.clj`; `src/seon/ai/claude.clj`; `src/seon/ai/claude/sdk.clj`; `src/seon/ai/gemini.clj`; `src/seon/claude/exploration.clj` |
| Application composition | `src/seon/config.clj`; `src/seon/core.clj`; `src/seon/runner.clj`; `src/seon/runtime.clj`; `src/seon/system.clj`; `src/seon/system/config.clj`; `src/seon/health.clj`; `src/seon/logging.clj`; `src/seon/session.clj` |
| Old context/program graph | `src/seon/ctx.clj`; `src/seon/ctx/history.clj`; `src/seon/graph/analyzer.clj`; `src/seon/graph/context.clj`; `src/seon/graph/extract.clj`; `src/seon/graph/ingest.clj`; `src/seon/graph/query.clj`; `src/seon/graph/scanner.clj`; `src/seon/ns/introspect.clj`; `src/seon/ns/lifecycle.clj`; `src/seon/ns/routes.clj`; `src/seon/ns/view.clj` |
| Embedded DB and core.async flow | `src/seon/db.clj`; `src/seon/db/relay.clj`; `src/seon/db/schema.clj`; `src/seon/db/tx.clj`; `src/seon/db/datahike/conn_process.clj`; `src/seon/db/datahike/flow.clj`; `src/seon/db/datahike/system.clj`; `src/seon/db/datahike/tx_bus.clj`; `src/seon/flow/agent_runner.clj`; `src/seon/flow/harness.clj`; `src/seon/flow/harness/bridge.clj`; `src/seon/flow/harness/channel.clj`; `src/seon/flow/harness/proxy.clj`; `src/seon/flow/msg.clj`; `src/seon/flow/pool.clj`; `src/seon/flow/status.clj`; `src/seon/flow/topology.clj`; `src/seon/flow/trace.clj` |
| Old REPL | `src/seon/repl.clj`; `src/seon/repl/context.clj` |
| JVM render/UI | `src/seon/render.clj`; `src/seon/render/code.clj`; `src/seon/render/default_page.clj`; `src/seon/ui/viewer.clj` |
| JVM HTTP/SSE | `src/seon/web/brotli.clj`; `src/seon/web/browser.clj`; `src/seon/web/caddy.clj`; `src/seon/web/components.clj`; `src/seon/web/flows.clj`; `src/seon/web/handlers.clj`; `src/seon/web/html.clj`; `src/seon/web/logs.clj`; `src/seon/web/namespace.clj`; `src/seon/web/routes.clj`; `src/seon/web/server.clj`; `src/seon/web/sse.clj`; `src/seon/web/tailwind.clj`; `src/seon/web/reactive/actions.clj`; `src/seon/web/reactive/demo.clj`; `src/seon/web/reactive/transform.clj`; `src/seon/web/sse/flow.clj` |

The old JVM renderer is specifically not a portable renderer to retain.
`src/seon/web/reactive/transform.clj` emits the old route and action contract,
while its `.cljs` sibling emits current `/agent/{id}/call` actions and Datastar
attributes. The two files are not equivalent implementations. The reusable
boundary is hiccup/surface data plus the Datastar protocol, not the old JVM
handlers.

## Wire operation cut

The active CLJS runtime calls only these writer operations:

- `ping`;
- `ensure-db`;
- `transact`;
- `replay-tx`; and
- `knn-search` when embeddings are enabled.

`seon.store.internal.wire-node` also exposes development wrappers for `q`,
`pull`, and `schema`, but the production pod performs ordinary reads against
its local immutable Datahike value. No active CLJS caller was found for:

- `transact-batch`;
- `entity-pull`;
- `pull-many`;
- `reverse-schema`;
- `db-filter`, `q-filtered`, or `filter-release`; or
- `register-subscription` and `unregister-subscription`.

`list-dbs` and `remove-db` are not used by the CLJS pod; current supervisor
commands evaluate `seon.server.registry` functions through the writer REPL.

Recommended disposition:

| Operation family | Classification | Action |
|---|---|---|
| `ping`, `ensure-db`, `transact`, `replay-tx`, `knn-search` | KEEP ACTIVE SERVER | Retain and specify as the minimum live protocol. |
| query subscriptions | DELETE DEAD DUPLICATE | Delete with `seon.server.reactive`. |
| filter handles and remote entity/pull helpers | DELETE DEAD DUPLICATE | Delete after one focused call-graph test confirms no external consumer. |
| `transact-batch` | DELETE DEAD DUPLICATE | No consumer; batching belongs behind the writer implementation when measured, not as an unused alternate public write path. |
| generic `q`/`pull`/`schema` | DECISION NEEDED | Keep only if a named remote/admin capability requires them. Ordinary application reads remain local. |
| `list-dbs`/`remove-db` and branch/restore admin | DECISION NEEDED | Replace arbitrary REPL expressions with a small typed admin protocol, then remove production REPL administration. |

Do not delete `q` merely because the current pod does not call it if the chosen
remote debugging contract explicitly needs it. Make that query contract a
named server capability rather than retaining every old sidecar operation.

## Scripts, config, resources, and build inventory

### Entrypoints

| Surface | Classification | Action |
|---|---|---|
| `bin/seon` | KEEP SHARED/PORT | Rewrite as the one robust supervisor door, preferably Babashka. Remove the `jvm` process, ports 7888/8080, and old start/help branches. Keep writer, CLJS build/watch, pod, status, logs, and reset. |
| `bin/test-cljs` | KEEP SHARED/PORT | Keep as the active CLJS behavioral gate. |
| `bin/mcp-server-cljs` | KEEP SHARED/PORT | Keep as the active multi-runtime MCP bridge. |
| `bin/seon-server-call` | DECISION NEEDED | Temporary writer-admin bridge. Replace arbitrary Clojure eval with typed admin operations before calling the server artifact production-ready. |
| `bin/_java-home-resolver` | KEEP ACTIVE SERVER | Keep JDK selection for writer/build/tests. |
| `bin/agent-eval`, `bin/oracle-server`, `bin/test-parser`, `bin/fix-bootstrap-macros` | KEEP SHARED/PORT | Active CLJS/eval/build utilities; independent of paused app. |
| `bin/lint`, `bin/issues-index` | KEEP SHARED/PORT | Lightweight developer tools. |
| `bin/replay-gold-patches` and `bin/replay_gold_patches.clj` | DECISION NEEDED | Not paused JVM app code, but retain only if the Inspect AI/evaluation plan still consumes this evidence harness. Do not put it on the writer classpath. |
| `bin/run`, `bin/nrepl`, `bin/agent-runner` | ARCHIVE PAUSED APPLICATION | Delete after tagging the old app. |
| `bin/mcp-server` | DELETE DEAD DUPLICATE | Old 1,300-line JVM/nREPL MCP server; CLJS MCP is canonical. |
| `bin/run-datalevin` | DELETE DEAD DUPLICATE | Script declares itself dead and no Datalevin dependency/source remains. |
| `bin/test` | ARCHIVE PAUSED APPLICATION | Replace with a writer-focused command; do not keep a generic command that discovers every archived JVM test. |

### Config and resources

| Surface | Classification | Action |
|---|---|---|
| `config/system.edn` | KEEP SHARED/PORT | Active CLJS/database manifest. |
| `config/test.edn` and `config/minimal*.edn` | KEEP SHARED/PORT | Keep only scenarios still used by active CLJS tests/manual proof; prune through the config audit, not JVM archival. |
| `config/acme.edn` | KEEP SHARED/PORT | Downstream harness; out of scope for this cutover and must not be touched in this lane. |
| `config/legacy.edn` | DELETE DEAD DUPLICATE | Legacy manifest. |
| `resources/public/**` | KEEP SHARED/PORT | Current CLJS web assets. |
| `resources/system.edn`; `resources/integrant/hierarchy.edn` | ARCHIVE PAUSED APPLICATION | Integrant/JVM application config. |
| `resources/seed/facts-schema.edn`; `facts-seed.edn` | DELETE DEAD DUPLICATE | Dead sidecar fact-blob proof, not the current schema/data model. |
| `resources/sample-data/databento-sample.edn` | DELETE DEAD DUPLICATE | Unreferenced product/sample data. |
| `env/dev/clj/**`; `env/prod/clj/**`; their logback resources | ARCHIVE PAUSED APPLICATION | Old Integrant/nREPL application profiles. |
| `env/test/resources/logback.xml` | DECISION NEEDED | Keep only if selected writer tests require it; otherwise remove with the old test classpath. |
| `docker/Dockerfile`; `docker/seon-entrypoint` | KEEP ACTIVE SERVER | Current production composition of writer plus Node pod. Later produce separable server and Java-free client artifacts without duplicating runtime semantics. |

### Adjacent prototype trees

`client-runtime/host`, `pod-host/libdatahike-cljs`, and
`pod-host/wasm-tauri` are old V2/WASM/sidecar prototypes. They are not called by
the active supervisor. The repository has a settled no-WASM direction, and the
current root CLJS pod has superseded the duplicated libdatahike/MCP build.
Preserve them at the archive tag and delete them from the active tree. A future
Tauri client should package the canonical root CLJS client and IndexedDB sync;
it should not evolve the old WASM guest.

`pod-host/datahike-harness` is evidence rather than production. Move any still
valuable measured result into dated research, then delete the harness if no
active command consumes it.

## Dependency and alias pruning

### Target dependency ownership

The current base `:deps` is inherited by both Shadow and every writer/test
process. It therefore puts paused application dependencies on the writer
classpath even when the writer never loads their namespaces. The target should
have a minimal shared base and explicit runtime aliases:

- shared base: Clojure and Malli only, plus nothing that is not truly required
  by both compilation tracks;
- `:cljs`: CLJS compiler/runtime, Datahike CLJS, SCI, Aero, core.async,
  parinferish, rewrite-clj, Transit CLJS, Reitit, and other actual CLJS deps;
- `:writer`: the exact Datahike/Konserve forks, `src-secondary`, vector JVM
  flags, Transit CLJ, Proximum, Google GenAI, Timbre, identity adapters, and a
  selected durable backend; and
- `:writer-test`: Kaocha/test.check plus test source, composed with `:writer`.

Move `-m seon.server.boot` out of the dependency alias and into the launch
command. Then `:writer` can be composed with `:writer-test` without conflicting
main options.

### Exact base dependency disposition

| Current dependency/group | Classification | Target |
|---|---|---|
| `org.clojure/clojure`, `metosin/malli` | KEEP SHARED/PORT | Minimal shared base. |
| Datahike and Konserve pinned forks | KEEP ACTIVE SERVER and CLJS | Directly select the coordinated SHAs in each runtime alias; do not rely on accidental transitives. |
| `com.taoensso/timbre` | KEEP ACTIVE SERVER | Writer alias only. |
| `com.cognitect/transit-clj` | KEEP ACTIVE SERVER | Writer alias only. |
| `org.replikativ/proximum`, `com.google.genai/google-genai` | KEEP ACTIVE SERVER | Writer alias only; embeddings remain optional at runtime, present in the server artifact. |
| `com.github.kkuegler/human-readable-ids-java`, `io.github.thibaultmeyer/cuid` | KEEP ACTIVE SERVER | Writer side of the one `.cljc` ID adapter, in writer alias. NPM adapters remain in the client package. |
| `org.replikativ/hasch` | KEEP ACTIVE SERVER | Add as a direct writer dependency because `seon.server.wire` requires it; do not depend on Proximum's transitive POM. |
| `konserve-jdbc`, `sqlite-jdbc` | DELETE DEAD DUPLICATE | Current `:sqlite` path is documented as unsupported yet `wire.clj` requires it at load time. Delete the fake backend and dependency; add only a proven backend when selected. |
| `logback-classic` | DECISION NEEDED | Pick one JVM logging provider. Proximum already brings `slf4j-simple`; shipping both providers is unnecessary/conflicting. |
| `org.clojure/core.async` | KEEP SHARED/PORT | CLJS alias only. Remove the old JVM version from base. The writer is synchronous/threaded, not the old flow topology. |
| `aero` | KEEP SHARED/PORT | CLJS alias only for `seon.config.cljs`. |
| `parinferish`, `rewrite-clj` | KEEP SHARED/PORT | CLJS/dev-tool alias only. |
| `edamame` | KEEP SHARED/PORT | Direct Babashka hook/tooling only; no writer dependency. |
| `clj-kondo`, `cljfmt` | KEEP SHARED/PORT | External/dev tooling only if still used; no runtime base dependency. |
| `tick` | DELETE DEAD DUPLICATE | No active source require was found. |
| Integrant | ARCHIVE PAUSED APPLICATION | Remove with `seon.system` and old app. |
| `libpython-clj`, `dtype-next`, `tech.ml.dataset` | DELETE DEAD DUPLICATE | No active source require; unrelated heavyweight base load. |
| Hato, Cheshire | ARCHIVE PAUSED APPLICATION | Used only by old JVM AI/browser providers. |
| http-kit | ARCHIVE PAUSED APPLICATION | Used only by old JVM web/SSE today. Kabel may add it later behind an isolated chosen transport alias, not retain it speculatively in base. |
| Nippy | ARCHIVE PAUSED APPLICATION | Used by old DB relay and flow harness. |
| Chassis, markdown-clj | ARCHIVE PAUSED APPLICATION | Old JVM renderer only. |
| Jsonista | ARCHIVE PAUSED APPLICATION | Old JVM web handlers only. |
| Brotli4j native artifacts and Netty buffer | ARCHIVE PAUSED APPLICATION | Old JVM SSE compression only; current CLJS web path uses Node gzip. |

### Alias and build disposition

| Alias/build target | Classification | Action |
|---|---|---|
| `:writer` | KEEP ACTIVE SERVER | Make it complete: fork overrides, secondary source, vector flags, and all writer deps in one alias. |
| `:simd`, `:fork-deps` | DELETE DEAD DUPLICATE after fold | They currently force every caller to know alias composition. Fold their data into the one writer runtime surface and the corresponding CLJS override. |
| `:writer-test` | KEEP ACTIVE SERVER | New narrow alias replacing the broad old `:test`. |
| `:cljs` | KEEP SHARED/PORT | Active build. Remove normal-client test/demo preloads separately so production pays only for production. |
| `:build`, `:lint` | KEEP SHARED/PORT | Build/tool aliases. |
| `:dev`, `:prod`, `:run`, `:nrepl`, `:agent-jvm-pool` | ARCHIVE PAUSED APPLICATION | Delete with the old application. |
| current `:test` | ARCHIVE PAUSED APPLICATION | Replace with writer-specific discovery; do not retain every old `.clj` test on the active classpath. |
| `build.clj` `writer-uber` | KEEP ACTIVE SERVER | Correct its basis and keep as the only production JVM artifact. |
| `build.clj` `uber` with `seon.core` | ARCHIVE PAUSED APPLICATION | Delete. |
| `build.clj` generic `jar` | DECISION NEEDED | Keep only if Seon is intentionally published as a source library; otherwise the server uberjar is the product artifact. |

The existing `writer-uber` defect is mechanical: it calls
`b/create-basis {:aliases [:writer]}` while live launch calls
`:simd:fork-deps:writer`. Copying `reference-code/datahike/src-secondary` into
the classes directory does not put the Datahike fork and its coordinated
Konserve dependency on that basis. Consolidating `:writer` makes build, launch,
Docker, and tests resolve the same graph.

## Test inventory

### Keep as writer/server tests

Keep and adapt these behavioral tests:

- `test/seon/server/generated_id_transaction_test.clj`;
- `protocol_integration_test.clj`;
- `registry_routing_test.clj` and `registry_test.clj` after the final attachment
  and branch APIs replace old map/copy behavior;
- `store_test.clj` after unsupported SQLite assertions are removed and selected
  backend configuration is covered;
- `tx_feed_replay_test.clj`;
- `wire_request_id_test.clj`, with stale changed-summary wording removed;
- `wire_types_test.clj`;
- the active frame/transaction parts of `wire_props_test.clj`, retargeted from
  `seon.server.transit` to `seon.server.codec`;
- `test/seon/embed_writer_test.clj`;
- `test/seon/db/datahike/schema_test.clj`;
- `test/seon/db/id_test.cljc`; and
- `test/seon/server/test_util.clj`, plus the moved test-only writer client.

`temporal_read_test.clj` should be adapted to the chosen typed historical-read
or simulation operation. The behavior is strategic, but the unused generic
remote `q` operation is not automatically strategic.

### Delete with dead writer paths

- `test/seon/server/reactive_test.clj`;
- the query-subscription content in `boot_test.clj`;
- `broadcast_routing_test.clj`, which covers only the in-process subscriber
  path; socket fanout remains covered by protocol integration;
- `facts_test.clj`;
- `overlay_semantics_test.clj`, which explicitly targets the retired WASM
  sidecar overlay;
- `protocol_extensions_test.clj` when the unused remote read/filter operations
  are removed;
- `transact_batch_test.clj` when the unused public batch operation is removed;
  and
- Transit-string-only properties in `wire_props_test.clj`.

### Archive old application tests

Archive/delete every `.clj` test for these removed namespaces:

- `seon.agent.env`, all old `seon.ai.*`, `seon.core`, `seon.ctx.*`;
- old `seon.db`, routing, pipeline, validation, and consistency;
- old `seon.graph.*`, `seon.logging`, `seon.ns.*`;
- JVM render, REPL, session, system, and JVM web/reactive/SSE;
- the old `seon.dev.*` hook/test framework, retaining only narrow tests for any
  directly ported markdown/docstring tool; and
- `test/seon/test_utils.clj`, whose fixtures are built around the archived flow
  and graph system.

Delete the entire disabled-test graveyard rather than carrying it forward:

```text
test/seon/_disabled/session_test.clj.disabled
test/seon/db/datahike/flow_test.clj.disabled
test/seon/flow/*.clj.disabled
test/seon/runtime_test.clj.disabled
test/seon/session_test.clj.disabled
test/seon/system/config_test.clj.disabled
test/seon/web/sse/flow_test.clj.disabled
```

The active CLJS runner continues to own the shared `.cljc` tests for filesystem
matching, IDs, repair, REPL parsing, and HTML. The writer gate should not
rediscover the entire CLJS test tree.

### Target test doors

- `bin/test-cljs [namespace ...]`: active CLJS behavior.
- `bin/test-writer [namespace ...]`: only writer, embedding, schema bridge, ID,
  branch/restore, codec, and storage behavior.
- Inspect AI: model/agent journeys. No homegrown gym or skip-only pseudo-tests.
- Datahike/Konserve fork suites: library regressions belong in the fork that
  owns them, not copied into Seon response-text tests.

## Datahike, cloud storage, and Kabel implications

### What is already supported by the model

Vendored Datahike describes exactly the desired authority split:

1. the single writer serializes transactions;
2. immutable index nodes land in shared storage;
3. an updated branch/root pointer is published atomically; and
4. readers query local immutable snapshots without a read RPC.

File, S3, GCS, JDBC, and IndexedDB are Konserve backend possibilities, not
features automatically activated by the current Seon store compiler. Current
`seon.server.store` supports memory/file and carries a nonworking SQLite branch.
S3 or GCS therefore requires selection and direct integration/testing of the
corresponding Konserve backend. Do not add a Seon-specific cloud object layout
beside Konserve.

### What Kabel actually supplies

The vendored Kabel integration combines two separate flows:

- write RPC to the authoritative writer; and
- `konserve-sync` replication of changed immutable store keys plus the mutable
  branch head into a local store.

For browser clients, the connector hydrates the memory frontend from IndexedDB
before subscribing, then waits for the complete sync handshake before exposing
the database. The branch head alone is not readiness; every referenced index
node must have arrived. Ongoing transaction-report broadcast is distinct from
store-key replication.

The integration is beta and not currently part of Datahike's published base
source path. `reference-code/datahike/deps.edn` exposes `src-kabel` only through
the test alias and adds Kabel, distributed-scope, konserve-sync, and http-kit
there. Seon cannot safely “turn Kabel on” by adding one dependency. It must
first decide whether to promote/package that source in the maintained Datahike
fork and prove its ordering, resume, branch, backpressure, and reconnect
semantics.

### One production change mechanism

Use the current UDS transaction/replay feed as the local correctness baseline.
Run Kabel/konserve-sync in an isolated integration spike against the same
writer invariants:

- one committed transaction is observed once logically;
- reconnect from a persisted coordinate loses nothing;
- immutable nodes arrive before the branch pointer becomes usable;
- branch identity is explicit;
- queues and replay pages are bounded;
- clients can discard and rebuild a local replica; and
- a restore causes a clear coordinate change and deterministic resync.

If the spike passes, adopt the Datahike mechanism as the remote transport and
retire the equivalent custom remote replication path. Do not run two reactive
systems indefinitely. Local UDS may remain an adapter only if it implements the
same logical writer/change contract and is materially useful for colocated
processes.

## Developer hook boundary

Archiving port 7888 without changing the configured hook would leave a false
sense of safety.

Keep these direct Babashka behaviors in one lightweight hook:

- pre-edit Edamame parse validation;
- the current bounded, rate-limited direct Gemini review if desired; and
- the current CLJS core-fault log gate if it remains useful.

Port the pure markdown/docstring checks behind that same direct process or a
single explicit lint command. Delete nREPL session resolution, backup/restore
of hook source, JVM reload, old affected-test selection, compliance, cljfmt
repair, and duplicate review orchestration. Test selection should be explicit
from changed namespaces through the CLJS/writer test doors, not an implicit
call into an absent application JVM.

Update `.claude/settings.json`, `.codex/hooks.json`,
`.claude/seon-hook.edn`, root instructions, and developer-tool docs in the same
commit so the stated behavior matches the actual behavior.

## Documentation and skill cut

Keep the ideal architecture and active runtime-reliability PRD current. Update
these active entrypoints as part of the cutover:

- `README.md`, `AGENTS.md`, `CLAUDE.md`, `AGENT.md`, and `ORCHESTRATOR.md`;
- `docs/seon/architecture/architecture.md`, `agent-runtime.md`, `data-model.md`,
  `ui.md`, `observability.md`, and `toolkit.md`;
- `docs/seon/process-management.md`;
- `docs/seon/components/testing.md`, `dev-tools.md`, and
  `flow-topology.md`;
- `docs/seon/reference/third-party-setup.md` and
  `separate-jvm-exploration.md`; and
- the Clojure testing, Datahike, Datastar UI, and context-config skills where
  they mention the old process or duplicate renderer.

Archive the old unified-flow and JVM namespace-UI implementation plans as
historical PRDs. Preserve dated research responses verbatim; change their
status/indexing rather than rewriting historical claims. Remove all current
runbook instructions for `bin/run`, `(user/go)`, `(user/run-tests)`, port 7888,
port 8080, `bin/mcp-server`, the `jvm` supervisor process, old sessions, and the
old JVM renderer.

The vocabulary sweep is cross-cutting but mechanically separate: canvas is the
focal agent-controlled area, surface is a renderable context view, and card is
only a visual CSS component. Remove tile/live-tile and world from active APIs,
code, config, skills, and architecture. Historical research may retain its
original wording when clearly archived.

## Mechanically safe cutover sequence

### Phase 0 — freeze and prove the baseline

1. Let every currently owned change land; start from a clean, coordinated
   commit.
2. Record the current active writer namespace closure, dependency tree, server
   tests, CLJS compile, cold reset, and one live agent transaction/feed replay.
3. Create the archive tag/branch at that known commit.

### Phase 1 — make the retained server independently buildable

1. Consolidate the complete fork/SIMD/dependency graph into `:writer`.
2. Move the writer main function to the launch command.
3. Add a narrow `:writer-test` alias and `bin/test-writer`.
4. Correct `writer-uber` to use the exact same basis as live launch.
5. Build and preflight the uberjar before deleting any old source.

Exit proof: a clean JVM process can start only `seon.server.boot`, open a fresh
and existing store, commit a transaction, publish/replay it, run a KNN query
when enabled, and drain cleanly. No Integrant, old app, or nREPL 7888 namespace
is loaded.

### Phase 2 — remove duplicate writer mechanisms

1. Delete `seon.server.reactive`, its boot schema/ops/hooks, and in-process
   broadcast subscribers.
2. Delete `seon.server.transit` and retarget codec properties.
3. Delete facts seed/resources and the WASM overlay tests.
4. Delete the registry's unwired agent map and agent-id request-routing branch;
   keep database-name routing as the cluster attachment coordinate.
5. Remove unused filter/entity/pull/batch wire operations after the explicit
   caller audit is pinned.
6. Keep replay pagination, socket fanout, receipts, and the raw committed tx
   feed.

Exit proof: active CLJS connect/transact/reconnect and live Datastar updates use
one tx feed; no changed-summary/subscription entity or second invalidation path
exists.

### Phase 3 — detach developer tooling from the paused JVM

1. Port the selected pure markdown/docstring checks to the direct hook/tool
   door.
2. Remove the nREPL path and all dead `seon.dev.*` orchestration.
3. Prove a syntax failure, a markdown structural warning, and each selected
   focused test command without a JVM application process.

### Phase 4 — remove old entrypoints and application

1. Remove the `jvm` process from `bin/seon` and delete `bin/run`, `bin/nrepl`,
   `bin/agent-runner`, `bin/mcp-server`, and `bin/run-datalevin`.
2. Delete paused aliases, old `seon.core` build targets, Integrant resources,
   environment profiles, and old config/sample data.
3. Delete the paused source and tests listed above.
4. Delete the adjacent WASM/libdatahike/client-runtime prototypes after their
   relevant measurements are linked from research.
5. Prune dependencies and regenerate any lock/build artifacts through their
   owners.

Exit proof: searches find no live runtime reference to the removed namespaces,
entrypoints, ports, resources, or aliases. Shadow still resolves
`seon.indexing.clj` and every active `.cljc` namespace.

### Phase 5 — update truth and cold-prove the product

1. Update architecture, runbooks, skills, help text, Docker, and startup docs.
2. From a cold checkout/dependency cache where practical, compile the CLJS
   client and writer artifact.
3. Run the focused writer gate and affected CLJS gate.
4. Destructively reset only the authorized default cluster, start writer and
   pod through the one supervisor, and verify status/readiness.
5. Mint and resume agents, commit data, reconnect the tx feed, load the web UI,
   submit a canvas form, and verify the UI morphs from the committed fact.
6. Verify no paused `jvm`, 7888, or 8080 process starts, and no client install
   requires Java beyond the server deployment.
7. Only after the default cluster passes should the separately owned ACME
   harness be rebuilt/reset by its owner.

### Phase 6 — evolve the retained server, not the archive

1. Replace registry physical copies with the already researched Datahike
   branch/restore primitives.
2. Select and prove S3 or GCS through a real Konserve backend and tiered store
   configuration.
3. Package and falsify the Kabel/konserve-sync path in isolation.
4. Once chosen, make the remote client use that one writer/change contract.
5. Build Tauri around the canonical CLJS runtime and IndexedDB replica.

These are forward server/client work. They do not justify retaining the old
Integrant application or JVM renderer.

## Acceptance invariants

The archival refactor is complete only when all of these are true:

- There is one authoritative writer and one production transaction-change
  mechanism.
- Every normal read happens against a local immutable database value unless a
  named heavy/admin operation explicitly requires the server.
- A reconnect has a persisted coordinate and lossless bounded recovery.
- Branch and restore operate on explicit database coordinates, not copied
  directory conventions.
- The server artifact dependency tree contains no old AI agent, Integrant,
  core.async flow, Chassis, markdown, Brotli JVM SSE, Python/dataframe, or JVM
  MCP dependency.
- The CLJS build still sees required `.clj` macros and shared `.cljc` files.
- The active test suite discovers only active behavior; disabled/archived tests
  are absent rather than skipped.
- Server-side agents run the canonical CLJS runtime beside the writer.
- The canonical web renderer is the CLJS surface/Datastar implementation.
- Active docs and skills contain no instruction to start the paused JVM app.
- Historical code is recoverable from the archive tag, but absent from normal
  source search and classpaths.

## Decisions that do not block archival

| Question | Recommended default |
|---|---|
| Tag or in-repo archive directory? | Annotated tag/protected archive branch plus one pointer doc; delete from active tree. |
| S3 or GCS first? | Choose one through deployment constraints. GCS aligns with current Vertex use, but backend maturity and integration tests decide. Do not ship both speculatively. |
| Keep arbitrary writer REPL administration? | Temporary only. Replace with typed root/supervisor admin operations. |
| Keep generic remote Datalog? | Only if a concrete debugging/admin capability is named and bounded. Ordinary clients read locally. |
| Keep public transaction batching? | No consumer means delete. Add internal batching only after measurement. |
| Revive JVM rendering? | No. Keep the pure surface/hiccup/Datastar contract; run canonical CLJS server-side when hosted rendering is needed. |
| Adopt Kabel now? | No production cutover without a falsifying spike. If adopted, it replaces the equivalent custom remote replication path. |

## Final classification summary

| Classification | Result |
|---|---|
| KEEP ACTIVE SERVER | Writer boot, socket broadcast, codec, registry, store compiler, wire transaction/feed, embeddings/preflight, Malli-to-Datahike bridge. |
| KEEP SHARED/PORT | Active `.cljc` contracts, Shadow compile macro, test-only writer client, selected direct developer linters, CLJS supervisor/build/MCP/test doors. |
| ARCHIVE PAUSED APPLICATION | Integrant/core.async JVM app, old agent/providers, context/graph/session, embedded DB flow, JVM REPL, renderer/web/SSE, app profiles/entrypoints/tests. |
| DELETE DEAD DUPLICATE | JVM query-subscription engine, Transit-string codec, facts POC, old JVM MCP, dead Datalevin/demo/scratch code, duplicated dev hook/test/instrumentation paths, disabled tests. |
| DECISION NEEDED | Typed writer admin/query surface, cloud backend selection, direct dev-lint packaging, generic source jar, and Kabel adoption after proof. None requires retaining the paused application. |
