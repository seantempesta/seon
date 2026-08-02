---
type: issue
status: resolved
severity: friction
tags: [issue, web, render]
---

# Restore the message bar to the page the agent route serves

## Problem

`/agent/{id}` renders no `<form>` at all. `POST /agent/{id}/message` works
(204, message committed), but there is no way to reach it from the UI: a
human looking at an agent page cannot say anything to that agent. The page
shell's message bar and hidden feed opener described for this renderer are
absent from the walk-based response the route actually serves.

The whole page chrome is one line of run-together links, `agentdebug`, with
no separator, no page title, and no agent identity beyond a raw lookup ref
`[:seon.cluster.agent/id "scout"]` printed as body text.

## Evidence

Observed 2026-07-31, cluster `visual-qa`:

```text
$ rg -o '<form[^>]*>' tmp/visual-qa/agent-scout.html | head
(no matches)
$ curl -X POST .../agent/scout/message --data-urlencode 'content=…'  → 204
```

Screenshots `tmp/visual-qa/agent-scout.png`, `tmp/visual-qa/root.png`,
`tmp/visual-qa/agent-scout-tall.png` — no input anywhere on the page.

## Owner

`seon.render.web` — the page shell used by the agent/namespace routes.

## Acceptance

Every agent page serves the message form and the feed opener, and a human
can send a message to an agent from the page without a terminal.

## Resolution

Resolved by `2c74a2353` (`Drive namespace pages from render walk units`). That
commit put `message-bar-html` back in the one `shell` used by the namespace and
agent page responses while retaining the hidden feed opener outside morph
targets. The current route therefore serves both the `content` form and the
feed bootstrap through the same page boundary.
