---
type: issue
status: resolved
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
#=> "~$vswap!" … "^1",2560 … macro true, varargs-min-arity 2
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

The adjacent suspicion that the deliberately malformed
`test/seon/schema_edn_fixtures/unreadable/bad.edn` fixture also entered this
gate was false in the repaired tree. `seon.fn/build-manifest` already derives
and passes only `.clj` and `.cljc` paths (`src/seon/fn.clj:48-65,405-409`).

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

Reply lint retains genuine findings without writing its synthesized program
prelude to the build cache; a following build analysis retains clj-kondo's
builtin arities and types. `cluster/populate-source!`, database-backed tests,
and complete `current-src` publication succeed.

## Second kondo type defect, same wave (2026-07-31)

clj-kondo also mistypes `(volatile! x)` as returning `nil`, blocking any
edit that derefs a volatile local (observed twice by the sci ground-truth
lane). Same analyzer-correction wave as the `vswap!` arity.

The stable sci interrupt-guard fix exposed the same poisoned numeric inference
for a primitive `long-array`: kondo treated `(aget ^longs entries 0)` as nil at
`src/seon/sci/eval.clj:267`, then reported the valid `bit-and` at the sampling
mask as an error. The JVM compiler loaded the namespace with reflection
warnings only in pre-existing dependency code. This is additional evidence for
the same analyzer/cache repair, not a reason to suppress type findings
globally.

## Resolution (2026-07-31)

Resolved by `9a073b146`. `seon.fn.analyzer/analyze-forms` now invokes the one
clj-kondo owner with `:cache false`. Pinned clj-kondo therefore resolves the
reply invocation's cache directory to nil: its packaged builtin analysis stays
available, but the synthesized program prelude is neither read from nor
written to disk. Build-time `analyze` retains the persistent project cache.

The prelude does not guess which leading arguments are SCI's implicit macro
parameters. `src/seon/cluster/loop.cljc:131-145` currently projects runtime
functions without the SCI Var's `:macro` metadata, so an analyzer-local rule
could not distinguish `[_ _ value]` on a macro implementation from the same
honest ordinary-function arglist. Blind positional stripping would create a
second false arity authority; cache isolation resolves the publication blocker
without it.

One recurring interaction regression at
`test/seon/fn/analyzer_test.clj:187` uses a private UUID cache and runs build
analysis → reply analysis → build analysis. It proves that the reply path still
reports an invalid persisted-function arity while the following build retains
valid `volatile!`/`vswap!` analysis. Removing only `:cache false` makes that
regression fail with both poisoned arities and the false volatile deref type.

## Proof

- After the one-time `.clj-kondo/.cache` remediation, a fresh JVM built 124
  source artifacts with zero `volatile!`/`vswap!` findings and manifest digest
  `f81d3d750c09743680c30ff2cbaabec713b3284176192d53c9779e3735ba69f8`.
- `bin/test seon.fn.analyzer-test` passed 6 tests / 34 assertions. The
  production `with-database` population passed 6 tests / 13 assertions, and
  `seon.schema.datahike-test` passed independently at 4 tests / 9 assertions.
- The actual `seon.cluster.loop/lint-form` returned a finding-bearing refusal;
  immediate build re-analysis in that fresh JVM still had zero builtin
  findings.
- `bin/seon init` published `current-src` commit
  `6a6cbb07-612d-5769-aa52-b2d6162d3c34` with digest
  `327696b647c35bad4a5d687aa1cc44f4a3b10d9ee9e07923f2b4efdd6d150abc`.
- One combined affected-namespace run exposed an independent load-order
  failure: requiring `seon.schema.datahike-test` registers its test-local
  `::title` globally before `seon.test-support-test` compares the canonical
  population. Both namespaces pass independently; this issue did not edit or
  conceal that separate test isolation defect.
