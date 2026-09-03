---
type: issue
status: open
severity: friction
tags: [issue, web, render, wave/context-fixes]
---

# Supply the agent ID to prospective debug history

## Problem

`GET /agent/root/debug` returns an unavailable prospective prompt between
runs because its `seon.render.walk/history` request omits the required
`:seon.cluster.agent/id`.

## Evidence

On 2026-09-03, a freshly initialized and reforked isolated
`instrument-absent` cluster had no active run and both
`seon.context/message-custody` and `seon.fs/delete-recursively!` were confirmed
instrumented. Calling `message-custody` directly with a nil run ID returned
`:seon.context/history`.

Tracing the real debug request recorded
`{:run-id nil :agent-id nil :message-eid 31068}` and the page rendered
`:seon.render.web/prospective-context-unavailable`. The contract refusal's
“should be a string” therefore names the missing agent ID, not the optional
run ID.

`src/seon/render/web.clj:557-561` puts the identity only in
`:seon.render.walk/lookup`. `src/seon/render/walk.clj:772-776` independently
reads `:seon.cluster.agent/id` from the request before calling
`message-custody`; that key is absent. The page response was HTTP 200 but its
AI pane status was `unavailable`.

## Owner

`seon.render.web/prospective-prompt` owns constructing the complete request it
hands to `seon.render.walk/history`. That file is protected by the active
`debug-page` lane.

## Acceptance

A fresh cluster with no active run serves a non-empty prospective prompt at
`GET /agent/root/debug`, hands the requested agent ID to every
`message-custody` call, and writes no `:seon.render.cost/*` facts.
