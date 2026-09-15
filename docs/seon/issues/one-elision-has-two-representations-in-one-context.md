---
type: issue
status: open
severity: friction
tags: [issue, render, agent, class/n1, wave/strict-repl-display]
---

# Give an elision one representation, and make it the value

## Problem

An elision renders two different ways inside a single agent context, chosen by
which cap happened to fire: as an ordinary elision VALUE for most entries, and
as an English sentence glued outside a closing quote for the rest. The ruled
elision is ordinary data carrying count, path, next offset, and requery
identity; the English twin carries the same facts in a form the agent has to
re-parse.

## Evidence

Observed live 2026-08-14 in the Drive 1 stored capture facts
(`tmp/drive-1-root`), in the same context, from the same call. Nine namespaces
end with the honest value:

```text
{:seon.print/face :seon.print/elided, :seon.print/omitted 46, :seon.print/elision-unit :children,
 :seon.render.data/total 69, :seon.render.data/path [], :seon.render.data/next-offset 23,
 :seon.render.profile/id :seon.render.profile/agent, :seon.print/requery-id [:seon.ns/name my.web]}
```

`my.background` alone becomes a quoted, double-escaped string with the sentence
appended outside the quote — so the agent must un-escape a string to read a
vector, then parse English to learn the offset:

```text
my.agents.root=> (dir (quote my.background))
"[(ns my.background (:require [my.run :as run] …)) {:seon.fn/sym \"my.background/await\", … must mark a background call with no resu"… 1641 more characters of 3279; requery by [:seon.render.call/id [:seon.render/ai [:seon.ns/name my.background] 2]] at path [] offset 1638 with :seon.render.profile/agent
```

The prose tail is `seon.print/render-elision-ai`
(`src/seon/print.cljc:283-301`); `seon.db` carries a second spelling of the
same sentence at `src/seon/db.clj:1666`.

Full walk and counts:
[results-as-data audit](../../prds/context-generation/research/results-as-data-audit-2026-08-14.md).

## Owner

`seon.print` owns the elision value and the one `fit` boundary.

## Acceptance

Every elision in every projection is the elision VALUE — one representation,
always data, whichever cap fired — and the second spelling in `seon.db` is
deleted rather than kept in parallel. One regression drives both cap paths over
the same value and asserts both results carry the same elision value shape.

## Verified at HEAD (2026-09-16, N1 verification)

**CONFIRMED — the filed spelling is gone; the class is not.**

Fixed: `seon.print/render-elision-ai` (`src/seon/print.cljc:455-465`) now
always emits the elision VALUE, and both cap paths agree. Probed live on
`default` (pid 69622) with a complete render request:

```text
;; max-children path
(vec (range 200))
=> [0 1 … 31 {:seon.print/bound-by :seon.render.profile/max-children,
              :seon.print/elision-unit :children, :seon.print/omitted 168,
              :seon.render.data/next-offset 32, :seon.render.data/path [],
              :seon.render.data/total 200}]

;; token-budget path
(apply str (repeat 4000 "x"))
=> {:seon.print/bound-by :seon.render.profile/token-budget,
    :seon.print/elision-unit :characters, :seon.print/omitted 4000,
    :seon.render.data/next-offset 0, :seon.render.data/path [],
    :seon.render.data/total 4000}
```

No `… N more characters of M; requery by …` prose survives anywhere in
`src/`.

Still open: TWO English twins remain, and one of them sits in the value
renderer itself. `seon.render.value/render-ai-data`
(`src/seon/render/value.clj:464-469`) glues a sentence outside the value
whenever the paged display is truncated. Demonstrated on the same cluster
by preparing one projection and rendering it with and without the flag:

```text
truncated? true  => [0 1 … 31 {…:seon.print/omitted 28 …}] ; elided — this value is larger than the configured window
truncated? false => [0 1 … 31 {…:seon.print/omitted 28 …}]
```

Its HTML twin is the `[:p {:class "seon-data-capped"} "elided — this value
is larger than the configured window"]` node at
`src/seon/render/value.clj:456-457`. A third spelling lives in the database
diff face: `"\nFull data elided (approximately " full-size " tokens);
requery by " (pr-str requery-id) "."` (`src/seon/db.clj:2350-2354`).

So an agent can still meet two representations of one omission in one
context — value for a cap that fired inside the walk, prose for a paging
cut or a diff. That is this note's exact Problem statement.

surface: render (`seon.print` owns the elision value and the one `fit`
boundary)

Fix sketch: the paging cut is a cap like any other — have `prepare` append
the elision VALUE for `:seon.render.value/more?`/offset instead of setting
`:seon.render.value/truncated?`, then delete `render-ai-data`'s tail and
the `seon-data-capped` paragraph; replace `src/seon/db.clj:2350-2354` with
the same value. One regression drives the walk cap, the paging cut and the
diff over one value and asserts three elision values of the same shape.
