---
type: research
status: completed
tags: [research, database, flow, agent, web]
---

# Active CLJS pod mutable-runtime census

## TL;DR

The active pod does not have one undifferentiated “global state” problem. It
has four different things that are currently mixed together:

- necessary process handles: the Datahike connection, compiler/analyzer,
  sockets, timers, child processes, gzip streams, and fiber-local scopes;
- database-derived projections: Malli, routes, render catalogs, config views,
  agent hosts, and feed dependencies;
- durable facts that are currently represented only by volatile pointers; and
- duplicate authorities: a second connection slot, several creation seams,
  three SSE registries, source/program rosters beside the DB, and globals that
  remember semantic status already knowable from live handles or facts.

The highest-risk findings are concrete:

1. `seon.client/!agent-conn` duplicates `seon.db/*conn*` and is the guard that
   decides whether crash recovery runs. A root replacement can make those two
   disagree.
2. `seon.store.wire/!adapter` does not retain the active pub socket or reconnect
   timer, has no stop/reset operation, and is keyed by a bare DB name. Its
   separate `!own-write-ids` is not `defonce`, so hot reload can erase in-flight
   echo suppression.
3. The web surface has three independent SSE registries:
   `serve/!sse-connections`, `datastar/!feeds`, and
   `debug/!sse-by-agent`. `serve/stop!` closes none of the Datastar/debug
   responses or gzip streams, so `http.Server.close` can wait on streams the
   lifecycle owner cannot reach.
4. Successful eval values are retained twice on `globalThis`; the raw
   `__seon_results_*` copy is never capped or deleted. Test-run results are
   also process-only and unbounded; each recording entrypoint stashes once,
   but old runs never evict.
5. `seon.agent.run/!runs-this-process`, `seon.agent.loop/!loop-input`, and
   `seon.dev.runtime-id/!hosted` only grow. Closed runs and terminated agents
   are not removed. These are both stale routing facts and retention roots for
   strings/closures/compiler state.
6. `seon.schema/*schemas` is still the live schema authority. The DB and the
   async tee are parallel durability paths. The many schema-registration
   `defonce`s also retain successive registry-map snapshots as their values.
7. `seon.eval/!timeout-ms` and `!next-budget-ms` are process-global agent
   controls. One concurrent agent can change another agent's current/default
   budget.
8. `seon.web.router/!ring-handler` is DB-derived but no route transaction
   listener actually calls `rebuild!`; its docstring claims one exists. A
   route change can therefore leave the cached router stale until server start
   or reload.
9. `seon.test.runner/run-vars` holds `cljs.test/*current-env*` with CLJS
   `binding` across `await`. Overlapping test runs can clobber one another in
   exactly the way the eval warning/print paths already fixed with
   AsyncLocalStorage.
10. Several database-backed render/config dials are captured at namespace load
    (`render.value/default-opts`, `verbatim-cap`, `width`,
    `eval/result-vars-cap`, and `eval/store-edn-cap`). They can freeze the
    pre-connection manifest value and ignore later DB config changes.
11. `client/-main` opens a fresh smoke-test connection on every process boot
    and never releases it. `repl/!conn` is another unreleased memory connection.
12. The single-slot render/config caches retain full DB values. They are not
    proven to be the large RSS cause, but they are explicit retention roots and
    belong in the Phase 13 heap/profile comparison.

The current worktree already contains the correct replacement for three
previous error globals: one `seon.error/scope-als` now replaces
`!persists-inflight`, `!expecting-core-fault`, and `!dev-eval-depth`. They are
listed in the retired-cell note so a later pass does not reintroduce them.

## Scope and evidence

This is a source census of the active `:client` CLJS pod at Git head
`059c0944f38b`, including the async error-scope correction committed during
the scan on 2026-07-12. The
`:client` build's development preloads are in scope because they execute in the
same Node process. Genuinely shared `.cljc` namespaces used by that build are
also in scope.

Explicitly out of scope:

- the paused JVM application lane;
- `seon.worker-eval`, `seon.worker-validator`, and `seon.dev.node-agent`, whose
  mutable cells belong to separate Node executables;
- dependency-internal caches not directly retained by Seon source; and
- ACME runtime state.

The scan covered `defonce`, module and lexical `atom`/`volatile!`,
AsyncLocalStorage, dynamic/root mutation, `globalThis`, Datahike listener
metadata, Reitit/Malli/cljs.js registries, timers, sockets, HTTP responses,
gzip streams, child processes, and database/compiler connections. Source
searches were cross-checked against the built `:client` namespace set under
`.shadow-cljs/builds/client/dev/out/cljs-runtime`.

## Disposition vocabulary

| Code | Meaning | Required outcome |
|---|---|---|
| H | Irreducible process handle or bounded in-flight state | One owner; explicit start/stop; safe loss; branch/runtime generation named |
| P | DB-derived runtime projection | Canonical DB inputs; atomic rebuild; exact invalidation; clear on coordinate change |
| F | Missing durable fact or durable artifact | Persist the fact/artifact, or remove the durable pointer that falsely promises it |
| D | Duplicate authority | Delete it after the canonical owner is wired; do not synchronize two copies |

“Safe loss” means a cold process can reconstruct correct behavior from DB and
explicit startup inputs. It does not mean silently changing durable semantics.

## Complete module-global census

The table groups cells only when they have the same owner, lifecycle, and
disposition. Every current pod `defonce` and every module-scoped mutable cell is
named here, so the table remains searchable by symbol.

| Cell or registry | Owner; value; readers and writers | Loss, inputs, invalidation | Class and implementation |
|---|---|---|---|
| `seon.db/*conn*` (`src/seon/db.cljs:345`) | Root-set Datahike connection; all ambient DB reads/writes read it; `client/start-agent!`, boot helpers, and reload paths write it | Loss requires reconnect; valid only for one full store/branch/commit attachment; replace only through quiesced lifecycle | H. Retain as the sole ambient handle, attach it to the full coordinate, and reset only in Phase 2/9. Prove cold boot, attachment switch, and teardown in DB/lifecycle tests. |
| `seon.client/!agent-conn` (`client.cljs:409`) | Second connection slot; `start-agent!` and reload read/write it and use nil/non-nil as the crash-recovery boundary | Duplicates `db/*conn*`; can disagree after reload/root switch | D. Delete in Phase 2; one runtime attachment owns the conn and “new process attachment?” fact. Tests: cold recovery runs once, mint never runs it, reload keeps one conn. |
| `seon.repl/!conn` (`repl.cljs:92`) | Lazy private memory-DB connection used only by `dev-init!` | Not rebuilt from cluster DB and never released; loss is acceptable only for the isolated dev tool | H/D. Move the scratch helper out of production runtime ownership or add explicit release in Phase 2. It must never become a routing/default DB authority. |
| `client/datahike-smoke-test!` and `client/mem-db` connection locals (`client.cljs:346,376`) | Per-call memory connections | The automatic `-main` smoke conn is never released; `mem-db` makes callers own an undocumented handle | H. Remove automatic smoke work from boot and release test conns in Phase 0/2. Mechanically assert connector count returns to baseline. |
| `seon.store.wire/!adapter` (`store/wire.cljs:438`) | Map of started/connected/db-name/feed generation/last DB/watermark; feed callbacks read/write it | No socket/timer handle, no stop, no branch/commit coordinate, survives reload indefinitely | H/P. Replace in Phase 9 with one adapter-generation record holding full coordinate, conn, socket, reconnect timer, watermark, and stop promise. Reset only after drain/release. |
| `seon.store.wire/!own-write-ids` (`store/wire.cljs:280`) | Set of forwarded write UUIDs read by reply-failure and feed echo suppression | In-flight only, but plain `def` reinitializes it on namespace reload; separate from adapter generation | D/H. Move into the Phase 9 adapter record; clear only after responses/feed resolution or adapter shutdown. Inject reload between dispatch and feed in `store/wire_test.cljs`. |
| `wire-node/!writer`, `wire-node/!reader` (`store/internal/wire_node.cljs:47-48`) | Transit codec objects lazily memoized; all wire encode/decode reads them | Safe to lose; Node/Transit owns internal per-message cache | H. Retain as one codec-generation cache or use `delay`; clear with adapter teardown only if codec options change. |
| `repl/!compile-state`, `repl/!init-version` (`repl.cljs:83,90`) | cljs.js analyzer atom plus a separate generation token; compiler/eval/agent setup read it; `ensure-bootstrap!` writes both | Rebuildable from compiled bootstrap plus canonical DB program facts; concurrent cold callers can perform duplicate initialization; two slots can describe one cache | H/P. Phase 8 uses one `{generation,state,in-flight}` compiler cell and one rebuild owner. Load DB declarations after base bootstrap; never replay effects. |
| `client/!state` (`client.cljs:245`) | Boot timestamp, reload count, heartbeat interval id | Heartbeat is redundant with server/ticker/feed handles; a second start can create another interval | D/H. Delete heartbeat machinery in Phase 2; keep observability as process logs/runtime counters, not semantic state. |
| `client/core-vars`, `!indexed-test-vars`, `!extra-core-vars` (`client.cljs:1085,1102,1117`) | Compile-time/public-var rosters; preload and downstream hooks mutate the two atoms; boot snapshot readers consume all three | Rebuildable from the selected current-core source/analyzer; duplicates canonical DB program facts and causes broad rescans | D/P. Phase 7 builds one file-grouped snapshot for an explicit current-core overlay, then deletes mutable rosters/parallel scans. Test add/change/delete/rename and no snapshot on preserve-target boot. |
| `runtime-id/!hosted`, `runtime-id/!cluster` (`dev/runtime_id.cljc:32-33`) | MCP routing advertisement: hosted agent-id set plus bare cluster name; client host/cluster calls mutate it | `unhost!` is never called by the active client; terminated agents remain advertised; no branch coordinate | D/P. Phase 2 derives hosted ids from the one live host registry and removes on teardown; Phase 9 advertises full attachment coordinate. `runtime_id_test.cljs` covers ambiguity and removal. |
| `agent/!arm-child-fn` (`agent.cljs:542`) | Injected closure from client used after child creation | Safe to rebuild but duplicates lifecycle entry seams and retains stale closure until re-register | D. Phase 2 gives spawn the same direct schema'd mint/host service as HTTP; delete injection atom. |
| `loop/!loop-input` (`agent/loop.cljs:121`) | Agent-id to `{id,llm-fn,compile-state}`; wake installation writes, resume drive reads | Closures/compiler state are legitimate handles, but entries never leave on terminate/unhost and key lacks coordinate | H. Fold into Phase 2's branch-qualified agent-host registry; add stop/unlisten/dissoc. Test terminate, restart, hot reload, and branch teardown. |
| `loop/!ticker` (`agent/loop.cljs:643`) | One `setInterval` id driving deadlines/schedules | Safe loss; explicit uninstall exists; currently process boot/reload calls reinstall | H. Retain under Phase 2 runtime owner, install once, stop before DB detach, restart after attachment ready. |
| `run/!runs-this-process` (`agent/run.cljs:165`) | Ever-growing set of run ids opened in this process; transcript asks whether result vars are live | Never removes closed runs and duplicates live result-handle truth; false history after restore/rebuild | D. Phase 8 deletes it and tests liveness at the eval/result handle (or explicit runtime generation), per eval rather than per run. |
| `shell.internal/!jobs` (`agent/shell/internal.cljs:211`) | Job-id to child process, bounded stdout/stderr, status, timestamps, spawning agent | Finished rows are capped; running children are irreducible. No runtime-wide stop kills children on pod teardown | H. Retain bounded job registry, add `stop-all!` to Phase 2 shutdown, and prove children/streams close. No DB job-status mirror. |
| `schema/*schemas`, `schema/seon-registry` (`schema.cljc:27,34`) | Mutable Malli map plus composite registry installed as Malli global; every registration/read uses it | Current authority is process memory; DB rows and registry can disagree; cold reconstruction depends on replay/indexing | D/P. Phase 6 makes full DB schema forms canonical and swaps one fully validated registry generation atomically. |
| Schema load guards `_inst-type`, `_dynamic-type`, `_db-namespace-type`, `_lookup-ref-value-type`, `_ref-type`, `_id-type`, `_schema-required-attrs`, `_schema-id-attr`, `_schema-render-fn`, `_schema-render-html-fn`, `_registry-key-type`, `_form-type`, `_namespace-name-type`, `_kvs-type`, `_discarded-keys-type` (`schema.cljc:86-167`) | `defonce` values are the return of `swap! *schemas assoc`, hence retained snapshots of successively larger registry maps | Safe only as load guards; they retain old map roots and are another bootstrap declaration path | D. Phase 6 replaces them with pure canonical seed data/candidate construction. If retained temporarily, each guard stores only a boolean, never the whole registry snapshot. |
| `schema/_registry-init` and Malli `registry*` watch (`schema.cljc:61-82`) | Installs/reasserts the Seon registry after self-host Malli reload | Third-party global integration; must follow the current registry generation | H/P. Phase 6 keeps one named watch owned by registry lifecycle, repoints after successful swap, removes on teardown/test reset. |
| `schema/!tee-fn`, `schema/!last-tee` (`schema.cljc:208-214`) | Late-bound async schema durability hook and most recent Promise | Parallel with eval tee/boot indexing; only remembers one promise globally, so concurrent registrations overwrite observability | D. Delete in Phase 6/8. Agent schema registration commits canonical facts before publishing the new registry; one operation returns its own result. |
| `render/!schema-cache` (`render.cljs:301`) | Single slot `{db,tables}` for renderable schema tables | Holds a full DB value; invalidates only by object identity; schema catalog duplicates Malli/DB projections | P. Phase 6 derives catalog once per validated registry generation; Phase 13 measures retained heap before removal. |
| `config/!db-config-view`, `db/!config-view-cache` (`config.cljs:409`, `db.cljs:1506`) | Injected zero-arg DB reader plus single slot `{db,view}`; every config accessor can read the live conn | Holds a full DB value and can read a newer head mid-render instead of the caller's frozen DB; cache has no explicit attachment key | D/P. Phase 5 removes the injection/cache pair in favor of a config projection over an explicit DB/coordinate. Render callers use their frozen DB. |
| Load-time config captures `render.value/default-opts`, `verbatim-cap`, `width`; `eval/result-vars-cap`, `store-edn-cap` (`render/value.cljs:70,78,371`; `eval.cljs:993,2889`) | Plain defs compute DB/manifest-backed accessors once during namespace load | Can freeze the pre-conn manifest and ignore DB config updates; reload timing changes behavior | D/P. Phase 5/13 makes each an operation input or registry-generation projection. Tests transact changed caps and observe structural behavior, not wording. |
| `fs.internal/!config` (`agent/fs/internal.cljs:104`) | Agent-callable filesystem grant map, initialized from env and mutated by `fs/configure!` | Semantic capability state disappears/reverts on restart and is independent of canonical config DB facts | F/D. Phase 5 either declares the grant in the config population/runtime descriptor or removes the public process-only mutator. Host lock remains an explicit startup input. |
| `log/!config` (`log.cljs:258`) | Process log path/rotation settings mutated by `log/configure!` | Operational adapter state; safe default loss only if mutation is explicitly non-durable | H/P. Phase 5 selects it from the immutable startup descriptor and keeps one logging-adapter config. Remove agent-visible implication that an unpersisted change survives restart. |
| `eval/!timeout-ms`, `eval/!next-budget-ms` (`eval.cljs:93,104`) | Default and one-shot eval budgets; agent-facing setters write them, all evals read them | Cross-agent bleed under concurrent turns; restart loss changes behavior | F/D. Phase 5 stores durable defaults/agent overrides as config facts; Phase 8 makes a one-shot override lexical or execution-ALS scoped. Concurrency test interleaves two agents. |
| `eval/warnings-als`, per-eval warning bucket atoms, `!warning-dispatcher-version`, and `ana/*cljs-warning-handlers*` (`eval.cljs:261,269,281,1096,3721`) | One ALS instance; each eval supplies its own vector atom; a process-global analyzer dispatcher reads the active bucket | Safe loss; rebuilt at compiler generation. This is correct fiber-local scratch | H. Retain, but own install/uninstall with the Phase 8 compiler generation. Prove overlapping eval warning isolation. |
| `eval/print-als`, `!orig-print-fns`, `!print-dispatcher-version`, `*print-fn*`, `*print-err-fn*`, and per-eval output atom (`eval.cljs:321-355,4127`) | ALS routes process-global print dispatch to a fiber-local string bucket | Safe loss; original sinks and generation must change atomically | H. Retain under one Phase 8 dispatcher record; test overlapping awaited evals and hot reload. |
| `error/!db-hooks`, `error/!pending` (`error.cljs:271,290`) | Injected transact/basis functions and bounded pre-DB error entity queue | Pending facts can be lost on crash; after attachment switch they must not flush into the wrong branch | H/F. Phase 3 tags post-genesis writes with user/process; Phase 9 scopes/clears pre-attachment queue by runtime coordinate. Early errors still go to process log. |
| `error/scope-als` and per-error `seon$error$recorded` property (`error.cljs:312,462-477`) | Fiber-local expected/dev/persist markers; raw throwable carries an ephemeral duplicate-record marker | Safe loss; values exist only during one propagation chain | H. Retain the ALS/per-object in-flight dedupe. Add concurrent error-persist/dev-eval proof. Do not restore the retired global counters. |
| `db.internal/als-instance`, `agent-id-als` (`db/internal.cljs:53,57`) | Fiber-local maps/current agent id; transaction boundary reads them | Runtime execution context is safe to lose, but the current generic map is copied too broadly into tx metadata | H. Phase 3 retains fiber-local execution context and explicitly constructs only `:seon.db/user`/`:seon.db/process` durable metadata. Test nested/concurrent scopes. |
| `eval/!result-var-ids`, `globalThis.__seon_results_*`, `globalThis.result.*`, and analyzer result defs (`eval.cljs:1001,1268-1417`) | Successful value is stored in two global properties; analyzer gets a synthetic var; vector tracks only the capped `result.*` face | Raw stash is never capped/deleted; vector grows only to cap but duplicates property/analyzer roster; all live values correctly die on restart | D/H. Phase 8 uses one capped process result store plus analyzer handles, deletes both faces on eviction, and derives prior/missing per eval. `result_var_test.cljs` includes cap/GC/restart proof. |
| `test.runner` `globalThis.__seon_test_run_*` and durable `:seon.test/last-run-id` (`test/runner.cljs:604-706`) | Full result stored globally; DB stores a pointer/summary | Stash is unbounded and DB pointers dangle after restart; the two recording entrypoints each stash once but share no eviction owner | F/D. Phase 8 either persists a content-addressed artifact with DB ref or stores summary only and labels full data ephemeral. Use the Phase 1 allocator. Test restart, eviction, and one stash per run. |
| `cljs.test/*current-env*`, report methods, and fixture vars on `globalThis` (`test/runner.cljs:300-600,730-780`) | Reporter/fixtures are process globals; `run-vars` binds current env across awaits | Concurrent test runs can write into the wrong builder | D/H. Phase 8 routes reporter state through ALS or serializes one explicit test-run service. Add two overlapped async runs to `runner_test.cljs`. |
| `eval/timeout-sentinel` (`eval.cljs:186`) | Identity-only JS object returned by the timeout race | Immutable process sentinel; safe loss | H. Retain or replace with a namespaced value envelope in Phase 8; it is not durable state. |
| `render.sci/!input`, `!deadline` (`render/sci.cljs:120-121`) | Process-global volatiles read by SCI host accessor/interrupt callback for one synchronous render | Safe only under non-reentrant synchronous rendering; nested/reentrant invocation can overwrite them | D/H. Phase 13 closes input/deadline over each fresh SCI context or enforces one explicit render runner. Add nested/concurrent scheduling falsification. |
| `render.sci/_warmup` (`render/sci.cljs:144`) | One-time interpreter/JIT warmup side effect | Safe loss; rebuilt per Node process | H. Retain as explicit render-engine start work and profile it separately from boot. |
| `render.sci/!bounding-warned`, `!source-fallback-noted` (`render/sci.cljs:185,340`) | Ever-growing “already logged” sets keyed by symbol/fault or agent namespace | Safe loss but unbounded and functionally a last-seen registry | D/H. Phase 13 replaces them with bounded generation-scoped rate limiting or removes suppression; visible render errors remain DB-derived. |
| `render.sci/!recovering` (`render/sci.cljs:618`) | Agent-id in-flight recovery dedupe set, removed in Promise `finally` | Safe loss; key lacks full coordinate | H. Retain as bounded in-flight state, key by attachment+agent, clear on Phase 9 teardown. |
| `serve/!server` (`web/serve.cljs:64`) | Node HTTP server handle | Must close only after feeds stop; current `stop!` cannot reach most live streams | H. Phase 2 owns start/stop once; Phase 10/11 feed owner closes streams first and awaits server close. |
| `serve/!sse-connections` (`web/serve.cljs:67`) | Legacy `/sse` response registry; no current broadcast writer consumes it | Duplicate, dead live path; clearing vector does not close responses | D. Delete `/sse`, registry, and route in Phase 10/11 after proving all pages use the canonical Datastar feed. |
| `serve/!create-agent-fn`, `!mint-agent-fn`, `!create-in-flight` (`web/serve.cljs:91-122`) | Two injected creation closures and one global boolean lock | Duplicate lifecycle entry points; boolean exists to serialize heavyweight cluster boot, not atomic mint | D. Phase 2 routes all creation through one direct service and removes all three. Concurrent mint proof covers the writer fence. |
| `datastar/!feeds` plus per-feed gzip/response, `pending-event`, `draining?` (`web/datastar.cljs:56,205-241,526-556`) | Descriptor vector for live/frozen view streams; per-feed atoms implement latest-wins backpressure | Necessary handles, but keys omit full coordinate and no runtime stop closes all streams | H. Phase 10/11 makes this the sole live-channel owner, branch-qualifies keys, and adds close-all/drain. Verify with server-side gunzip. |
| Datastar per-agent `!dependencies` (`web/datastar.cljs:681`) | Per-feed atom of literal surface/structural/header attr sets; structural changes recompute it | DB-derived projection; duplicated per equivalent tab; incomplete for dynamic reads | P. Phase 12 replaces it with observed read-result dependencies shared by normalized view key. |
| `datastar/!pending-change`, `!broadcast-timer`, `!installed?`, and Datahike `::views` listener (`web/datastar.cljs:282-329`) | Global coalesced change, trailing timer, redundant install flag, connection listener callback | Pending work/timer must clear on detach; boolean can disagree with per-conn listener registry | H/D. Phase 10 owns one lossless ordered batcher and listener; remove boolean, cancel timer/drain on stop. |
| `debug/!sse-by-agent`, `!pending`, `::debug` listener (`web/debug.cljs:52,997-1056`) | Separate response registry and per-agent timer flags; provenance-based fan-out | Duplicate live channel; empty agent keys accumulate; timers have no stored cancel handle; no coordinate | D. Phase 10/11 migrates debug/data views onto canonical feed and deletes this registry/listener/fan-out. |
| `router/!ring-handler`, `!same-origin-pred`, `!router-config` (`web/router.cljs:70-83`) | Cached Reitit handler plus two injected serve closures/maps | Router is DB-derived but has no working route-tx invalidation; three cells can publish partial generation | P/D. Phase 11 builds one immutable router generation from route facts + static handlers; Phase 12 invalidates by observed route reads and full coordinate. |
| Datahike conn listener atom (`db/internal.cljs:1517-1572`; keys `::views`, `::debug`, `[:seon.agent/user-message-trigger id]`) | Third-party per-conn callback registry; store adapter manually fires it | Correct ephemeral subscription mechanism, but current owners duplicate feeds and do not detach as one unit | H. Phase 2 owns agent wake listeners; Phase 10 owns one UI listener; Phase 9 releases the old conn only after all are removed/drained. |
| `client/!orig-shadow-node-eval` and patched `js/SHADOW_NODE_EVAL` (`client.cljs:2815-2832`) | Stores original Shadow function and installs one dev-eval wrapper | Dev-only process integration; no uninstall, but `defonce` prevents stacking | H. Retain under Phase 2 process hook lifecycle or provide explicit restore for tests/shutdown. |
| Node `unhandledRejection`/`uncaughtException` handlers (`client.cljs:2834-2867`) | Anonymous process listeners installed by every `-main` call | No handle/idempotency guard; repeated entry stacks handlers and duplicate error facts | D/H. Phase 2 stores named handlers in runtime state, installs once, and removes/replaces them mechanically. |
| Web test seams `!policy-override`, `!lookup-impl`, `!fetch-impl`, `!search-config-override`, `!gemini-impl`, `!serper-impl` (`agent/web/internal.cljs:81,193,418,577,660,756`) | Test-only process overrides | Safe loss, but concurrent tests can bleed if fixtures overlap | H. Keep only as test fixture state or replace with request injection/ALS in Phase 0 test hygiene. Every fixture resets in `finally`. |
| `agent.web.internal/!rdeps` (`agent/web/internal.cljs:357`) | Cached Readability/linkedom module object | Safe loss; Node already caches `require`, so this is a duplicate cache | D. Delete or use one immutable delay in Phase 13; it is not DB state. |
| AI dynamic test roots `openai-compat/*fetch*`, `anthropic/*fetch*`, `diffusiongemma/*fetch*`, `*poll-ms*`, `*local-poll-ms*`, `*max-polls*` (`ai/openai_compat.cljs:318`; `ai/anthropic.cljs:290`; `ai/diffusiongemma.cljs:175-185`) | Root-set test transport/timing seams | Safe loss; CLJS bindings across awaits are unsafe, so concurrent tests can bleed | H. Pass explicit adapter options or ALS-scoped test dependencies in Phase 8; never treat them as runtime provider config. |
| cljs.js/global namespace objects on `globalThis` | Compiled core plus agent-defined vars; eval/lookup/render/test readers resolve through them | Runtime program projection; cannot safely mix two branch program graphs in one namespace object | P. Phase 8 rebuilds from canonical declarations in a fresh process/compiler generation; Phase 9 uses separate fork pods and restart for live restore. |
| Immutable registries `router/mw-registry` and `warn/checks` (`web/router.cljs:173`, `warn.cljs:981`) | Source-defined maps/vectors of functions | Immutable code data, not semantic runtime state | H (code constant). Retain; changes arrive with the selected current-core overlay/build, not DB mutation. |

## Lexical mutable holders

These cells cannot become cross-agent durable authorities because each is
allocated inside one invocation. They are still included for completeness and
to distinguish legitimate protocol state from Clojure code that can become a
pure reduction.

| Allocation | Purpose and lifetime | Disposition |
|---|---|---|
| `store/wire.cljs:558-560` `!buffer`, `!live?`, `!db-name` | One pub-feed connect/replay handoff | H. Keep inside one socket generation; expose socket/stop at the owning adapter. |
| `store/internal/wire_node.cljs:161-167` `!need`, `!payload`, `!lenbuf`, `!settled`, `!alive-ms`, `!timer` | One request/reply frame parser and liveness timeout | H. Correct per-RPC state; timer/socket cleanup remains in `done`. Add closed/error/timeout leak proof. |
| `store/internal/wire_node.cljs:232-234` `!connected`, `!closed`, `!buf` | One persistent pub socket parser | H. Correct closure state; caller must retain/destroy the returned socket. |
| `instrument.cljc:581` `stats` volatile | One instrumentation pass counters | Local reduction only. Replace with `reduce` while implementing Phase 8 deltas; no DB fact. |
| `client.cljs:802-803` `!order`, `!seen` | DFS topological sort | Local pure accumulator. Replace with pure recursion/reduce in Phase 7. |
| `client.cljs:965` `!n-fail` | One declaration replay failure count | Local async fold. Return accumulator explicitly in Phase 8. |
| `client.cljs:2711` `!acc` | Sequential per-agent initialization results | Local async fold. Phase 2 mint/resume split removes this boot-wide collector. |
| `test/runner.cljs:388` `settled` | One test/fixture timeout race | H. Correct lexical race state. |
| `test/runner.cljs:533` `!builder` | One run's transient report event builder | H, but reporter access must become ALS/serialized; builder remains lexical. |
| `ui/markdown.cljs:52-53` `acc`, `last-end` | One inline-markdown parse | Pure accumulator. Use loop/transient when touched; no lifecycle work. |
| `eval.cljs:208` `!timer` | One Promise timeout race | H. Correct lexical timer holder; clear in `finally`. |
| `eval.cljs:1096`, `3721` warning atoms | Per-eval ALS warning buckets | H. Correct fiber-local buckets. |
| `eval.cljs:1409` `pruned` | Captures ids evicted by one result-store swap | Local reduction. Disappears with the Phase 8 single result store. |
| `eval.cljs:2086` `acc`; `3666` `syms`; `3764` `found`; `4015` `all-syms` | One source/analyzer traversal or callback collection | Local pure/callback accumulators. Prefer reduce/explicit callback result when those functions change. |
| `eval.cljs:4127` `out-bucket` | Per-eval print ALS string bucket | H. Correct fiber-local scratch. |
| `eval.cljs:4901-4909` `eids`, `n-ok`, `n-fail`, `current-ns`, `failed-defs` | One async eval batch fold | Lexical async state. Refactor to explicit fold in Phase 8 where practical; never persist the algorithm trace. |
| `config.cljs:1251` `seen` | One context-config merge pass | Pure accumulator. Replace with a set-producing reduction in Phase 5. |
| `diffusion/retrieval.cljs:392` `acc` | One syntax-tree free-reference walk | Pure accumulator. No runtime authority; replace with pure traversal when touched. |
| `agent/ctx.cljs:1924-1926` `data-by-kw`, `seen`, `order` | One namespace-render traversal/cache | Pure render-local accumulator. Phase 13 can make it an explicit state-returning walk. |
| `agent/ctx/transcript.cljs:190` `budgets` | One transcript budget allocation pass | Pure render-local accumulator. Keep lexical or use reduce. |
| `agent/fs.cljs:600-601` `!out`, `!truncated` | One bounded directory walk | Lexical traversal state passed through recursive host I/O. Safe loss; no DB fact. |
| `web/datastar.cljs:536-537` per-feed pending/draining atoms | One gzip stream's backpressure | H. Retain inside the canonical feed descriptor and close explicitly. |
| `web/datastar.cljs:681` `!dependencies` | One agent feed's DB-derived dependency projection | P. Replace with normalized-view observed reads in Phase 12. |
| `web/serve.cljs:230`, `web/reactive/call.cljs:207` request `chunks` | One HTTP request body | H. Lexical stream collector; add body-token/byte transport cap and abort-on-close in Phase 13. |
| `agent/search/internal.cljs:330` `cache` | One search call's file-existence memo | Local pure cache. Thread a map through the filter or retain lexical bounded map. |

## Timers, sockets, listeners, and connection teardown matrix

| Handle | Current owner/start | Current stop | Required proof and phase |
|---|---|---|---|
| Main Datahike connection | `client/start-agent!` via `open-cluster-conn!` | No complete pod detach owner; duplicate `!agent-conn` | Phase 2/9: admission close → drain writes/feed/listeners → release conn → verify closed → attach next coordinate. |
| Boot smoke memory conn | `client/-main` | None | Phase 0/2: remove from normal boot or release/delete; connector registry returns to baseline. |
| REPL scratch memory conn | `repl/dev-init!` | None | Phase 2: explicit `dev-stop!` or isolate outside pod runtime. |
| One-shot wire RPC socket + interval | `wire-node/rpc` | `done` clears interval and ends socket; timeout destroys | Phase 9 tests every resolve/reject path and no live timer/socket afterward. |
| Persistent pub socket + reconnect timer | `store.wire/start-listen-adapter!` | None; socket/timer are not retained globally | Phase 9 adds stop, generation fence, branch coordinate, replay watermark proof. |
| Datahike conn listener atom | `db/listen!` from agent loop/Datastar/debug | Individual `unlisten!`; no whole-runtime drain | Phase 2/10 removes all old-conn listeners before release and rebuilds exact owners after attach. |
| Agent wake listeners | `loop/install-wake-trigger!` per id | Re-arm replaces same key; termination does not remove | Phase 2 host teardown test proves unlisten/dissoc/unhost on terminate/delete/detach. |
| Global ticker interval | `loop/install-ticker!` | `uninstall-ticker!` exists | Phase 2 asserts exactly one timer across boot/reload and none after stop. |
| Client heartbeat interval | `client/-main`/reload | `stop-heartbeat!` only on reload | Phase 2 deletes it; server/feed/ticker already keep Node alive. |
| Eval timeout | `eval/race-timeout` | `finally clearTimeout` | Phase 8 keeps; prove late underlying settle cannot cross run fence. |
| Test timeout | `test.runner/with-test-timeout` | Clear on settle; fired timer is terminal | Phase 8 concurrency test with ALS reporter. |
| Web fetch AbortControllers | `agent.web.internal/transport`, Gemini, Serper | Timers clear after fetch resolves/errors | Phase 13 keeps abort through body consumption; current Gemini/Serper clear after headers then call unbounded `.text`. |
| HTTP server | `serve/start!` | `serve/stop!` calls `.close` without awaiting | Phase 2/11 closes all feeds first, awaits close, removes process hooks. |
| Legacy `/sse` responses | `serve/open-sse!` | Request close removes; server stop only drops vector | Phase 11 deletes path/registry. |
| Datastar gzip streams | `datastar/open-feed!` | Request close ends one gzip; no close-all | Phase 10/11 canonical feed owner closes and awaits every stream. |
| Debug/data SSE responses | `debug/open-*-sse!` | Request close removes one; no close-all | Phase 11 migration deletes separate registry. |
| Debug coalescing timers | `debug/schedule-push!` | Timer id is not retained/cancellable | Phase 10 canonical batcher owns/cancels one timer. |
| Datastar coalescing timer | `datastar/schedule-broadcast!` | Replaced per change; uninstall does not clear | Phase 10 stop drains/cancels it and cannot render after detach. |
| Background child processes/streams | `shell.internal/start-job!` | Per-job stop exists; no stop-all | Phase 2 `stop-all!`, SIGTERM/grace/kill policy, stream close proof. |
| Shadow dev-eval and Node process hooks | client module/-main | No unified restore/remove | Phase 2 named hook install/stop is idempotent and produces one error datom. |

## Cold rebuild and invalidation contract

The target runtime can be represented without a new state framework. One
process attachment owns this order:

1. Read the durable runtime descriptor and open one full database coordinate.
2. Install the wire adapter for that exact coordinate.
3. Build one validated Malli/catalog generation from canonical schema facts.
4. Build one compiler/program generation from compiled core plus safe canonical
   declarations selected for this operation.
5. Build one router generation from route facts and static code handlers.
6. Resume the DB-derived agent roster into one host registry; each host owns
   its wake listener and process-only loop input.
7. Install one ticker, one UI DB listener, one HTTP server, and one canonical
   feed registry.

Teardown is the reverse. No cache or handle survives a coordinate change unless
its key includes that full coordinate and its value is valid there.

The only runtime generations needed are attachment, schema/catalog, compiler,
router, and live-channel. They are projections/handles, not stored status
entities. Current facts and exact coordinates remain in Datahike; runtime
generation numbers stay process-local.

## Phase/file/test ownership

| Phase | Mutable-state work owned there | Primary files and proof |
|---|---|---|
| 0 | Baseline counts; remove auto-smoke leak; measure result/test stash growth; verify current error ALS replacement | `client.cljs`, `eval.cljs`, `test/runner.cljs`, profiling script; cold boot and heap snapshots |
| 1 | Replace test-run and every other generated id with `seon.db.id/allocate!` | `test/runner.cljs`, ID allocator tests |
| 2 | One runtime attachment; delete connection/create/arm duplicates; host teardown; one ticker/server/process hooks; child stop-all | `client.cljs`, `agent.cljs`, `agent/loop.cljs`, `dev/runtime_id.cljc`, `web/serve.cljs`, shell tests |
| 3 | Execution ALS versus explicit user/process tx facts; coordinate-safe early-error queue | `db/internal.cljs`, `db.cljs`, `error.cljs`, tx-context/error tests |
| 5 | Explicit DB config projection; remove injected live-reader cache, process-only fs mutation, and load-time cap captures | `config.cljs`, `db.cljs`, `render/value.cljs`, `eval.cljs`, `agent/fs/internal.cljs`, config/fs tests |
| 6 | Canonical schema facts; one atomic Malli/catalog generation; delete tee and schema DB-value cache | `schema.cljc`, `render.cljs`, schema/render tests |
| 7 | One selected source snapshot; delete mutable program rosters/ghost scans | `client.cljs`, indexing/program tests |
| 8 | One compiler cell; incremental instrumentation; one capped eval result store; honest test artifacts; concurrent test reporter | `repl.cljs`, `eval.cljs`, `instrument.cljc`, `test/runner.cljs`, result/runner/replay tests |
| 9 | Full coordinate wire adapter, stop/release/reconnect, attachment switch | `store/wire.cljs`, `store/internal/wire_node.cljs`, pinned Datahike fork, wire/restore tests |
| 10 | One listener and lossless batch owner; cancel/drain on detach | `web/datastar.cljs`, `web/debug.cljs`, `db.cljs`, Datastar tests |
| 11 | One feed registry; remove `/sse` and debug registries; close-all before server close; atomic router generation | `web/serve.cljs`, `web/datastar.cljs`, `web/debug.cljs`, `web/router.cljs`, browser + gunzip proof |
| 12 | Observed DB reads replace per-feed literal dependency atoms; route/config invalidation uses result changes | agent view/render/router/reactive files; equivalent-tab and irrelevant-write tests |
| 13 | Remove DB-retaining render caches, process SCI invocation globals, unbounded seen sets; bound request/body/render work | `render.cljs`, `render/sci.cljs`, `agent/web/internal.cljs`; CPU/RSS/event-loop matrix |
| 14 | Cross-agent concurrency and cold/restart/restore leak acceptance | Full CLJS suite plus live agents/browser/feed/heap checks |

## Mechanical acceptance checks

- A runtime-state inventory function reports one attachment, one wire adapter,
  one UI listener, one ticker, one HTTP server, and the exact live feed/agent
  host counts without persisting counters.
- Repeated cold boot/stop returns connector, listener, timer, socket, child,
  and feed counts to baseline.
- Hot reload during an in-flight write neither loses echo correlation nor
  fires a transaction twice.
- Terminating an agent removes its host, loop input, and wake listener.
- Closing a run does not grow a semantic run roster; eval-result liveness is
  determined per handle.
- Repeated successful evals and test runs plateau at explicit token/item caps;
  evicted `globalThis` properties and analyzer defs are absent.
- Two overlapping evals isolate timeout overrides, warnings, and print output.
- Two overlapping async test runs isolate reporter events.
- A route/config/schema fact change publishes exactly one matching runtime
  generation; an irrelevant transaction publishes none.
- Stop with open main/debug/data tabs closes all response/gzip streams and the
  server Promise settles.
- A full coordinate switch cannot observe the prior branch's conn, adapter
  watermark, router, schema catalog, compiler declarations, dependencies, or
  agent hosts.
- Heap snapshots before/after repeated grown-store renders distinguish DB-value
  cache retention from transient query/SCI/hiccup/gzip allocation.

## Retired cells observed during the census

The working tree's `seon.error` already replaces these committed-base globals:

- `!persists-inflight`;
- `!expecting-core-fault`; and
- `!dev-eval-depth`.

One `scope-als` now carries `:seon.error.scope/persist?`,
`:seon.error.scope/expecting-core-fault?`, and
`:seon.error.scope/dev-eval?` per fiber. This is the intended pattern and
should be committed/tested as part of the Phase 0/3 boundary, not recreated as
three counters.
