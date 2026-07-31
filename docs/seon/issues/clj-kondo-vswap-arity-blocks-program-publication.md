---
type: issue
status: open
severity: blocker
tags: [issue, source, indexing, clj-kondo]
---

# Correct clj-kondo's `vswap!` arity before program publication

## Problem

Valid two- and three-argument `vswap!` calls are reported as error-level invalid arities, blocking current-source publication and every database-backed test population.

## Evidence

`seon.fn.analyzer/analyze` reports `src/seon/render/block.clj:717` and `src/seon/render/walk.clj:247,363` as requiring four arguments, while `clojure -M:dev` loads both namespaces and the calls match `clojure.core/vswap!`.

## Root cause (2026-07-31, falsification lane — measured)

Not a clj-kondo defect. **The runtime reply lint poisons the shared clj-kondo
cache that the build indexer reads.**

`.clj-kondo/.cache/v1/clj/clojure.core.transit.json` records `vswap!` at **row
556 with arglist `[_ _ vol f & args]` and varargs-min-arity 4**. Linting the
Clojure jar alone in an isolated directory records the CORRECT entry — **row
2560, min-arity 2, macro true**:

```bash
cd /tmp/kondo-poison && mkdir -p .clj-kondo
clj-kondo --lint ~/.m2/repository/org/clojure/clojure/1.12.5/clojure-1.12.5.jar \
          --dependencies --parallel
# => "~$vswap!" … "^1",2560 … macro true, varargs-min-arity 2
```

`[_ _ vol f & args]` is a macro fn's arglist including the implicit `&form`/
`&env` parameters — the shape SCI's `clojure.core` macro implementations carry.
`seon.fn.analyzer/analyze-forms` (`src/seon/fn/analyzer.clj:288-330`) synthesises
a `defn` prelude from `:seon.fn/arglists` (added by `b028556b5` "Preserve runtime
lint arities", 2026-07-30) and calls the SAME `invoke-kondo`, which uses the SAME
`:cache-dir ".clj-kondo/.cache"` (`src/seon/fn/analyzer.clj:8-9,107-115`).
clj-kondo persists that synthesized `clojure.core` analysis over its builtin
definitions; the build indexer then reads it and refuses `src/`.

Proof the sources are clean: redirecting `cache-directory` to a private path
process-locally yields **0 error findings** over the identical `src/` tree
(`tmp/falsify/00f.clj`, `tmp/falsify/harness.clj:15-16`).

Two mechanisms share one cache directory and one of them writes agent-derived
fiction into it. Repair belongs at that seam — the runtime reply lint must use
its own cache directory (or no persistent cache) — not at clj-kondo's Var
metadata.

Also blocking-listed in the same gate: the deliberately malformed
`test/seon/schema_edn_fixtures/unreadable/bad.edn` fixture (4 parse errors). The
indexer's blocking gate should filter to Clojure sources.

## Blast radius (2026-07-31)

`cluster/populate-source!` throws `Static program analysis found blocking
errors.`, so `bin/seon init`, fresh cluster population, and every
`seon.test-support/with-database` test currently fail — including
`seon.test-support-test/a-canonical-database-is-the-production-source-population`,
`seon.schema.datahike-test`, `seon.sci.eval-instrumentation-test`, and
`seon.test-runner-test`.

## Owner

`seon.fn.analyzer`'s cache-directory ownership: the runtime reply-lint path and
the build indexing path must not share one persisted clj-kondo cache.

## Acceptance

Valid `vswap!` arities analyze cleanly, an actually invalid arity still refuses, current-source publication advances, and the render/context namespace gate reaches its assertions.

## Second kondo type defect, same wave (2026-07-31)

clj-kondo also mistypes `(volatile! x)` as returning `nil`, blocking any
edit that derefs a volatile local (observed twice by the sci ground-truth
lane). Same analyzer-correction wave as the `vswap!` arity.
