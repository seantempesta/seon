---
type: architecture
status: active
tags: [architecture, reference, cljs, database, web]
---

# Library grounding — current concept-to-source read map

Architecture claims are grounded in vendored dependency source and the one
Seon owner that adapts it. Before changing a mechanism, read the listed source
and the closest localized `AGENTS.md`; do not infer library behavior from API
names or historical migration notes.

| Concept | Vendored and Seon source to read | Invariant established |
|---|---|---|
| Datahike transaction fence | `reference-code/datahike/src/datahike/db/transaction.cljc`; `src-old/seon/db/`; `src-old/seon/db/server.clj` | Datahike's `:db.fn/cas` is checked inside the authoritative writer transaction and is reserved for facts two processes race to win exactly once: plan freeze absent→digest and run claim process nil→process plus claim-epoch increment |
| Refs, components, identities, and cursors | Datahike transaction, database, and index source; `src-old/seon/schema.cljc`; `src-old/seon/db/internal.cljs` | attribute schema owns identity/ref/component semantics; cursor windows follow indexes |
| Immutable database values and branches | Datahike database/connection/branch source; `src-old/seon/db/` | reads use one immutable value; commit IDs, basis transactions, and branches preserve lineage rather than copying mutable state |
| Shared Datahike read computation | `reference-code/datahike/src/datahike/query.cljc`; `reference-code/datahike/src/datahike/query/`; `reference-code/datahike/src/datahike/pull_api.cljc`; `reference-code/datahike/src/datahike/versioning.cljc`; query-cache and pull tests; `src-old/seon/reactive.cljs` | parsed queries and pull specs produce source-scoped dependency plans; immutable database values own weighted result identity, materialized-commit revision fencing, and single-flight; Seon registers demanded computations rather than adding a result cache or parser |
| Datahike connection demand and release | `reference-code/datahike/src/datahike/connections.cljc`; `reference-code/datahike/src/datahike/connector.cljc`; `reference-code/datahike/src/datahike/pod.clj` | repeated connects share one physical connection; the final release drains and closes resources; pod database values already have explicit IDs and release |
| Datahike listener ownership | `reference-code/datahike/src/datahike/core.cljc`; listen tests; `src-old/seon/db.cljs` | subscription is process-local keyed `listen!`/`unlisten!` ownership, never a stored active flag or parallel pub/sub bus |
| Konserve cache and release | `reference-code/konserve/src/konserve/cache.cljc`; `reference-code/konserve/src/konserve/store.cljc`; selected backend source | query results, store nodes, secondary indexes, and connection resources are distinct budgets with distinct release semantics |
| Bun native host boundaries | `reference-code/bun/packages/bun-types/bun.d.ts`; Bun spawn/socket/server implementations and tests; `src-old/seon/web/`; `src-old/seon/db/transport/uds.cljc`; `src-old/seon/agent/shell/internal.cljs` | native values stay inside process, HTTP/feed, and socket owners; application and protocol boundaries remain ordinary namespaced data |
| Malli candidate and projection | `reference-code/malli/src/malli/registry.cljc`; `reference-code/malli/src/malli/core.cljc`; `src-old/seon/schema.cljc` | validate a complete candidate before publishing one immutable active registry projection |
| Malli instrumentation | vendored Malli instrumentation source; `src-old/seon/instrument.cljc` | reconstruct wrappers from committed program facts, then update changed definitions and schema dependents |
| SCI interruption | `reference-code/sci/doc/interrupt.md`; `reference-code/sci/src/sci/interrupt.cljc`; the cluster JVM eval owner | SCI is the one interpreter for agent-authored code; every invocation installs the one `:interrupt-fn`, which SCI calls at every `fn` body entrance; `time-limit` is the only execution limit, `interrupt!` stops the eval uncatchably, and `:seon.eval/fn-entries` is a diagnostic only |
| Execution planning over the program graph | `src-old/seon/program/plan.cljc`; the analyzer edge producers; `src-old/seon/schema.cljc` | the derived, basis-fenced execution plan is the sole placement authority; purity and locality reduce from stored direct edges, fail closed on dynamic construction, and are never re-derived by consumers |
| Runtime scheduling | `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj`; `reference-code/core.async/src/main/clojure/clojure/core/async/flow/spi.clj`; `reference-code/core.async.flow-monitor/`; the cluster-JVM Flow owner | real `core.async.flow` procs, `step-fn`s, `conns`, `graph-def`, report channel, and `flow.spi/ProcLauncher` provide the one scheduling substrate with zero forked Flow files; flow-monitor remains the operations surface |
| Bun leaf host boundary | Bun spawn/socket implementations and tests; `src-old/seon/db/transport/uds.cljc`; the `seon.packages.js.*` wrappers | the disposable leaf serves JavaScript package and worker effects over the one typed wire; a lost in-flight call is a flat capability error, and no durable state lives in the leaf |
| Reitit route derivation | `reference-code/reitit/modules/reitit-core/`; `reference-code/reitit/modules/reitit-ring/`; `src-old/seon/route.cljs`; `src-old/seon/web/router.cljs` | routes compile from database values; raw streaming remains Seon-owned |
| Datastar morph and feed | `reference-code/datastar-clojure/libraries/sdk/`; `reference-code/datastar-clojure/src/dev/examples/tiny_gzip.clj`; `src-old/seon/web/datastar.cljs` | stable-ID element patches and signals ride flushed gzip SSE; agent code never owns a stream |
| Hyperlith render loop | `reference-code/hyperlith/src/hyperlith/`; `src-old/seon/web/datastar.cljs` | the useful model is `view = f(db)` with bounded coalescing and resource cleanup, not Hyperlith's route registry |
| Changed CLJS tests | vendored Shadow CLJS build/analyzer source; `script/seon/dev/changed_test.clj`; `bin/test-cljs` | Shadow owns CLJS compilation and namespace dependency facts; incomplete facts widen selection |
| Changed CLJ/CLJC tests | vendored clj-kondo analysis source; `script/seon/dev/changed_test.clj`; `bin/test-writer` | host namespace/macro analysis may narrow conservatively; CLJC unions both runtime decisions |
| Inspect AI boundary | the pinned Inspect package source; `src-inspect-ai/`; `src-old/seon/web/` one-shot agent endpoint | standard tasks measure a model; cluster-backed tasks measure Seon through production behavior; no second evaluator |

## Reading rules

- Start with the current architecture domain and its Seon owner, then read the
  dependency implementation that supplies the claimed primitive.
- Prefer the maintained source under `reference-code/`; do not unzip installed
  artifacts or rely on memory.
- Probe a load-bearing assumption at the smallest executable boundary before
  changing code.
- Historical timings, line-specific migration instructions, rejected systems,
  downstream consumer wiring, and dated validation results belong in PRD
  research, not this always-current map.
- A dependency proves only its own primitive. Seon's adapter still owns
  cancellation, deadlines, error values, protocol framing, lifecycle, and
  tests where the library does not.

## Related sources

- [[architecture]] — topology and cross-cutting target.
- [[datahike-primer]] — the database-value and Datalog mindset.
- [[data-model]] — schema and relationship ownership.
- [[agent-runtime]] — program publication and execution transitions.
- [[ui]] — render units, routing, and the live feed.
- [[roadmap]] — implementation status and dated evidence links.
