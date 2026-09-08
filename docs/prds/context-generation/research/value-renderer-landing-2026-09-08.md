---
type: research
status: blocked
date: 2026-09-08
tags: [render, performance]
---

# Value renderer baseline

No implementation has landed yet. The baseline below exercises the existing
loaded Vars through MCP JVM evaluation on root `/Users/sean/src/seon`, cluster
`default`, pid 14049. It is not an adoption or an agent-turn proof. Concurrent
test and development work was active; these are single observations, not
isolated benchmark distributions. Input construction is outside the timers.

| Input | AI ms | HTML ms |
|---|---:|---:|
| 100,000-element vector | 1993.589250 | 5119.195958 |
| 5 MiB ASCII string | 841.141750 | 1437.115166 |

Both AI observations violate the assignment's 100 ms target. HTML returned
Hiccup in both cases; this probe does not assert that every child reached HTML.

Exact vector AI bytes (one line):

```text
[0 1 2 … 99997 more children of 100000; bounded by :seon.render.profile/max-children; requery by result/e0123456789ab at path [] offset 3 with :seon.render.profile/test]
```

## Reproducible probe

Run as one form in MCP JVM mode with explicit root and cluster:

```clojure
(let [profile {:seon.render.profile/id :seon.render.profile/test
               :seon.render.profile/token-budget 1024
               :seon.render.profile/max-depth 4
               :seon.render.profile/max-children 3
               :seon.render.profile/composition :single-line}
      caps (seon.config/result-caps (seon.config/defaults))
      unit {:seon.render.call/id [:seon.render.value-test/probe]
            :seon.render.value/root 'result/e0123456789ab
            :seon.render/profile profile
            :seon.sci.admit/caps caps
            :seon.render.value/options
            {:seon.render.value/structural? true}}]
  (mapv
   (fn [[label raw]]
     (let [request (assoc unit :seon.render/value raw)
           start (System/nanoTime)
           ai (seon.render.value/render-ai request)
           middle (System/nanoTime)
           html (seon.render.value/render-html request)
           end (System/nanoTime)]
       {:probe/value label
        :probe/ai-ms (/ (- middle start) 1e6)
        :probe/html-ms (/ (- end middle) 1e6)
        :probe/ai ai
        :probe/html? (vector? html)}))
   [[:vector (vec (range 100000))]
    [:string (.repeat "x" (* 5 1024 1024))]]))
```

## Source grounding

- `src/seon/render/value.clj:222` admits the whole value before
  `prepare` fits its AI projection at line 569.
- `src/seon/print.cljc:397` emits identity prose instead of a requery form.
  `fit-entry` at line 1043 uses entry indexes rather than associative keys.
- Clojure's `get-in` is repeated `get`, not print-node navigation:
  `reference-code/clojure/src/clj/clojure/core.clj:6288`.
- Clojure's string printer quotes and escapes through its character escape
  table: `reference-code/clojure/src/clj/clojure/core_print.clj:212`.
- The existing map-layout defect already has an issue:
  [the-value-floors-map-face-is-not-readable-edn.md](../../../seon/issues/the-value-floors-map-face-is-not-readable-edn.md).
  Its second map formatter is still present at `value.clj:443`.

## Verification

Both baseline commands exited 1 before executing renderer assertions:

```text
bin/test --paths src/seon/render/value.clj src/seon/print.cljc resources/seon/schemas/seon.print.edn resources/seon/schemas/seon.render.value.edn test/seon/render/value_test.clj test/seon/print_test.clj -- seon.render.value-test seon.print-test seon.repl-test
```

The isolated snapshot reported HEAD
`38d2fc91bdf42d944ead9e8d3998ca33cabc1415` and no snapshot differences.
The executing `bin/test:464` read
`(:seon.dev-cache/test-classpath-file selection)` from a cache result that
did not carry that key, then failed with `Cannot open <nil> as a Reader`.
The working-tree `dev_cache.clj:475` adds the key; the snapshot's older
producer does not. Both the gate and cache producer had foreign uncommitted
edits. This interface-version mismatch is already recorded in
[bin-test-shared-base-compiles-other-lanes-half-edits.md](../../../seon/issues/bin-test-shared-base-compiles-other-lanes-half-edits.md).

```text
bin/test-fast seon.render.value-test seon.print-test seon.repl-test
```

Fast iteration acquired the packaged projection, then failed loading
protected `src/seon/turn.clj:135:64`:

```text
Syntax error reading source at (seon/turn.clj:135:64).
#:clojure.error{:phase :read-source, :line 135, :column 64, :source "seon/turn.clj"}
```

The assignment explicitly requires stopping on foreign in-flight breakage.
No production edits were made, no other lane was contacted or modified, and
both owned background test commands exited. `--platform` was not started
after this mandatory stop; no green gate is claimed. All six implementation
requirements remain open. The exact bytes above are the defective baseline,
not a claimed repaired render.
