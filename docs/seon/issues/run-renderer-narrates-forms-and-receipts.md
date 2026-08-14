---
type: issue
status: open
severity: friction
tags: [issue, agent, render, class/n1, wave/strict-repl-display]
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

## Live capture evidence — 2026-08-14

Confirmed in the Drive 1 stored capture facts (`tmp/drive-1-root`). The form
narration appears verbatim in result position:

```text
my.agents.root=> (db/pull db (quote [*]) [:seon.cluster.run.form/id "[:seon.cluster.run.form/id \"bootstrap-supervision:root\" 1]"])
Form 1: (merge (my.message/send "drive-one-agent-attempt-5" "What are you doing?") (run/complete "Read drive-one-agent-attempt-5's recent history and asked what it is doing."))
```

The run narration accounts for 16 of 210 result positions and destroys 98.8% of
the queried value (6,596 characters over 11 attributes rendered as 79
characters of English).

The narration is NOT run-family-specific: the substitution happens in the
shared selection construction, so the cluster, config, message, error, problems
and schedule families do the same thing. Fixing this renderer alone leaves the
class alive — the class note is
[an-entity-pull-returns-a-sentence-instead-of-its-attributes](an-entity-pull-returns-a-sentence-instead-of-its-attributes.md),
with the complete walk in
[results-as-data audit](../../prds/context-generation/research/results-as-data-audit-2026-08-14.md).

## N1 disposition — 2026-08-12

Skipped because protected `src/seon/cluster/run.clj` belongs to the wedge
lane. The exact edit is to make the form producer return submitted source plus
the receipt's actual rendered value/error data, delete `Form N` and background
guidance narration, and update the focused run-render assertions to compare
forms and values rather than English templates.
