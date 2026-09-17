---
type: research
status: active
created: 2026-09-17
tags: [research, sci, program-graph, analysis, test]
---

# Two baseline reds: the evaluated declaration analysis and the schema row shape

Bounded triage-and-fix lane. Subjects: cold gate batch 115 B
(`tmp/orchestrator/gate-results/batch-115.log`, retained root
`tmp/test-runs/run.Z4ufFh`, HEAD `defd915cd`), red at HEAD across several
baselines:

1. `seon.sci.documentation-test/bare-test-macros-resolve-without-namespace-referrals`
2. `seon.sci.eval-test/declared-row-evaluates-a-schema-once-inside-its-delta`

Both are consequences of ONE commit: `6312fcef0` "Unify indexed and evaluated
declaration analysis" (2026-09-16T13:11), which replaced the SCI seam's
metadata-derived declaration row with the static indexer's analysis
(`seon.fn/source-rows`) and, in the same commit, dropped `:seon.schema/ns`
from the evaluated schema row.

## 1. A bare `deftest` analysed to no declaration at all — FIXED

### What the log says

```
FAIL in (bare-test-macros-resolve-without-namespace-referrals) (documentation_test.clj:176)
expected: (= "fixture.bare-tests/durable-arithmetic" (:seon.test/sym row))
actual: (not (= "fixture.bare-tests/durable-arithmetic" nil))
ERROR ... seon.db/transact! refused transaction at [0]: expected a map, got nil.
```

The ERROR is downstream of the FAIL: the test transacts
`(program/canonical-row row)` of the absent row, so `transact!` is handed
`[nil]` and correctly refuses. There is no second defect at the writer.

### Root cause, measured

`6312fcef0` rewrote `seon.sci.eval/definition-row` (`src/seon/sci/eval.clj:408-419`)
to derive the row from `seon.fn/source-rows`, i.e. from clj-kondo analysis of
the submitted source under a namespace form built by
`seon.fn/runtime-namespace-form` (`src/seon/fn.clj:738`) out of the namespace
row's own requires/aliases/refers. The interpreter, however, binds `deftest`,
`is`, `doc`, `dir` and `help` in `clojure.core` itself
(`src/seon/sci/eval.clj:236-241`, again at `:1390-1391`), so an agent's
namespace resolves them with NO referral. The analysis context did not.

clj-kondo on the two cases (measured, clj-kondo CLI, `:analysis true`):

| source | `var-definitions` |
|---|---|
| `(ns fixture.bare-tests)` + `(deftest durable-arithmetic …)` | `[]`, three `:unresolved-symbol` findings |
| same with `(:require [clojure.test :refer [deftest is]])` | one entry, `:defined-by clojure.test/deftest`, `:test true` |

`seon.fn/var-row` (`src/seon/fn.clj:611`) mints a test row only on
`::analyzer/test`, which only the second case carries. So the bare form
produced no row — absence of signal, read by the evaluator as "no
declaration".

### The fix

The analysis of a submitted form must resolve exactly what its evaluation
resolves, and the set is derived, not listed:

- `resources/seon/schemas/seon.sci.binding.edn` now declares
  `:seon.sci.binding/deftest` and `:seon.sci.binding/is` with
  `:seon.sci.binding/target clojure.test/deftest|is`, in the same grammar the
  existing `/help`, `/dir` and `/doc` bindings already use (attribute name =
  the bare name; target = the Var interned). `seon.program/base-context-injected-symbols`
  already yielded both symbols through `:seon.sci.binding/testing`'s
  `public-namespace` expansion, so `build-base-ctx` is byte-unchanged.
- `seon.fn/interpreter-refer-rows` (new, `src/seon/fn.clj:738`) derives one
  refer row per declared `:seon.sci.binding/target` from the operation's own
  declaration population (`db/carried-projection`, falling back to
  `schema/declaration-population`), and `runtime-analysis-batch` folds them
  into the analysed namespace row's `:seon.ns/refers`. `runtime-require-specs`
  already turns refers into libspecs, so this adds no second mechanism.

## 2. The evaluated schema row lost its declaring namespace — HELD, NOT FIXED

### What the log says

```
FAIL in (declared-row-evaluates-a-schema-once-inside-its-delta) (eval_test.clj:1067)
expected {… :seon.schema/ns [:seon.ns/name user]}
actual   {… :seon.schema/shape #:seon.schema.shape{…}}   ; and no :seon.schema/ns
```

Two independent differences, not one:

- `:seon.schema/shape` is ACCRETION and the expectation is stale.
  `6312fcef0` extended `seon.program/with-contract-facts`
  (`src/seon/program.cljc:875-880`) to assoc the canonical shape row onto
  every schema row; `seon.program-test` was updated in the same commit,
  this assertion was not.
- `:seon.schema/ns` is a REGRESSION, at the writer. The same commit changed
  `seon.sci.eval/row` (`src/seon/sci/eval.clj:303`) from
  `(select-keys event [:seon.schema/key :seon.schema/form :seon.schema/ns])`
  to `(select-keys event [:seon.schema/key :seon.schema/form])`. The reader
  supplies the fact (`src/seon/sci/reader.cljc:410`), the canonical rebuild
  preserves it when the candidate carries it
  (`src/seon/program.cljc:1002-1003`), and the committed row is
  `(or var-row reader-row)` — so nothing re-derives it and an
  agent-declared schema now records no namespace at all.

That fact has live consumers: `src/seon/render/ns.clj:893` lists a
namespace's schemas from `[?schema :seon.schema/ns ?namespace]`,
`src/my/program.clj:264` names them, and
`seon.cluster.turn-test/runtime-schema-registration-commits-the-evaluated-form-and-attribute`
(`test/seon/cluster/turn_test.clj:1067`) asserts "the namespace ref records
where the global declaration was authored". That regression did not run in
batch 115.

### Why this lane did not land it

Both sides live in files the S3 acquisition lane holds dirty:
`src/seon/sci/eval.clj` (the writer) and `test/seon/sci/eval_test.clj` (the
expectation). A path-limited commit of either would carry that lane's
in-flight work. The exact hunks, for whoever holds those files:

```clojure
;; src/seon/sci/eval.clj:303 — restore the declaring namespace
   (when (:seon.schema/key event)
-    (assoc (select-keys event [:seon.schema/key :seon.schema/form])
+    (assoc (select-keys event [:seon.schema/key :seon.schema/form
+                               :seon.schema/ns])
            :seon.schema.admission/source :agent))
```

```clojure
;; test/seon/sci/eval_test.clj (HEAD :1067, working tree :1224) — the row
;; now carries its derived shape; compare the declaration facts exactly and
;; the shape by its own identity.
-    (is (= {:seon.schema/key :user/direct-schema
-            :seon.schema/ns [:seon.ns/name 'user]
-            :seon.schema/form "[:int {:min 0}]"
-            :seon.schema.admission/source :agent
-            :seon.schema/generatable? true}
-           (:seon.sci.eval/base-declared-row result)))
+    (let [row (:seon.sci.eval/base-declared-row result)]
+      (is (= {:seon.schema/key :user/direct-schema
+              :seon.schema/ns [:seon.ns/name 'user]
+              :seon.schema/form "[:int {:min 0}]"
+              :seon.schema.admission/source :agent
+              :seon.schema/generatable? true}
+             (dissoc row :seon.schema/shape)))
+      (is (= {:seon.schema.shape/type :int
+              :seon.schema.shape/form "[:int {:min 0}]"
+              :seon.schema.shape/properties "{:min 0}"
+              :seon.schema.shape/comparison :exact}
+             (select-keys (:seon.schema/shape row)
+                          [:seon.schema.shape/type :seon.schema.shape/form
+                           :seon.schema.shape/properties
+                           :seon.schema.shape/comparison]))
+          "the canonical shape accretes; its fingerprint and normalization
+           revision are Malli's, not this regression's"))
```

Issue: [Schema declaration regression disagrees with the current row shape](../../../seon/issues/schema-declaration-regression-disagrees-with-current-row-shape.md).

## Verification boundary

```
bin/test-fast --paths src/seon/fn.clj resources/seon/schemas/seon.sci.binding.edn \
  -- seon.sci.documentation-test seon.fn-test
```

Snapshot HEAD `333b1bf2d` plus this lane's two paths, 2026-09-17T02:40Z:
`Ran 67 tests containing 532 assertions. 0 failures, 0 errors.`
`bare-test-macros-resolve-without-namespace-referrals` ran green in 1.1s
(verify log line 43-44), and `seon.fn-test` — the owning regressions for
`analyze-form`/`analyze-forms`, which now carry the interpreter refers in
every analysed namespace form — is green with it.

This is ITERATION evidence: no cold `bin/test` gate was run by this lane, so
the isolated per-worker proof, the platform tier and the recorded result
facts are not claimed. Red 2 (`declared-row-evaluates-a-schema-once-inside-its-delta`)
remains red at HEAD until the two hunks above land in the held files; it was
not run in this verification (`seon.sci.eval-test` was deliberately excluded
because its HEAD expectation is one of the two hunks).
