---
type: issue
status: open
severity: high
tags: [issue, agent, runtime, ai]
---

# A prose reply is tokenized into garbage forms instead of refused

## Problem

When the model answers in English rather than in Clojure forms, the reply
reader does not refuse it — it splits the prose on whitespace and freezes each
WORD as an executable form. One sentence became nineteen frozen forms and
nineteen committed receipts, seventeen of them `Unable to resolve symbol`
failures, and two of them silent successes that are worse than the failures:
the bare tokens `1`, `10` and `55` evaluated to themselves, and the word `get`
resolved to `clojure.core/get` and committed
`{:seon.sci.admit/opaque "clojure.core$get"}` as a result.

The agent's next prompt then renders that wreckage as its own history, so a
single formatting mistake by the model becomes durable, self-reinforcing
garbage in the agent's context.

## Evidence

Found by the namespace+distance context pilot's live drive against the local
Qwen snapshot (`tmp/context-pilot-live-drive.clj`, log
`tmp/context-pilot-live.log`, 2026-07-28 20:03). Turn 1 succeeded exactly as
intended — the model returned real forms, `(sum-to 10)` committed `55`, and
`(my.run/complete "55")` closed the run.

Turn 2 asked "What did you just do?" and the model replied in prose. Run
`e3cb1899-97d1-46f6-85c2-01819a766e94` froze 19 forms whose sources are the
words of that sentence:

```text
ordinal 0  "I"          -> Unable to resolve symbol: I
ordinal 1  "defined"    -> Unable to resolve symbol: defined
ordinal 2  "a"          -> Unable to resolve symbol: a
ordinal 8  "1"          -> 1
ordinal 14 "10"         -> 10
ordinal 16 "get"        -> {:seon.sci.admit/opaque "clojure.core$get"}
ordinal 17 "55"         -> 55
ordinal 18 ", and"      -> Can't take value of a macro: #'clojure.core/and
```

The two-word token `", and"` shows the split is not even a clean tokenizer.

## Why this is not the agent's mistake

An agent mistake becomes a flat `:seon.error` value the agent sees, which is
working correctly per receipt. What is wrong is one level up: a reply that
contains no readable form is a REFUSAL condition for the reader, and the run
should carry one `:seon.cluster.run/error` saying the reply was not forms —
the shape `interruption`'s retired doctrine and the run family's lens already
know how to present. Instead the reader manufactures a plan, so "nothing
re-executes" is honoured while "nothing meaningless executes" is not.

Note the archived
`archive/prose-token-line-recovery-swallowed-same-line-forms.md`: a previous
system had a prose-token recovery path and its own defect. This is the same
class re-appearing in the fresh tree, and the fix is not another recovery
heuristic — it is a reader that either reads forms or refuses.

## Owner

The reply reader (`seon.cluster.reply`) with `seon.cluster.run/plan-*` as the
downstream consumer, and `seon.cluster.loop` for the refusal's disposition.

## Acceptance

- A reply containing no readable top-level form freezes NO forms and closes
  the run with a `:seon.cluster.run/error` naming the condition.
- A reply containing prose AROUND readable forms freezes only the forms; the
  prose is not a form and never becomes one.
- A bare symbol or literal that happens to resolve is not admissible as a form
  merely because it evaluated — the falsifier is `get` committing an opaque
  function reference.
- One regression per class, driven from a recorded prose reply rather than a
  hand-built plan.
