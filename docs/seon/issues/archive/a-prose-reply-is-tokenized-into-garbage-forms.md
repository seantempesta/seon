---
type: issue
status: resolved
severity: high
tags: [issue, agent, runtime]
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
working correctly per receipt. What is wrong is one level up: prose tokens are
note content, never individual forms. The reader must preserve any real forms
in source order without manufacturing executable history from the surrounding
English.

Note the archived
`archive/prose-token-line-recovery-swallowed-same-line-forms.md`: a previous
system had a prose-token recovery path and its own defect. The quarry
comparison in
`docs/prds/sci-execution-runtime/research/reply-parser-quarry-2026-07-29.md`
showed that its useful property was per-span classification: prose became
narration and real forms survived.

## Owner

The reply reader (`seon.cluster.reply`) with `seon.cluster.run/plan-*` as the
downstream consumer, and `seon.cluster.loop` for the refusal's disposition.

## Resolution

The first resolution in `2a49cbd75` killed the word-salad class but made one
whole-reply decision. Quarry review then found the measured regression: the
retained live reply was one prose sentence followed by the valid completion
form the prompt requested.

`seon.cluster.reply/sources` still uses SCI's `source-reader` and
`parse-next+string`; there is no whitespace splitter and no restored
rewrite-clj/parinferish stack. Its revised admission is:

- Structured top-level forms beginning a code line, or following another form
  on that line, remain ordered plan sources. A parenthesized expression
  mentioned inside an English line remains prose.
- A bare symbol remains a form only on its own source line in a reply that also
  contains structure, preserving `(def widgets ...)`, `widgets`, completion.
- Every other readable atom coalesces back into prose, uses the single-`;`
  comment grammar, and attaches to the next form.
- Trailing or pure prose becomes a comment-only source. SCI reads it as nil,
  so no prose token resolves or invokes anything.
- Invalid prose tokens are commented and re-read; malformed code still returns
  `::unreadable` with the SCI reader's position.

The live reply now freezes ONE source: the sentence as a comment followed by
`(my.run/complete "reported")`. None of `I`, `1`, `10`, `get`, or `55` becomes
an independent form or receipt.

## Proof

- `bin/test seon.cluster.reply-test seon.cluster.turn-test`: 26 tests, 130
  assertions, 0 failures, 0 errors.
- `test/seon/cluster/reply_test.clj` covers pure code, pure prose, mixed
  prose/forms, invalid-token same-line recovery, unbalanced code, fenced
  Markdown, and the exact live word-salad reply reconstructed from the retained
  log.
- `bin/test`: 430 tests, 1,703 assertions, 0 failures, 0 errors (the shared
  tree had advanced from the requested 422/1,683 baseline).
