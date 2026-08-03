---
type: issue
status: open
severity: friction
tags: [issue, agent, render, architecture]
---

# Render run forms and receipts with strict REPL fidelity

## Problem

The run-family AI renderer rewrites a planned form as `Form N: …` and rewrites
its receipt as English such as `Form N returned …`, `failed`, `was
interrupted`, or `is still running`. Owner decision 11 requires the displayed
session to show the exact form followed by its actual computed value, not a
narrated substitute for either side of the REPL exchange.

## Evidence

`seon.cluster.run/render-form-ai` at `src/seon/cluster/run.clj:1134-1141`
prefixes the source with an ordinal sentence. `render-receipt-ai` at
`:1148-1184` converts results, errors, printed output, interruptions, pending
state, and background guidance into English sentences. The superseding ruling
is decision 11 in
[messaging, state, and reply-norm design](../../prds/sci-execution-runtime/research/messaging-state-design-notes-2026-08-03.md).

## Owner

`seon.cluster.run` owns the form and receipt family renderers; their HTML twins
must continue to project the same facts without forcing the AI projection into
narration.

## Acceptance

The AI session displays the form's exact submitted source followed by the
actual computed value and ordinary printed output. Errors and interruptions
remain honest values, and background guidance moves to `(help)` or an explicit
query result. No `Form N …` narration, comment framing, result annotation, or
comment-only pseudo-entry remains. Recurring render coverage asserts forms and
values rather than English sentence templates.
