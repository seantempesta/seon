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
[results-as-data audit](../../prds/sci-execution-runtime/research/results-as-data-audit-2026-08-14.md).

## Owner

`seon.print` owns the elision value and the one `fit` boundary.

## Acceptance

Every elision in every projection is the elision VALUE — one representation,
always data, whichever cap fired — and the second spelling in `seon.db` is
deleted rather than kept in parallel. One regression drives both cap paths over
the same value and asserts both results carry the same elision value shape.
