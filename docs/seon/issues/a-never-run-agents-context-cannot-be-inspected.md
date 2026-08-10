---
type: issue
status: open
severity: friction
tags: [issue, render, context, observability]
---

# Show a never-run agent's prospective context on its debug page

## Problem

The debug page's `:seon.render/ai` pane serves only the last recorded
`:seon.context.capture`. An agent that has never completed a turn therefore
has no inspectable context at all — which is exactly when onboarding
context most needs auditing, and exactly the case that is currently broken.

## Evidence

`/ns/seon.db/debug` on the live default cluster (pid 31570), 2026-08-10,
returns 2,111 bytes whose AI pane is:

```html
<section class="seon-debug-body seon-debug-body-ai" id="debug-ai-seon.db">
  <pre>No recorded context capture exists for this agent.</pre>
</section>
```

The same for `seon.fn` and `seon.render` — the three core-namespace agents
created that day, none of which has a capture (see
[bootstrap-teaching-failures-strand-every-new-agent](bootstrap-teaching-failures-strand-every-new-agent.md)).

The live projection is not missing, only unrendered on this pane: the HTML
twin at `/ns/seon.db` renders the same walk (141 namespace family entries,
655,937 characters ≈ 163,984 estimated tokens against a 32,768-token prompt
budget — see
[context quality audit 2026-08-10](../../prds/sci-execution-runtime/research/context-quality-audit-2026-08-10.md),
findings 2 and 9). That discrepancy was only measurable through the HTML
page because the AI pane refused to show it.

## Owner

`seon.render.web` owns the debug panes and their feed.

## Acceptance

With no recorded capture, the AI pane renders the agent's CURRENT
prospective walk and labels it as prospective rather than captured, so the
two are never confused. An agent's context is inspectable before its first
turn. One regression covers a freshly created agent with no capture.
