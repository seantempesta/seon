---
type: prd
status: planned
tags: [prd, web, agent, flow]
---

# Root workspace sessions roadmap

## Outcome

Root has a calm system workspace distinct from an ordinary agent page, and
each browser tab owns one durable location so navigation and targeted focus do
not make multiple tabs fight.

## Current state

The one root agent, root route, agent cards, blocks, focus derivation, and gzip
feed exist. Root still inherits too much ordinary-agent presentation, and the
architecture's `:seon.web.session/*` location contract is not fully implemented
or proven across tabs, reload, reconnect, deletion, and message provenance.

The grounded audit [[research/root-workspace-session-source-audit-2026-07-14]]
confirms the session contract is wholly absent: no session schema/namespace,
`sessionStorage` bootstrap, message session ref, turn cause-message ref, or
protected root selector exists. The feed `view-id` is process-local socket
state and must not become the session identity. `/` currently calls the
ordinary root-agent shim/feed and renders the fleet as root's canvas, including
ordinary focus chrome and a recursive root card.

## Dependencies

- `reactive-render-units` graduates the one runtime-observed unit transition;
  this PRD consumes it and does not add a local invalidation path.
- Exact source is present for Datastar RC.7-line signals/morphs, Datastar
  Clojure RC7 redirect scripts, reitit 0.10.1 reverse routing, and the selected
  Datahike fork's identity/ref/history behavior.
- Open issues: [[web-session-navigation-provenance-is-missing]] and
  [[root-page-is-an-ordinary-agent-layout]].

## Ordered work

1. Consume the graduated general render-unit transition, then land pure
   normalized-location and reverse-routing functions with error-value tests.
2. Implement one writer-allocated `{id, user, location}` session and validated
   `sessionStorage` attachment bootstrap; reconcile only changed locations.
3. Key tab-specific feed focus/redirect decisions by the validated session and
   implement missing-session reset through the existing Datastar patch stream.
4. Thread human message -> web session and turn -> exact cause-message; add the
   protected root selector that can move only the originating tab.
5. Move `/` onto a dedicated system layout over the shared units, projections,
   surface materializer, router, and gzip feed; remove the recursive root card.
6. Delete signal-only durable-pin assumptions and ordinary-root page plumbing,
   then prove two tabs, reload, deletion, reconnect, restart, and reset in a
   real browser plus server-side gunzip clients.

## Graduation

- Root is visually and structurally distinct without a second block, route,
  renderer, or feed architecture.
- Two tabs navigate/select independently; reload and reconnect preserve only
  their own valid location.
- Message/session provenance targets the originating tab; a different tab does
  not move.
- Missing/deleted sessions return explicit error or reset behavior, never a
  guessed global selection.
- Focused facts/transitions tests and a real two-tab browser journey pass.
