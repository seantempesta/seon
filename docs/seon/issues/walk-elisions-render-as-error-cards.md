---
type: issue
status: open
severity: friction
tags: [issue, render, web, context]
---

# Stop rendering a walk elision as an error

## Problem

`elided connections at the requested distance cap` is a normal, expected
outcome of a bounded walk. It renders as an ERROR CARD: a red-bordered block
carrying `data-error-kind=":seon.render.walk/elided"`. One ordinary agent page
carried 21 of them, and they are by far the loudest thing on the page — a
reader's eye reports the system as broken when nothing is wrong.

The same notice is the single most repeated line in the AI projection: the
agent's prompt OPENS with 42 consecutive lines of elision notices before any
content (86 lines for a namespace-assigned agent).

## Evidence

Observed 2026-07-31 on `/agent/scout` (`tmp/visual-qa/agent-scout-tall.png`):

```text
$ rg -o 'data-error-kind="[^"]*"' agent-scout.html | sort | uniq -c
  21 data-error-kind=":seon.render.walk/elided"
$ rg -c 'seon-error-card' agent-scout.html
  11
```

AI side: `tmp/visual-qa/ai-scout.txt:2-43` and
`tmp/visual-qa/ai-flowkeeper.txt:2-87` are nothing but paired
`;; path=… provenance=:seon.render.walk/elided` / `elided connections …`
lines.

## Owner

`seon.render.walk` elision units and `seon.render.web/surface-html`'s error
card.

## Acceptance

An elided branch is not an error card and does not carry `data-error-kind`.
In the AI projection the frontier is stated ONCE (a single line naming how
many branches were cut and how to widen `:depth`), not once per cut edge.
