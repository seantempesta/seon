---
type: issue
status: resolved
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

## Verified at HEAD (2026-09-16, N1 verification)

**RESOLVED by deletion of the owner.** `src/seon/cluster/run.clj` does not
exist at HEAD; the run family was folded into `seon.turn` by `7296d173b`
("Rename turn facts and writer into seon.turn"). Both named seams are gone:
`grep -rn "render-form-ai\|render-receipt-ai" src/` matches only a docstring
reference in `src/seon/context.clj:12`, and no `"Form N: "` source prefix or
`Form N returned …` receipt template survives anywhere in `src/`.

What replaces them satisfies the acceptance. A real stored failed
evaluation from the live `default` cluster (pid 69622), rendered through
`seon.repl/text`, is the submitted source followed by its actual outcome as
DATA:

```text
user=> [:find ?note :in $ ?subject :where [?note :my.note/agent ?subject] …]
#:seon.repl{:error "Execution error (ExceptionInfo) at sci.impl.utils/throw-error-with-location (utils.cljc:67).\nUnable to resolve symbol: ?note", :ns my.agents.juniper, :ms 3}
```

No ordinal narration, no result annotation, no background guidance in
result position.

The CLASS this note points at — a declared family producer replacing a
queried value — is alive and is tracked where it belongs, on
[an-entity-pull-returns-a-sentence-instead-of-its-attributes](an-entity-pull-returns-a-sentence-instead-of-its-attributes.md),
which now carries fresh HEAD evidence including a pulled turn entity that
renders to the empty string. Closing this note does not close that one.
