---
type: issue
status: open
severity: blocker
tags: [issue, render, context, agent]
---

# Render an assigned namespace's functions, not just its `ns` form

## Problem

An agent assigned to a real namespace sees, as its own workspace, the `ns`
form and the referenced schemas — and NONE of the namespace's functions. No
omission note either; the definitions are silently absent. Assigning an agent
to an existing namespace is therefore useless: the agent cannot see the code
it owns.

`seon.render.ns/full-ai-text` (the distance-1 branch, i.e. the agent's own
namespace) emits function sources only `(when stub? …)`, and `bare-source?`
(`src/seon/render/ns.clj:286-289`) is true only when the stored
`:seon.ns/source` is literally `(ns the.name)`. Any namespace with a
docstring or a `:require` — every first-party namespace — takes the empty
path.

## Evidence

Observed 2026-07-31, cluster `visual-qa`, agent `flowkeeper` assigned to
`seon.flow`:

```clojure
;; facts present
[48 22]  ;; :seon.fn rows in seon.flow, of which 22 carry :seon.fn/spec
(count (seon.render.ns/render-ai {:seon.db/db db :db/id 944
                                  :seon.ns/name 'seon.flow
                                  :seon.render/distance 1}))
;; => 1883   (the ns form + referenced schemas; zero functions)
```

`rg '^; fn seon\.flow/' tmp/visual-qa/ai-flowkeeper.txt` → no matches, in a
92 KB context. The nursery agent `scout`, whose namespace source IS a bare
`(ns my.agents.scout)`, does see its one definition — the gate is the source
shape, not the facts.

## Owner

`seon.render.ns/full-ai-text`.

## Acceptance

An agent assigned to `seon.flow` reads its own namespace's public function
contracts (and, within budget, sources) in its context; when the budget
truncates, the omission note states how many definitions were dropped. The
distance-1 branch honours the token budget instead of branching on whether
the `ns` form happens to be bare.
