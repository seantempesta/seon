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
| Datahike transaction fence | `reference-code/datahike/src/datahike/db/transaction.cljc`; `src/seon/db/`; `src/seon/db/server.clj` | CAS is checked inside the authoritative writer transaction; only data crosses the protocol |
| Refs, components, identities, and cursors | Datahike transaction, database, and index source; `src/seon/schema.cljc`; `src/seon/db/internal.cljs` | attribute schema owns identity/ref/component semantics; cursor windows follow indexes |
| Immutable database values and branches | Datahike database/connection/branch source; `src/seon/db/` | reads use one immutable value; commit IDs, basis transactions, and branches preserve lineage rather than copying mutable state |
| Shared Datahike query computation | `reference-code/datahike/src/datahike/query.cljc`; `reference-code/datahike/src/datahike/core.cljc`; query-cache tests | raw database values already own a weighted result-cache identity and transaction propagation; strengthen this owner rather than adding a Seon cache |
| Datahike connection demand and release | `reference-code/datahike/src/datahike/connections.cljc`; `reference-code/datahike/src/datahike/connector.cljc`; `reference-code/datahike/src/datahike/pod.clj` | repeated connects share one physical connection; the final release drains and closes resources; pod database values already have explicit IDs and release |
| Datahike listener ownership | `reference-code/datahike/src/datahike/core.cljc`; listen tests; `src/seon/db.cljs` | subscription is process-local keyed `listen!`/`unlisten!` ownership, never a stored active flag or parallel pub/sub bus |
| Konserve cache and release | `reference-code/konserve/src/konserve/cache.cljc`; `reference-code/konserve/src/konserve/store.cljc`; selected backend source | query results, store nodes, secondary indexes, and connection resources are distinct budgets with distinct release semantics |
| Bun native host boundaries | `reference-code/bun/packages/bun-types/bun.d.ts`; Bun spawn/socket/server implementations and tests; `src/seon/web/`; `src/seon/db/transport/uds.cljs`; `src/seon/agent/shell/internal.cljs` | native values stay inside process, HTTP/feed, and socket owners; application and protocol boundaries remain ordinary namespaced data |
| Malli candidate and projection | `reference-code/malli/src/malli/registry.cljc`; `reference-code/malli/src/malli/core.cljc`; `src/seon/schema.cljc` | validate a complete candidate before publishing one immutable active registry projection |
| Malli instrumentation | vendored Malli instrumentation source; `src/seon/instrument.cljc` | reconstruct wrappers from committed program facts, then update changed definitions and schema dependents |
| ClojureScript self-host evaluation | vendored ClojureScript analyzer/compiler source; `src/seon/eval.cljs`; `src/seon/execution/` | analyzer state, namespace declarations, and eval results are one program corpus; authored source compiles in its agent's Bun child and promises are awaited at the agent boundary |
| Bun child execution isolation | Bun spawn/process implementations and tests; `src/seon/execution/`; `src/seon/eval.cljs` | one parent-owned deadline, cancellation, and lifecycle door bounds each agent process; capability grants and database values remain ordinary data |
| Reitit route derivation | `reference-code/reitit/modules/reitit-core/`; `reference-code/reitit/modules/reitit-ring/`; `src/seon/route.cljs`; `src/seon/web/router.cljs` | routes compile from database values; raw streaming remains Seon-owned |
| Datastar morph and feed | `reference-code/datastar-clojure/libraries/sdk/`; `reference-code/datastar-clojure/src/dev/examples/tiny_gzip.clj`; `src/seon/web/datastar.cljs` | stable-ID element patches and signals ride flushed gzip SSE; agent code never owns a stream |
| Hyperlith render loop | `reference-code/hyperlith/src/hyperlith/`; `src/seon/web/datastar.cljs` | the useful model is `view = f(db)` with bounded coalescing and resource cleanup, not Hyperlith's route registry |
| Changed CLJS tests | vendored Shadow CLJS build/analyzer source; `script/seon/dev/changed_test.clj`; `bin/test-cljs` | Shadow owns CLJS compilation and namespace dependency facts; incomplete facts widen selection |
| Changed CLJ/CLJC tests | vendored clj-kondo analysis source; `script/seon/dev/changed_test.clj`; `bin/test-writer` | host namespace/macro analysis may narrow conservatively; CLJC unions both runtime decisions |
| Inspect AI boundary | the pinned Inspect package source; `src-inspect-ai/`; `src/seon/web/` one-shot agent door | standard tasks measure a model; pod-backed tasks measure Seon through production behavior; no second evaluator |

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
