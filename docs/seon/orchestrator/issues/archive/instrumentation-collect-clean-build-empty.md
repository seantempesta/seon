---
type: issue
status: resolved
tags: [issue, agent, schema]
severity: blocker
---

# Instrumentation collects almost nothing in a clean build (collect! expands too early)

## Resolution (2026-06-24)

Replaced the compile-time scan with a **runtime scan of the program graph**
— the DB is the complete, ordering-independent source of every fn + spec
(`code as data`). `seon.instrument/instrument-from-db!` queries all
`:seon.fn/sym` + `:seon.fn/spec` rows, resolves each live var, detects
async from the var (`AsyncFunction` ctor), routes through `register-target!`
(skip-syms honored), and `mi/instrument!`s once. Called in `start-agent!`
AFTER `index-core!` indexes core fns (and after replay tees agent fns).
`install!` + the boot use of the `collect!` macro are gone.

Why the DB beats the macro: `index-core!` already writes a `:seon.fn` row
with `:seon.fn/spec` (= `m/form` of the schema) for every PUBLIC schema'd
core fn; the eval-tee writes the same for every agent fn. So the DB has the
COMPLETE set independent of compile order — no entry-ns caveat, no macro.

Verified on a **clean** build (`rm -rf .shadow-cljs/builds/client`):
boot logs `instrumentation: {:registered 204 :skipped 18 :no-var 0
:bad-spec 0}` (was 3 nses / 25 fns); `seon.eval/result-var-ref?`
instrumented; verbs skipped; replay 14/14; `bin/test-cljs` green.

(An interim fix expanded `collect!` from the entry ns `seon.client` — it
worked too, 48 nses / 205, but still leaned on the compile-time macro and a
"must be in the entry's closure" caveat. The DB scan supersedes it. The
`collect!` macro is retained only as a compile-time test utility for
`db_test`, which has no DB.)

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
