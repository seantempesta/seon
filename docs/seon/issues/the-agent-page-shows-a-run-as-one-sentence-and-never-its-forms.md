---
type: issue
status: open
severity: blocker
tags: [issue, render, web, agent, class/n11, wave/ui-watchability, wave/live-drive-render]
---

# Show the session's turns on the agent page

## Problem

The agent page is where the owner goes to SEE a session. It does not show
one. A run renders as a single summary sentence; its forms, their values,
their printed output, and the model's reply text appear nowhere on the page.
The session's facts exist and one projection renders them — the debug page's
`:seon.render/ai` pane — but the agent page's `/html` projection never
descends into a run's forms and receipts.

Compounding it: the page's bulk is the toolkit namespace schema wall, so what
little session content exists sits at the very bottom under roughly 14 KB of
`register!` forms and schema declarations.

## Evidence

Observed live 2026-08-14 on the Drive 1 attempt-5 cluster,
`http://127.0.0.1:55156/agent/drive-one-agent-attempt-5`. The page holds 38
walk units and 16,266 characters of text. Its ONLY run blocks, complete and
verbatim:

```text
Run bootstrap:drive-one-agent-attempt-5, opened #inst "2026-08-14T11:25:09.152-00:00". It completed.
```

```text
Run a887d305-c8ae-4b6e-842f-43287f7f7496, opened #inst "2026-08-14T11:28:56.845-00:00". It did not run: The reply carried no Clojure forms — its whole text read as prose. Prose runs nothing and settles nothing; write the Clojure you want evaluated. Nothing was retried, and nothing it asked for ran.
```

The first of those summarizes a generated opening of 13 settled forms in 100
characters. Neither block carries any form source, returned value, printed
output, or receipt. The model's prose reply — the actual content of the paid
DeepSeek turn — does not appear anywhere in the page's text; the only trace
of it is the refusal sentence describing it.

The same session IS legible one route away, at
`/agent/drive-one-agent-attempt-5/debug`: the `:seon.render/ai` pane holds
34,964 characters across 30 `my.agents.drive-one-agent-attempt-5=>` prompts
with their rendered results.

Beside the run blocks the page shows only two message blocks and 17
`renderer unavailable` chips, with the left grid column almost entirely
empty.

Full walk with screenshots described:
[ui-verification-2026-08-14](../../prds/context-generation/research/ui-verification-2026-08-14.md).

## Owner

The agent entity's `:seon.render/html` projection and the neighborhood walk's
descent into a run's forms and receipts. Closely bound to
`agent-html-still-uses-the-retired-transcript-assembler` (which route owns the
session) and `run-renderer-narrates-forms-and-receipts` (what a form and a
receipt display) — this note is the observable those two produce together.

## Acceptance

A reader opening an agent page sees the session as turns: for each run, the
exact submitted source of each form followed by its actual computed value and
printed output, plus the model's reply, in order, separated per turn rather
than as one wall. The AI and HTML projections show the SAME entries, differing
only by profile and projection. A recurring proof asserts that a settled
run's rendered agent page contains each of its forms' sources and each
receipt's value.
