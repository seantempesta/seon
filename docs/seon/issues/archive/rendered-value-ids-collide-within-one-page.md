---
type: issue
status: resolved
severity: blocker
tags: [issue, render, web, datastar, identity, live-drive]
---

# Make every rendered value id unique within its namespace page

## Problem

One namespace page contained hundreds of duplicate HTML `id` attributes.
`seon.render.value/node-id` hashed agent id, a root address, and `get-in` path,
but anonymous rendered units shared the invented
`:seon.render.value/anonymous` address. Datastar morph targeting and fragment
navigation therefore could not identify one DOM node.

## Original live evidence — default cluster, 2026-08-06

At one settled database basis, `/`, `/agent/root`, and
`/ns/my.agents.root` each returned 520,416 bytes with 643 `id` attributes and
185 duplicated values. The shared SHA-256 was
`62692756ff4d841bb28798b7e7fe7e7996216d65d3b91d429e084d568334cfb1`.
The agent debug page contained 644 id attributes and the same 185 duplicated
values.

## Resolution

Resolved in this commit. `seon.render.value/node-id` no longer invents an
anonymous root. It prefers the caller's `:seon.render.call/id`, retains the
existing explicit value/database/block identities, and returns the flat
`:seon.render.value/missing-root-identity` refusal when none exists.
`seon.render/render-argument` now carries the walk's block id into the selected
floor producer; this is the only production caller exposed by deleting the
fallback.

The class regressions prove that two anonymous roots both refuse, equal values
in different supplied blocks get distinct ids, and the same block/path keeps
the same id. `seon.render.value-test` passed 14 tests and 44 assertions;
`seon.render-simplification-test` passed 10 tests and 47 assertions; the
complete `seon.render.web-test` portion passed all of its tests in the combined
render gate.
