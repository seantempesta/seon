---
type: landing
status: landed
lane: fix-ns-clause-reader
created: 2026-09-24
report: docs/research/agent-platform/one-happy-path/reading-2026-09-24.md (namespace clauses row)
---

# Lane fix-ns-clause-reader — 2026-09-24

The reader read namespace clauses with its own parser. It now uses Clojure's
grammar for `clojure.core/ns`: the `:clojure.core.specs.alpha/ns-form` and
`::ns-require` specs, which also check `ns` when it macroexpands. The hand
parser for clauses, prefixes and options is gone. Aliases, refers and
requires now come from the conformed value.

## Broken or unproven first

- **The running JVM cannot see this change.** Hook publication is off, so
  default runs the committed archive `data/source/ce73846…`. Proof below comes
  from a `git archive` snapshot of HEAD plus this diff. Default adopts the
  change only when it is next started from the checkout.
- **`seon.sci.reader-test` already fails 19 assertions on HEAD `e45e27d73`
  without this diff** (28 tests, 267 pass). With the diff there are 29 tests,
  277 passes and the same 19 failures, at shifted lines. Causes:
  - Tests compare `:seon.fn/sym` and `:seon.test/sym` to strings, but the
    reader emits symbols.
  - `declaration-facts-equal-the-current-var-lift` finds no `read` row.
  - Two property tests fail (`#unaccepted/tag` and the span partition).
  - None of these touch namespace clauses. They are outside this lane and no
    issue records them yet; root should file one.
- **Three-level prefixes are refused.** Clojure refuses a form like
  `[clojure [tools [reader :as r]]]` (kondo: "Prefix lists can only have two
  levels"). When the spec refuses an `ns` form, the reader claims no namespace,
  just as it does for `(ns)`. Following forms lose attribution.
- **One gap in the spec is not mirrored.** The spec accepts a dotted lib inside
  a prefix, such as `[clojure [tools.reader :as r]]`, which `load-lib` refuses.
  The reader joins the names to `clojure.tools.reader`.
- **The new private helpers have no `:malli/schema`.** They are
  `libspec-pair`, `conformed` and `require-pairs`. The neighbouring private
  helpers have none either.
- The defn-exclusion and trailing-attribute-map cases were not touched. They
  belong to `function-declaration`, which the observation-based gate retires.
  Once Var metadata from the SCI intern diff is the truth, neither case
  matters.

## Evidence

- **Before**, from the live reader (JVM mode):
  - Input:
    `(ns x (:require [clojure [edn :as e] set] [a.b :as-alias ab] [clojure.test :refer [deftest] :rename {deftest dt}] c.d))`
  - Result: requires `#{c.d clojure.test clojure}`, with no `e` alias and no
    `clojure.set`.
- **After**, from the edited source loaded into the throwaway namespace
  `scratch.fix-ns-clause-reader.new`:
  - requires `#{c.d clojure.test clojure.set clojure.edn}`
  - aliases `e→clojure.edn` and `ab→a.b`
  - refer `dt→clojure.test/deftest`
- **Equivalence:** namespace rows (name, doc, requires, aliases, refers) are
  equal between the old and new readers for all 437 `src`/`test` files. All
  437 `ns` forms conform to the spec.
- **Timing:** `seon.sci.reader/read` on `src/seon/fn.clj` (181k chars), 20
  runs after 3 warm-ups, interleaved:

  | Run | Old | New |
  |---|---|---|
  | 1 | 19.2 ms | 24.2 ms |
  | 2 | 18.7 ms | 19.6 ms |

  - The first pair includes JIT warm-up. The steady-state gap is 0.9 ms (5%).
  - Allocation per read: 75.9 MB old, 77.1 MB new.
  - One `ns` conform takes 0.058 ms.
  - The "over one second" line from the profiler is the probe loop (46 reads),
    not one operation.
- **Regression:** `namespace-clauses-follow-clojures-own-ns-grammar` passes all
  10 of its assertions in the snapshot. It covers:
  - a prefix list, and a nested `:refer` with `:rename` inside it
  - `:as-alias`
  - a bare symbol
  - auto-resolved keywords and syntax-quote through the new bindings
  - a standalone `require` with a prefix list
  - a refused three-level prefix

## Changes

- `src/seon/sci/reader.cljc`
  - Deleted: `namespace-clauses`, `symbol-name`, the swallowed
    `apply hash-map`, and the raw vector/symbol cases of `require-bindings`.
  - Added: `libspec-pair`, `conformed` and `require-pairs`.
  - `namespace-info` and `literal-require-bindings` now go through the spec.
  - Net +6 lines (67 added, 61 deleted).
- `test/seon/sci/reader_test.clj`: +36 lines for the one class regression.
- Seam: `clojure.core.specs.alpha` 0.4.74 (`~/.m2/…/core.specs.alpha-0.4.74.jar`):
  - `::libspec`, `::prefix-list`, `::ns-require`, `::ns-clauses`: lines 121–136
  - `::ns-form`: lines 211–227
  - `:refer` conforms to `[:all :all]` or `[:syms names]` (lines 118–119).
    Keys that are not declared, such as `:rename`, pass through `keys*`.
- Dependencies considered and rejected:
  - SCI `sci.impl.load/load-libs` (`reference-code/sci` fcbd8862,
    `load.cljc:294-322`) handles prefixes, but only while loading libs.
  - Kondo `normalize-libspec` (`analyzer/namespace.clj:43`, 57252e07) works on
    rewrite-clj nodes with lint context.
  - `tools.namespace` is not on the classpath.

RESET NEEDED: none. Default adopts the change at its next start from the
checkout.
