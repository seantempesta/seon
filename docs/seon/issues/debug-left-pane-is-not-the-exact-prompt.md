---
type: issue
status: open
severity: friction
tags: [issue, web, render, class/n1, wave/visual-qa]
---

# Make the debug left pane the exact bytes the agent received

## Problem

`/agent/{id}/debug` exists so a human can read the AI context exactly as the
model saw it. Its left pane is not those bytes:

- the walk HEADER line is missing — the pane starts at the first elision
  notice, so the `root=… basis=… depth=…` line the agent actually reads is
  invisible; and
- values print differently (`{:db/id 747}` in the pane versus `#:db{:id 747}`
  from a REPL walk), because `*print-namespace-maps*` differs between the two
  callers. Nothing tells the reader which spelling the agent got.

Presentation-wise the pane is unreadable regardless: text neither wraps nor
scrolls, so every line is clipped mid-word at the pane boundary.

## Evidence

Observed 2026-07-31, `/agent/scout/debug` (`tmp/visual-qa/debug-scout.png`):

```text
left pane chars 31360 | walk ai chars 31307 | identical: False
+;; (seon.render/walk {:root [:seon.cluster.agent/id "scout"], :depth 2})
+   => root=… basis=536870972 depth=2          # present in walk, absent in pane
-{:seon.cluster/instructions [{:db/id 747}] …  # pane
+{:seon.cluster/instructions [#:db{:id 747}] … # walk
```

Screenshot: left pane lines end in `;; path=[:seon.render.walk/neighbour`
and `elided connections at the requested` at the column boundary; the right
pane's tables reflow to two characters per cell (`:db` / `/id`, `visual-` /
`qa`).

## Owner

`seon.render.web/debug-response` and the debug page's CSS.

## Acceptance

The left pane equals the recorded `:seon.context.capture/prompt` for the
agent's latest run, byte for byte, including the header. Long lines wrap or
the pane scrolls; neither pane clips content at its boundary.

## Backlog triage 2026-08-02

**Still real, but the old formatting symptoms are fixed.** `9aa9bf8d1` made
the pane render the current AI walk and gave it wrapping/scrolling CSS, so the
missing-header and namespace-map spelling evidence above is historical.
Current `debug-page-of` still calls `ai-walk` against `@connection`; it never
reads the latest committed `:seon.context.capture/prompt`. A later transaction
can therefore change the pane after the model call. The remaining destination
is the visual-QA context-capture wave: select the latest capture for the agent
and render those exact bytes, with current-walk inspection remaining a
separately labelled surface if retained.

## N1 disposition — 2026-08-12

Still open in protected `src/seon/render/web.clj`. Change `debug-response` to
select the latest committed `:seon.context.capture/prompt` for the agent and
put those exact bytes in the left pane; retain current-walk inspection only as
a separately labelled value. The N5 completion lane owns this file.
