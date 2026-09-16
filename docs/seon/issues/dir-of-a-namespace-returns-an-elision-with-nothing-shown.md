---
type: issue
status: open
severity: blocker
tags: [issue, sci, render, repl, agent-context]
---

# `dir` of a real namespace returns an elision value and no content

Observed 2026-09-16 on `default` (pid 45917), SCI evaluation mode, namespace
`my.agents.root`, read-only:

```clojure
(dir seon.turn)
```

shown text, verbatim:

```clojure
{:seon.print/bound-by :seon.render.profile/token-budget,
 :seon.print/elision-unit :characters,
 :seon.print/omitted 41040,
 :seon.render.data/next-offset 0,
 :seon.render.data/path [],
 :seon.render.data/total 41040}
```

41,040 characters omitted, ZERO shown. The agent asked what is in a namespace
and received a count of what it was not told. `:seon.render.data/path []` is
the root, so there is no narrower path to requery, and no
`:seon.print/requery-form` reached the agent either — the elision names the
bound but hands back no way through it.

`doc` is fine: `(doc seon.turn/next-agent-work)` returns the complete
documentation map with `:arglists`, `:summary`, `:body`, `:in`, `:out`,
`:supplied`.

Two things are wrong and they are separable:

1. A whole-value cut at depth 0 should not be an empty report. AGENTS.md §2.4:
   a floor hit is counted, never silent — and a cut that shows nothing is the
   silent case wearing a count. `dir` is the agent's index into a namespace;
   the profile should bound it by MEMBERS, showing as many rows as fit, not
   refuse the value whole.
2. `:seon.render.data/total 41040` is a CHARACTER count in agent-facing
   output. AGENTS.md §2.4 rules display sizes for humans are estimated tokens
   via `seon.ai.tokens/estimate`; character counts are storage projections and
   surfaces still showing them are defects to file.

Why it matters now: with
[the function-row pull returning a stale-Var sentence](pulling-a-function-row-in-sci-returns-a-restart-the-jvm-sentence.md)
these are the two halves of "the agents could not read the source" recorded in
the issue-context trials
(`docs/prds/steward-platform/research/issue-context-trials-2026-09-16.md`).
That one is fixed; this one is not, and `dir` is the wider surface — it is how
an agent finds a function before it can pull or `doc` it.

Acceptance: `(dir <namespace>)` returns members — as many as the profile
admits, with an elision naming the bound, the count in estimated tokens, and a
requery form or offset that actually reaches the rest. One regression through a
real SCI context on the canonical harness asserting the shown text contains
member symbols.

surface: render-value / repl

Found by the `sci-pull` lane while fixing the pull defect; see
[its landing note](../../prds/steward-platform/research/sci-pull-restart-sentence-2026-09-16.md).
