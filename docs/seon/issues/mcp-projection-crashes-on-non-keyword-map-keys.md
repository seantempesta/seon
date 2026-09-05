---
type: issue
status: open
severity: friction
tags: [issue, mcp, render, class/n1, wave/whole-system-arc]
---

# Project an MCP value whose map keys are not keywords

## Problem

`eval_clj` returns a ~40-frame raw stack trace instead of a value whenever the
result is a sorted map with non-keyword keys. The projection performs a keyword
lookup on any map; a `PersistentTreeMap` compares that keyword against its
`String` keys and throws `ClassCastException` during `:print-eval-result`.

The value computed fine. Only presenting it failed — and the failure dumps
forty frames of Clojure internals into the calling agent's or orchestrator's
context, which is both unreadable and expensive. Grouping results by a string
key is an ordinary thing to do at a REPL, so this is reachable by accident.

## Evidence

Cluster `default` (pid 31475), whole-system-arc observer lane, 2026-08-08.
Minimal reproduction through `eval_clj` in `jvm` mode:

```clojure
(sorted-map "a" 1 "b" 2)
```

Returns:

```text
class java.lang.String cannot be cast to class clojure.lang.Keyword
  [clojure.lang.Keyword compareTo "Keyword.java" 124]
  [clojure.lang.Util compare "Util.java" 153]
  [clojure.lang.RT$DefaultComparator compare "RT.java" 287]
  [clojure.lang.PersistentTreeMap doCompare "PersistentTreeMap.java" 330]
  [clojure.lang.PersistentTreeMap entryAt "PersistentTreeMap.java" 317]
  [clojure.lang.PersistentTreeMap valAt "PersistentTreeMap.java" 297]
  [clojure.lang.KeywordLookupSite$1 get "KeywordLookupSite.java" 45]
  [seon.cluster$evaluation_node invokeStatic "cluster.clj" 220]
  [seon.cluster$mcp_project invokeStatic "cluster.clj" 285]
  [seon.cluster$mcp_valf invokeStatic "cluster.clj" 364]
  … 30 more frames …
  :phase :print-eval-result
```

The owner is `src/seon/cluster.clj:214-224`:

```clojure
(defn- evaluation-node
  [value]
  (when (and (map? value)
             (string? (:seon.cluster.eval/result-edn value)))
    …))
```

`(:seon.cluster.eval/result-edn value)` is a keyword lookup applied to every
map that reaches the projection. For a sorted map the lookup goes through the
comparator, which cannot compare a `Keyword` to a `String`.

Hit accidentally while grouping observation counts by a string group name; the
same shape recurs for any `(into (sorted-map) …)` over string keys.

## Acceptance

- A value whose keys are not keywords projects as an ordinary value. The
  SCI-evaluation recognition test cannot throw on a well-formed Clojure map,
  whatever its key type or sortedness.
- No projection failure ever renders as a raw stack trace in the caller's
  context; a projection that genuinely cannot proceed returns a flat error
  naming the value shape it could not present.
- One regression proves the class: `(sorted-map "a" 1)` through `eval_clj`
  returns the map.

## N1 disposition — 2026-08-12

Still open at the MCP result projection. `4bc8104d8` proves ordinary maps with
unqualified keyword keys reach the render floor, but this member requires the
MCP recognition/projection code to stop assuming every map key is a keyword.
Project `(sorted-map "a" 1)` as an ordinary map and return a flat shape error
for genuine projection failures; do not stringify a stack trace.
