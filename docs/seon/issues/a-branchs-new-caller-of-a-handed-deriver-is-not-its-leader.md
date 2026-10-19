---
type: issue
status: open
severity: medium
created: 2026-10-20
tags: [issue, cache, sci, branch-runtime]
---

# A branch's new caller of a handed deriver is not its leader

Found by lane review4-opus on HEAD `342e14357`, 2026-10-20; evidence in
[runtime-review4-opus](../../research/agent-platform/2026-10-20/runtime-review4-opus.md) (B2). Class:
[a compiled function caching a handed derivation](a-compiled-function-caching-a-handed-derivation-serves-it-across-runtimes.md).

On a branch (any change, here `seon.print/literal`), an agent defines
`(ns rt4.h) (defn f [db] (seon.health/derived db :seon.health/untested (fn [_] #{'rt4/branch})))`. Same committed
branch database value, kinds `:seon.health/derived` and `:seon.health/reads` evicted before each order:

| Order | branch `(rt4.h/f db)` holds `rt4/branch` | compiled `(#'seon.health/untested db)` holds it | expected |
|---|---|---|---|
| branch first | true | **true** | true / false |
| compiled first | **false** | false | true / false |

`seon.health/derived` stays compiled on the branch (`derived-compiled-on-branch? true`).

Root cause: `seon.sci.eval/coupling` derives each handed deriver's `::leaders` once, from the loaded program's
callers, when `seon.sci.eval/acquire-program!` builds the shared program snapshot; a changed runtime reuses that
reach index. A caller the branch adds is not a leader, so `seon.sci.runtime/compiled-names` keeps the deriver compiled
and its lookup keys the branch's handed code by main's bare key. The collision needs the branch's derivation to use
a name main also derives (`:seon.health/untested` here); with a fresh name the entries never meet.

Red proof: probe `seon.runtime-review4-probe-test/h2-new-caller-of-a-compiled-deriver` (check run `31ca3ba7a255`,
records values only). Options: a branch's own definitions that call a handed deriver become its leaders (coupling of
the changed definitions, which the overlay holds), or a handed deriver runs analysed on every changed runtime.
