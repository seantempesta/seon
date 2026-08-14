---
type: issue
status: open
severity: friction
tags: [issue, render, web, class/n1, wave/visual-qa]
---

# Wrap the debug page's AI pane instead of running 23,000 px wide

## Problem

The debug page's `:seon.render/ai` pane is a `<pre>` with `white-space: pre`,
so a rendered result never wraps. One line runs 23,552 px wide inside a 615 px
container. The pane does scroll horizontally, so nothing is strictly
unreachable — but reading a single `dir` result means scrolling 23,000 px
sideways and back, and at rest every visible line is cut mid-token.

This is the pane whose whole purpose is letting a human read the exact prompt
the model was given.

## Evidence

Measured live 2026-08-14 at 1280x720 on both targets:

```javascript
getComputedStyle(pre).whiteSpace          // "pre"
getComputedStyle(pre).overflowX           // "visible"
getComputedStyle(section).overflowX       // "auto"  (.seon-debug-body-ai)
section.clientWidth                       // 639
section.scrollWidth                       // 23564
Math.max(...pres.map(p => p.scrollWidth)) // 23552
```

Identical widest-line figure (23,552 px) on
`http://127.0.0.1:55156/agent/drive-one-agent-attempt-5/debug` and
`http://127.0.0.1:7994/agent/root/debug`. The longest single line measures
3,260 characters within a 74-line, 34,964-character pane.

What the reader sees at rest, every line cut at the pane edge mid-token:

```text
my.agents.drive-one-agent-attempt-5=> (db/pull db (quote [*]) [:seon.cluster.run/id "bo
my.agents.drive-one-agent-attempt-5=> (db/pull db (quote [*]) [:seon.cluster/name "defa
my.agents.drive-one-agent-attempt-5=> (db/pull db (quote [*]) [:seon.config/cluster "de
my.agents.drive-one-agent-attempt-5=> (db/pull db (quote [*]) [:seon.cluster.instructio
```

Full walk:
[ui-verification-2026-08-14](../../prds/sci-execution-runtime/research/ui-verification-2026-08-14.md).

## Owner

`resources/public/css/input.css` — the `.seon-debug-body-ai` pane. If the
displayed text is meant to be the exact prompt bytes, the wrap belongs in the
presentation, not in the rendered string.

## Acceptance

The debug AI pane's lines wrap within the pane at desktop width; the pane's
`scrollWidth` does not exceed its `clientWidth` by more than a token. The
displayed characters remain byte-identical to the prompt — only their visual
wrapping changes — and no line is cut mid-token at rest.
