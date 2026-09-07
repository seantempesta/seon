---
type: issue
status: resolved
severity: friction
tags: [issue, render, print, repl, ugly-output, class/p1]
---

# An admission-minted elision node cannot name its requery identity

## Evidence — 2026-09-07

Seen by the `prompt-and-entity-merge` lane while closing friction **F1** of
[the REPL and record audit](../../prds/context-generation/research/audit-repl-and-record-2026-09-07.md).
The audit reported that a clipped value emits a refusal SENTENCE inside
`:seon.repl/value`:

```
#:seon.repl{:value [0 … 1 more subtree; requery refused: no stable identity was supplied at path [] offset 0 with :seon.render.profile/unspecified]}
```

The audit's proposed fix — hand `value-text` the unit's elision root and
render profile — **cannot work**, and this note records why so nobody spends
the afternoon rediscovering it.

`seon.sci.admit` mints a cut as a BARE scalar
(`elide!`, `src/seon/sci/admit.clj:110-116`; `append-elision!`, `:135-143`):

```clojure
(node ::print/elided)      ; => {:seon.print/face :seon.print/elided}
```

with no `:seon.print/omitted`, no path, no offset, and no requery field.
`seon.print`'s emitter for that face reads the NODE and nothing else
(`src/seon/print.cljc:547-549` → `render-elision-ai`, `:284-303`), so every
missing field falls back to a default and the requery clause becomes
`"requery refused: no stable identity was supplied"`.

The one function that would repair such a node — `seon.print/fit`, whose
`fit-children`/`preserve-requery` mint a complete `elision-node` from a
profile carrying `::requery-id` (`src/seon/print.cljc:694-830`) — **is a
no-op today** because presentation-size limits are disabled
(`src/seon/print.cljc:1000-1009`, "Return one admitted node without
presentation-size cuts").

Proven, not inferred (both lines identical):

```clojure
(print/emit-text node (assoc (print/default-options)
                             :seon.print/requery-id [:seon.cluster.eval/id "x"]))
;; => "[0 … 1 more subtree; requery refused: no stable identity was supplied …]"
(print/emit-text (print/fit node {:seon.render.profile/id :seon.render.profile/agent
                                  :seon.print/requery-id [:seon.cluster.eval/id "x"]})
                 (print/default-options))
;; => the same bytes
```

So no caller above `seon.print` — `seon.repl/value-text`,
`seon.render.value/render-ai`, `seon.render.transcript/bounded-result` — can
name the source of a cut it did not make. Every one of them already computes
the right identity and hands it to a seam that discards it.

## Why it matters

This is the recurring class in a new coat: the diagnostic that says nothing
when its subject is absent (AGENTS.md §2.4). An elision is supposed to be
ordinary data carrying count, path, and requery identity; an admission
elision carries a face and a lie.

## The root fix, not the instance

The identity belongs where the cut is MADE. `seon.sci.admit` knows the
evaluation it is admitting for; it should mint the complete elision node —
`::omitted`, `:seon.render.data/path`, `:seon.render.data/next-offset`, and
`::requery-id` from the evaluation's own identity — exactly as
`seon.print/elision-node` already shapes one. Then no downstream caller has
to repair anything, and `render-elision-ai` tells the truth by construction.

Owned by neither `seon.repl` nor `seon.render`: `src/seon/sci/admit.clj` and
`src/seon/print.cljc`.

## Resolution — 2026-09-07

The cut now carries its own evidence, minted where the cut is MADE.
`seon.print/elision` is the ONE public constructor for an elision node
(`seon.print/fit`'s private `elision-node` delegates to it), and
`seon.sci.admit`'s walk threads a path so every cut records
`:seon.render.data/path`, `/next-offset`, `:seon.print/omitted` and
`/elision-unit`, plus `:seon.render.data/total` when the source is `counted?`
— an infinite or lazy source is still never counted.

The identity is HANDED IN: an admission request may carry
`:seon.print/requery-id`, and `seon.sci.eval/evaluate` supplies the
evaluation's own `[:seon.cluster.eval/id …]` for both the settled and the
failed admission, because that identity is already derived there. An
admission nobody is storing supplies none and its cuts carry the honest
refusal instead. `seon.repl/value-text` therefore emits
`requery by [:seon.cluster.eval/id "…"] at path […] offset N` with no change
of its own. Regression:
`seon.sci.admit-test/every-admission-cut-names-its-path-count-and-source`.
