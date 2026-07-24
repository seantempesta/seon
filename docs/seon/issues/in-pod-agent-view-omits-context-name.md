---
type: issue
status: open
severity: blocker
tags: [issue, web, flow]
---

# Preserve context identity in the in-pod agent view

## Problem

The in-pod `seon.agent.ctx.driver/render-agent-view!` path can derive a surface
without the required `:seon.agent.ctx/name`. Malli output instrumentation then
rejects the complete agent-view projection, so the Datastar feed replaces the
page with a render-error card.

## Evidence

On the source-frozen default cluster at HEAD `ab0913794`, a server-side SSE
client opened `/agent/bright-candies-relax/feed`. The route returned HTTP 200,
then emitted:

`render error: :malli.core/invalid-output`

The committed core fault at basis transaction `536874863` names
`seon.agent.ctx.driver/render-agent-view!` and the exact output path
`[:seon.render.surface/surfaces 2 :seon.agent.ctx/name]`; the value was nil
where `:keyword` is required. The same fault reproduced earlier for root at
basis transaction `536874770`.

The former `execution child did not become ready` text is absent, so the
in-pod cutover is active. The agent page is still not healthy because its
derived projection violates the existing output schema.

## Owner

`seon.agent.ctx.driver/render-agent-view!` and the one surface derivation it
uses own this correction. Preserve the context block's existing identity while
moving it through the in-pod invocation path; do not weaken instrumentation or
add a fallback renderer.

## Acceptance

- Every surface emitted by `render-agent-view!` has its required
  `:seon.agent.ctx/name`.
- A real `/agent/{id}/feed` emits the ordinary agent view without a render
  error or `SEON-CORE-FAULT`.
- The root page and a fresh non-root agent page both pass the same live feed
  check.
- The obsolete execution-child readiness error remains absent.
