---
type: issue
status: resolved
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
hands to `seon.render.walk/history`.

## Acceptance

A fresh cluster with no active run serves a non-empty prospective prompt at
`GET /agent/root/debug`, hands the requested agent ID to every
`message-custody` call, and writes no `:seon.render.cost/*` facts.

## Resolution

Commit `e28de63bc` makes the shared production `walk-request` carry
`:seon.cluster.agent/id`; `prospective-prompt` now hands that complete request
to `seon.render.walk/history` instead of reconstructing a partial debug-only
variant. The route regressions exercise the real HTTP request constructor,
prove a never-captured agent renders a non-empty prospective prompt, observe
the agent/run inputs passed to `message-custody`, and prove the debug read
writes no render-cost facts.

The debug route remains HTTP 200 when only its prospective AI pane is
unavailable. The response is the composite debug document, whose HTML pane and
diagnostic remain useful; the unavailable AI pane is therefore visibly marked
`unavailable`, renders the evidence-complete diagnostic, and renders no
healthy-looking prompt `<pre>`. `test/seon/render/web_test.clj:588-616` guards
that decision.

`bin/test seon.render.web-test` passed 40 tests and 330 assertions with zero
failures and zero errors. A cold cluster forked after a fresh isolated
publication served `GET /agent/root/debug` as HTTP 200 with prompt kind
`prospective` and a non-empty prompt beginning with the root agent's cluster
and configuration pulls.
