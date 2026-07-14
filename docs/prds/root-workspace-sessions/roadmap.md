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

## Ordered work

1. Audit current root composition, session schemas/routes, Datastar signals,
   and two-tab transition ownership against exact dependency source.
2. Implement one writer-allocated session identity and one normalized database
   location reconciled by navigation, reload, reconnect, and root selection.
3. Move root onto its dedicated system layout while retaining the same blocks,
   ordinary-agent card/focus derivation, and render-unit engine.
4. Thread originating sessions through human-message and root-targeted focus
   without storing ambient tab state on an agent.
5. Delete duplicate root selection, client-only authority, and root-specific
   transition/feed paths, then prove two concurrent tabs in a real browser.

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
