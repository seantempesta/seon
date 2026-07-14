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
| Immutable database values and branches | Datahike database/connection/branch source; `src/seon/db/` | reads use one immutable value; coordinates and branches preserve lineage rather than copying mutable state |
| Malli candidate and projection | `reference-code/malli/src/malli/registry.cljc`; `reference-code/malli/src/malli/core.cljc`; `src/seon/schema.cljc` | validate a complete candidate before publishing one immutable active registry projection |
| Malli instrumentation | vendored Malli instrumentation source; `src/seon/instrument.cljc` | reconstruct wrappers from committed program facts, then update changed definitions and schema dependents |
| ClojureScript self-host evaluation | vendored ClojureScript analyzer/compiler source; `src/seon/eval.cljs` | analyzer state, namespace declarations, and eval results are one program corpus; promises are awaited at the agent boundary |
| SCI bounded invocation | `reference-code/sci/src/`; `src/seon/render/sci.cljs` | agent-authored functions enter through one capability and bounding door |
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
