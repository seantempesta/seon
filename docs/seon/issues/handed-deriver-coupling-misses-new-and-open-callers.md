---
type: issue
status: open
severity: blocker
created: 2026-10-20
tags: [issue, sci, cache, branch-runtime, review4-astra]
---

# Handed-deriver coupling misses new and open callers

On `342e14357`, the coupling introduced by `959ec1a91` fixes the loaded `seon.bootstrap/groups` caller, but a new caller can still hand branch code to compiled `seon.health/derived`. Its cache entry is then shared with compiled main code. This is a residual of [the handed-derivation issue](a-compiled-function-caching-a-handed-derivation-serves-it-across-runtimes.md), with a different missing input: callers introduced after the loaded program snapshot.

`seon.sci.eval/acquire-program!` derives `seon.sci.eval/coupling` from the loaded database and holds its leaders in the program snapshot. `seon.sci.eval/bind-analysed!` updates changed names but does not add the new caller's edges to those leaders. `seon.sci.runtime/compiled-names` therefore keeps the deriver compiled even when the newly supplied callback invokes changed code. The contract permits that callback.

The acquired-context reproduction defines `review4/h` to return 2, binds its change through the production binder, then invokes a new `(fn [database key] (seon.health/derived database key (fn [_] (review4/h))))`. Compiled main calls the same owner on the same committed database and key with its derivation returning 1. Use a new key for each order.

| Order, shown as `[branch main]` | Expected | Actual |
|---|---|---|
| Branch first | `[2 1]` | `[2 2]` |
| Main first | `[2 1]` | `[1 1]` |

The deriver is identical to its compiled root in the branch. Candidate `5905a1194779b047ca3a2be7195dcbd639c8266a`, run `3a1a3dbda06d`; red proof `seon.runtime-review4-astra-test/a-new-caller-hands-its-own-derivation`.

The real MCP entrance confirms it: change the loaded fixture helper from 1 to 2, then define this contracted new caller through MCP:

```clojure
(in-ns 'seon.batch)
(defn review4-handed
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/value] :seon.schema/value]}
  [database key]
  (seon.health/derived database key (fn [_] (seon.review3-fixture/h))))
```

Call it through MCP with `(seon.db/db)`; compiled code calls `seon.health/derived` on the same branch database with the same callback source and key. Both orders reproduce the table. Setup returns the Vars with no error. Candidate `99ea74a0b1a6997da19abbe21512281d180f2654`, run `abc4d259ee31`; red `seon.runtime-review4-astra-test/an-agent-new-caller-hands-its-own-derivation`.

A second probe uses a **loaded open caller**, not a new caller. Contracted `seon.review3-fixture/open-handed` calls the same deriver with `(fn [_] ((resolve 'seon.review3-fixture/h-dynamic)))`. Only `h-dynamic` changes from 1 to 2. The same orders again answer `[2 2]` and `[1 1]`, expected `[2 1]`: candidate `1ad2412a1468f855b64bc0b71af875641c1cc043`, run `1544594d6d98`, red `seon.runtime-review4-astra-test/an-open-caller-hands-its-own-derivation`.

For this variant the caller is a leader, but `seon.sci.runtime/compiled-names` removes a deriver only when a leader is in the changed/closed-reached set. Open callers are not in the closed reach index, so their dynamic dependency changes do not select the deriver. Both variants refute the premise that the loaded, closed dependency evidence suffices to identify every handed derivation's executing program.

The issue is at the derivation's identity and the runtime's knowledge of its executing code. Extending a hand list of loaded callers would not establish the requirement for newly authored code or arbitrary compositions.

[Independent review and complete evidence](../../research/agent-platform/2026-10-20/runtime-review4-astra.md). [Reproducible probe patch](../../research/agent-platform/2026-10-20/runtime-review4-astra-probes.patch), kept as review evidence rather than installed production changes.
