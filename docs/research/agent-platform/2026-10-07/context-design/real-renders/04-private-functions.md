---
type: notes
status: active
created: 2026-10-07
tags: [notes, context-design, render]
---

# References: `seon.db/query-input-values`, `seon.db/query-patterns`, `seon.db/parsed-nodes`

Kind: private functions named in the task's intent (the intent spells them bare: `query-patterns/parsed-nodes`,
`query-input-values`; the corrected text qualifies them). Declared AI render: `:seon.fn/fn` →
`seon.render.ns/function-ai`. One file holds the three because each reply is small; each was a separate call.

```clojure
(seon.render.ns/function-ai (fn-unit 'seon.db/query-input-values))
(seon.render.ns/function-ai (fn-unit 'seon.db/query-patterns))
(seon.render.ns/function-ai (fn-unit 'seon.db/parsed-nodes))
```

Replies as the agent sees them (0 ms each; 81, 70 and 70 armed calls):

```clojure
#:seon.repl{:value {:seon.fn/sym seon.db/query-input-values,
 :seon.render.ns/source-lines
   ["(defn- query-input-values"
    "  {:malli/schema [:=> [:cat :seon.schema/value [:sequential :seon.schema/value]] :map]}"
    "  [parsed-query arguments]" "  (or (apply merge-with set/union"
    "             (map query-binding-values (:qin parsed-query) arguments)) {}))"]}, :result result/e6918a178dba9, :ms 0, :calls 81}
#:seon.repl{:value {:seon.fn/sym seon.db/query-patterns,
 :seon.render.ns/source-lines
   ["(defn- query-patterns" "  [parsed-query]"
    "  (filter #(instance? Pattern %) (parsed-nodes (:qwhere parsed-query))))"]}, :result result/e550860286349, :ms 0, :calls 70}
#:seon.repl{:value {:seon.fn/sym seon.db/parsed-nodes,
 :seon.render.ns/source-lines
   ["(defn- parsed-nodes" "  [root]"
    "  (tree-seq #(or (map? %) (coll? %))" "            #(cond"
    "               (map? %) (vals %)" "               (coll? %) %"
    "               :else nil)" "            root))"]}, :result result/ed25ddd3b9831, :ms 0, :calls 70}
```

## What is wrong

- **The same string-per-line packing** as `seon.db/q` (see 03). Short functions survive it, but `parsed-nodes` reads
  as a puzzle.
- **What the task needs most is missing: the call sites.** "Walked 5 times per call" is a claim about *callers*. The
  render shows none. Today `query-patterns` has seven callers (`my.program/callers`: `seon.db/malformed-query-pattern-error`,
  `query-variable-attributes`, `query-find-attributes`, `encode-query-request`, `native-query-result?`,
  `may-read-vectors?`, `query-value-type-error`), answered only by that separate call.
- **No contract shown for the two uncontracted helpers**, and no note that they lack one. `query-patterns` and
  `parsed-nodes` are unarmed, so the proof's armed-call counter cannot see them. The Var render's
  `:seon.render.var/contract :missing` would say so, but `function-ai` does not.

## What the render should do

- For a private subject: source as code, `:callers` as symbols, and `:contract :missing` when absent.
