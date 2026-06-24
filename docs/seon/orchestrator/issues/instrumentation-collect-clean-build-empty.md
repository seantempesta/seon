---
type: issue
status: completed
tags: [issue, agent, schema]
severity: critical
---

# Instrumentation collects almost nothing in a clean build (collect! expands too early)

## Resolution (2026-06-24)

Fixed by expanding `collect!` from the entry ns. `seon.instrument/install!`
no longer calls `collect!`; instead `seon.client` refers `collect!` as a
macro (`:refer-macros [collect!]`) and calls `(when (enabled?) (collect!))`
in `-main` before `(install!)`. Because `seon.client` is compiled last
(after its whole transitive closure), the `ana/all-ns` scan is complete.

Verified on a **clean** build (`rm -rf .shadow-cljs/builds/client`):
instrumentation went from 3 nses / 25 fns → **48 nses / 205 fns**;
`seon.eval/result-var-ref?` is instrumented again; verbs still skipped;
agent replay 14/14. Caveat (documented in `install!`): a first-party ns
NOT in `seon.client`'s transitive closure would still be missed — fine for
the pod (everything that runs is reachable from the entry).

---

## Problem

`seon.instrument/install!` is supposed to instrument every first-party
`:malli/schema` fn at boot. In a **clean build it instruments only 3
namespaces / 25 fns** (`seon.db`, `seon.schema`, `seon.test.runner`) —
everything else (`seon.eval`, `seon.agent`, `seon.ctx`, `seon.render`,
`seon.ai`, the handlers, …) gets **no instrumentation at all**. So in a
fresh production build / `bin/seon cluster reset`, runtime schema
validation is effectively OFF for the vast majority of the codebase.

This was masked during development: incremental `cljs-watch` rebuilds
accumulate analyzer state, and every edit to `instrument.cljc`
re-expanded the scan against a fuller `ana/all-ns`. A dev who had been
editing saw ~188 fns instrumented and assumed it worked. A clean build
(`rm -rf .shadow-cljs/builds/client` + rebuild) drops it to 25.

## Reproduction

```
rm -rf .shadow-cljs/builds/client
bin/seon restart cljs-watch        # clean rebuild
bin/seon restart pod
# pod.log: "instrumentation installed {... :n-instrumented 25}"
```

Then in the pod runtime:

```clojure
(require '[malli.core :as m])
(keys (m/function-schemas :cljs))
;; => (seon.db seon.schema seon.test.runner)   ; only 3 nses
(boolean (get-in (m/function-schemas :cljs) ['seon.eval 'result-var-ref?]))
;; => false                                    ; core fn NOT instrumented
```

## Root cause

`install!` calls `(collect!)` in its body, and `install!` lives in
`seon.instrument.cljc`. The `collect!` macro scans `cljs.analyzer.api/all-ns`
and bakes a `(do (register-target! …) …)` block **at macroexpand time** —
i.e. when `instrument.cljc` itself compiles. `instrument.cljc` is an
**early leaf dependency** (required by `seon.error.instrument` → `seon.schema`
chain and by nearly everything), so when its `collect!` expands, `all-ns`
contains only the handful of namespaces analyzed before it. The baked
roster is therefore tiny and fixed at `instrument.cljc`'s compile.

This is a deviation from Malli's intended CLJS pattern: `malli.instrument/collect!`
defaults to `{:ns *ns*}` and is meant to be called **per-namespace** (each ns
registers its own publics at its own compile, when its analyzer entry is
complete). The seon version does one global `all-ns` scan from a single
early site — which is exactly the ordering-fragile thing.

## Impact

- **Critical for the "always-on instrumentation" goal.** Production boots
  with ~3 nses instrumented; bug-catching (e.g. the `result-var-ref?`
  `nil`-vs-`:boolean` bug) does not fire for almost any core fn.
- The agent-eval **tee path** (`seon.eval/instrument-tee-fns!`) is
  unaffected — runtime/agent-defined fns still get instrumented as they're
  eval'd. Only the **boot scan of COMPILED core fns** is broken.

## Fix direction

Expand the scan from a namespace that is compiled **last** and whose
transitive closure is the whole pod — the entry ns `seon.client`. Concretely:
move the `collect!` expansion out of `install!`'s body (leaf `instrument.cljc`)
and into `seon.client` (`:require-macros [seon.instrument :refer […]]`), so
`all-ns` is complete when it expands. Verify the clean-build count jumps from
25 to ~near-total and that `seon.eval/result-var-ref?` is instrumented.

Incremental adds stay correct: adding a core fn recompiles its ns AND its
dependent `seon.client` (which requires it transitively), re-running the
scan. Runtime/agent fns continue through the tee path. A namespace that is
NOT in `seon.client`'s transitive closure would still be missed — acceptable
for the pod (everything that runs is reachable from the entry), but worth a
doc note. (A heavier-but-bulletproof alternative is a shadow `:build-hook`
that scans after full compilation, or Malli-idiomatic per-ns `collect!`
calls.)

## Discovered

2026-06-24, while extending instrumentation to async fns
([[project-always-on-instrumentation-2026-06-24]]). Pre-existing — the
`install!`-calls-`collect!`-in-`instrument.cljc` structure predates that work.
