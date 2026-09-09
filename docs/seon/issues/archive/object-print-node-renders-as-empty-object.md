---
type: issue
status: resolved
severity: friction
tags: [issue, render, print, repl, ugly-output]
---

# An object print node renders as an empty `#object[]`

## Evidence — 2026-09-07

Seen by the `result-handles` lane while proving the REPL response grammar.
A stored `:seon.print/object` node — the face admission produces for a value
that cannot cross the boundary, an atom for instance — emits nothing inside
its brackets:

```clojure
(seon.repl/render-ai
 {:db/id 9
  :seon.cluster.eval/source "(atom 1)"
  :seon.cluster.eval/result-edn
  "#:seon.print{:face :seon.print/object, :name \"clojure.lang.Atom\"}"})
;; => "user=> (atom 1)\n#:seon.repl{:value #object[]}"
```

The node CARRIES the name (`"clojure.lang.Atom"`); the emitter drops it. The
agent reads `#object[]` and learns nothing at all — not the class, not that a
handle was withheld, not what it could do instead. Ruling 59c is right that
such a value gets no handle; it does not follow that its rendering should say
nothing.

## Why it is a defect, not a cosmetic

UGLY OUTPUT IS A DEFECT is a standing order, and this is the sharper kind: the
render is TOTAL (it does not throw) and it is EMPTY, so the reader cannot tell
an unrenderable value from an empty one. That is the project's recurring
failure class — a signal whose absence reads as content.

## Where

`seon.print`'s emitter for `:seon.print/object` (the node is built in
`src/seon/sci/admit.clj`, printed through `src/seon/print.cljc`, and reaches
the agent through `seon.repl/value-text`, `src/seon/repl.clj`).

## Smallest correct fix

Emit the name the node already holds — `#object[clojure.lang.Atom]` — so the
projection is a function of the facts stored, with nothing dropped. Fix it at
the print emitter, where every consumer of the node benefits, never in
`seon.repl`.

## Not in scope of the lane that found it

The `result-handles` lane owned the handle derivation, not the print grammar.

## Resolution — 2026-09-07

`seon.print`'s `emit ::object` now prints the class the node carries, under
either the `:seon.print/class` admission mints or the `:seon.print/name` a
stored node may hold, and a node that names NO class emits the flat
`:seon.print/object-without-class` diagnostic instead of empty brackets — the
absent signal reports itself rather than reading as content
(`src/seon/print.cljc`, `emit ::object`). Regression:
`seon.print-test/an-object-node-never-renders-as-empty-brackets`.
