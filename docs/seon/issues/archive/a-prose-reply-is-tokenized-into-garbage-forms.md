---
type: issue
status: resolved
severity: high
tags: [issue, agent, runtime, ai]
---

# A prose reply was tokenized into garbage forms instead of refused

## Problem

When the model answers in English rather than in Clojure forms, the reply
reader does not refuse it — it splits the prose on whitespace and freezes each
WORD as an executable form. The recorded reply became 23 frozen forms: 22
tokens from one prose sentence plus its trailing completion form. Eighteen
prose tokens became errors; four silent successes were worse than the
failures. The bare tokens `1`, `10` and `55` evaluated to themselves, and the
word `get` resolved to `clojure.core/get` and committed
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

Turn 2 asked "What did you just do?" and the model replied with one prose
sentence followed by `(my.run/complete "reported")`. Run
`e3cb1899-97d1-46f6-85c2-01819a766e94` froze 23 forms whose first 22 sources
are the words of that sentence:

```text
ordinal 0  "I"          -> Unable to resolve symbol: I
ordinal 1  "defined"    -> Unable to resolve symbol: defined
ordinal 2  "a"          -> Unable to resolve symbol: a
ordinal 8  "1"          -> 1
ordinal 14 "10"         -> 10
ordinal 16 "get"        -> {:seon.sci.admit/opaque "clojure.core$get"}
ordinal 17 "55"         -> 55
ordinal 18 ", and"      -> Can't take value of a macro: #'clojure.core/and
ordinal 22 "(my.run/complete \"reported\")" -> completed
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

## Resolution

Resolved by the commit that archives this note. `seon.cluster.reply/sources`
still uses SCI's `source-reader` and `parse-next+string` for every boundary;
there is no whitespace splitter and no new Markdown parser.

Admission is one whole-reply decision:

- A code reply has at least one structured top-level form.
- A bare symbol remains valid only when it occupies its own source line inside
  that structured reply. This preserves the established `(def widgets ...)`,
  `widgets`, completion plan.
- Any other atomic form makes the whole original reply text. Nothing before or
  after it is partially admitted, and the exact text remains in the
  `::no-forms` error value.

The live reply therefore returns `::no-forms`: neither `get` nor its trailing
completion list becomes a plan source, so the run freezes zero forms and
commits zero eval receipts.

## Proof

- `bin/test seon.cluster.reply-test seon.cluster.turn-test`:
  25 tests, 126 assertions, 0 failures, 0 errors.
- Changed-test gate generation 664:
  116 tests, 552 assertions, 0 failures, 0 errors.
- `bin/test` on the integrated current tree:
  422 tests, 1,683 assertions, 0 failures, 0 errors. The suite grew from the
  requested 418/1,651 baseline while this bounded lane was running.
- `test/seon/cluster/reply_test.clj` covers pure code with its standalone
  `widgets` form, pure prose with exact text retention, and the exact live
  word-salad reply reconstructed from the retained log.
