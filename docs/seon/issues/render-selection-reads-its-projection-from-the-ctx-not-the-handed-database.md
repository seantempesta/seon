---
type: issue
status: open
severity: friction
tags: [issue, render, class/p1, schema-projection]
---

# Render selection derives its projection from the SCI ctx, not the database it is handed

`seon.render/project-node*` (`src/seon/render.clj:1060`) opens with

```clojure
(let [projection (some-> (:seon.sci.eval/ctx request)
                         sci.kernel/context-projection)
```

and every selection question — which shapes a value matches, whether a
producer's output satisfies the output schema — is asked of THAT projection.
The request's `:seon.db/db` and the projection it carries are not consulted,
even though the value being rendered was pulled from that database value.

This is the §2.1 shape inverted. `seon.db` reads use the database's carried
projection first and the supplied one only as a fallback
(`src/seon/db.clj:950`); database values carry their projection state
(`src/seon/db.clj:141`); evaluation binds a database carrying that state
(`src/seon/sci/eval.clj:2250`). Selection alone reaches past the value to a
ctx-held copy, so rendering a database value from a DIFFERENT branch, or one
read before a schema change, selects producers from whatever the ctx's
projection says. It is also the reason a caller cannot answer "what would this
render as under projection P" without mutating the shared ctx state.

Consequences observed, not hypothesised: while fixing
[the stale-Var sentence](pulling-a-function-row-in-sci-returns-a-restart-the-jvm-sentence.md)
the A/B — same value, same database, projection with and without one declared
pair — could not be run through the render floor at all, because the only way
to change the projection selection sees is to mutate the shared fixture ctx's
projection state. The proof had to be taken one level down at
`seon.render/schema-producers`. A seam that cannot be probed with a supplied
value is the same defect that makes it read stale state.

No incorrect render has been attributed to this yet; it is filed as the
structural cause, ahead of the incident.

Acceptance: selection derives its projection from the database value the
request carries, with the ctx projection as the fallback for a request that
carries no database — the same precedence `seon.db` already uses — and one
regression renders the same value under two supplied projections and gets two
different selections without touching any ctx.

surface: render-selection

Found by the `sci-pull` lane, 2026-09-16; see
[its landing note](../../prds/steward-platform/research/sci-pull-restart-sentence-2026-09-16.md).
