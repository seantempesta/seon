---
type: architecture
status: active
tags: [architecture, reference, database, runtime, flow, web]
---

# Library grounding — current concept-to-source read map

Architecture claims begin at the live first-party owner and continue into the
exact vendored dependency source that supplies its primitive. Before changing
a mechanism, read both sides and the closest localized `AGENTS.md`. Historical
implementations are archaeology, not owners.

## Pinned revisions

These are the maintained source revisions verified for this map:

| Dependency | Revision |
|---|---|
| Datahike | `407e9328851ccce318148188f1d284646eb64132` |
| Konserve | `07377c27c8288b7484f0aa7b82e8158b415985be` |
| SCI | `fcbd8862800e638dc0f8f5521111f999279cbcd2` |
| core.async | `dc35f3e0d7bc2eef502e77982f48641f025c8051` |
| Malli | `3517a3cd9271b2083780ac7be1725493905bca2e` |
| clj-kondo | `57252e07975710aa579b24f0d1b2b1e04195caa2` |
| Reitit | `106fc4c7a09290c8e2df2d4ef9570ea1322ab2ab` |
| datastar | `bb9ed6fbe78cf5690f5ad23a5faf86407a44982f` |
| datastar-clojure | `1cef624e9e59a2ea79ffe2f65df2e7b06f8198d2` |

## Current seams

| Concept | Live first-party owner | Vendored source to read | Invariant established |
|---|---|---|---|
| Process-root store and cluster branches | `src/seon/cluster.clj`; `src/seon/cluster/store.clj` | `reference-code/datahike/src/datahike/versioning.cljc`; `reference-code/datahike/src/datahike/connections.cljc`; `reference-code/datahike/src/datahike/writer.cljc` | One JVM may host several clusters over one fenced physical store; each cluster has one branch connection, while Datahike owns connection acquisition and serial transaction execution |
| Application database reads and writes | `src/seon/db.clj`; `src/seon/cluster/store.clj`; `src/seon/cluster/run.clj` | `reference-code/datahike/src/datahike/query.cljc`; `reference-code/datahike/src/datahike/pull_api.cljc`; `reference-code/datahike/src/datahike/db/transaction.cljc`; `reference-code/datahike/src/datahike/writer.cljc` | Reads operate on an explicit or ambient immutable database value; writes call the co-located Datahike writer, and run transition eligibility is decided inside its transaction |
| Schema population and Datahike attributes | `resources/seon/schemas/`; `src/seon/schema/edn.clj`; `src/seon/schema.clj`; `src/seon/schema/datahike.cljc` | `reference-code/malli/src/malli/core.cljc`; `reference-code/malli/src/malli/registry.cljc`; `reference-code/datahike/src/datahike/db/transaction.cljc` | Shipped Malli forms are loaded from one classpath directory as one candidate population, admitted before activation, and used to derive Datahike value type, cardinality, identity, refs, and component ownership |
| Program-graph source indexing | `src/seon/fn/analyzer.clj`; `src/seon/fn.clj`; `src/seon/program.cljc`; the program family declarations under `resources/seon/schemas/` | `reference-code/clj-kondo/src/clj_kondo/core.clj`; `reference-code/clj-kondo/src/clj_kondo/impl/analyzer.clj` | Static JVM analysis of first-party `src/` and `test/` produces canonical namespace, function, and test rows with exact source; application forms are not evaluated to build the index |
| Live SCI program context and interruption | `src/seon/sci/reader.cljc`; `src/seon/sci/eval.clj`; `src/seon/sci/admit.clj`; `src/seon/cluster/loop.clj` | `reference-code/sci/src/sci/core.cljc`; `reference-code/sci/src/sci/interrupt.cljc`; `reference-code/sci/doc/interrupt.md` | Each cluster cold-acquires one program-only base `ctx`; every turn uses a fresh fork rehydrated with the selected agent's defs, one reader event, one `:interrupt-fn`, and `time-limit` as the execution limit before admitting the result |
| Host and interpreted function contracts | `src/seon/instrument.clj`; `src/seon/sci/eval.clj`; `src/seon/program.cljc` | `reference-code/malli/src/malli/instrument.clj`; `reference-code/malli/src/malli/core.cljc`; `reference-code/malli/src/malli/registry.cljc` | Host public Vars with `:malli/schema` are instrumented under the core-error dial; committed interpreted functions are wrapped from their `:seon.fn/spec` at program row installation |
| Flow graphs, procs, and workloads | `src/seon/flow.clj`; `src/seon/cluster/agent.clj`; `src/seon/cluster/loop.clj`; `src/seon/render/web.clj` | `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj`; `reference-code/core.async/src/main/clojure/clojure/core/async/flow/spi.clj`; `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj`; `reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj` | Seon builds real Flow graphs from Var-backed procs and pins each proc to `:io` or `:compute`; channels carry losable process-local work while durable state remains database facts |
| Content-addressed result blobs | `src/seon/blob.clj`; `src/seon/cluster/loop.clj`; `src/seon/cluster/store.clj`; the blob and eval family declarations under `resources/seon/schemas/` | `reference-code/konserve/src/konserve/core.cljc`; `reference-code/konserve/src/konserve/store.cljc` | Threshold-eligible result content moves to the already-open Konserve store only when its bounded-window receipt plus binary payload is smaller than the full inline receipt; the SHA-256 digest is verified when read |
| Routes, Ring handlers, and Datastar SSE | `src/seon/render/route.clj`; `src/seon/render/web.clj`; `resources/public/js/datastar.js` | `reference-code/reitit/modules/reitit-core/src/reitit/core.cljc`; `reference-code/reitit/modules/reitit-ring/src/reitit/ring.cljc`; `reference-code/datastar/bundles/datastar.js`; `reference-code/datastar-clojure/libraries/sdk/src/main/starfederation/datastar/clojure/api.clj`; `reference-code/datastar-clojure/libraries/sdk-http-kit/src/main/starfederation/datastar/clojure/adapter/http_kit.clj` | One route table owns matching and reverse routing; the page loads the byte-identical pinned Datastar browser bundle, and the web renderer uses Datastar's element patch and HTTP-kit SSE lifecycle rather than owning a second streaming protocol |
| Changed-test selection and the fresh gate | `script/seon/dev/changed_test.clj`; `src/seon/test/runner.clj`; `bin/test` | `reference-code/clj-kondo/src/clj_kondo/core.clj`; `reference-code/clj-kondo/src/clj_kondo/impl/analyzer.clj` | clj-kondo host analysis may conservatively narrow affected CLJ and CLJC tests; `bin/test` remains the one fresh-system correctness gate |

## Reading rules

- Start with the current architecture domain and live Seon owner, then read the
  dependency implementation that supplies the claimed primitive.
- Prefer the maintained source under `reference-code/`; do not unzip installed
  artifacts or rely on memory.
- Probe a load-bearing assumption at the smallest executable boundary before
  changing code.
- Historical timings, migration instructions, rejected systems, downstream
  consumer wiring, and dated validation results belong in PRD research, not
  this always-current map.
- A dependency proves only its own primitive. The first-party owner still owns
  admission, errors-as-values, lifecycle, and tests at the integration seam.

## Related sources

- [[architecture]] — topology and cross-cutting target.
- [[datahike-primer]] — the database-value and Datalog mindset.
- [[data-model]] — schema and relationship ownership.
- [[agent-runtime]] — program publication and execution transitions.
- [[ui]] — render units, routing, and the live feed.
- [[architecture/decisions/012-process-root-cluster-topology]] — the current
  process, database, and cluster decision.
