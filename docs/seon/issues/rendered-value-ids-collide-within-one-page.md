---
type: issue
status: open
severity: blocker
tags: [issue, render, web, datastar, identity, live-drive]
---

# Make every rendered value id unique within its namespace page

## Problem

One namespace page contains hundreds of duplicate HTML `id` attributes.
`seon.render.value/node-id` hashes agent id, a root address, and `get-in` path,
but several rendered units receive the same address. Datastar morph targeting
and fragment navigation therefore cannot identify one DOM node.

Stable identity is not enough when it is not unique in the document.

## Live evidence — default cluster, 2026-08-06

At one settled database basis, all three ordinary aliases returned the same
HTML bytes:

| route | status | bytes | total `id` attrs | duplicated id values |
|---|---:|---:|---:|---:|
| `/` | 200 | 520,416 | 643 | 185 |
| `/agent/root` | 200 | 520,416 | 643 | 185 |
| `/ns/my.agents.root` | 200 | 520,416 | 643 | 185 |

The shared SHA-256 was
`62692756ff4d841bb28798b7e7fe7e7996216d65d3b91d429e084d568334cfb1`.
The agent debug page contained 644 id attributes and the same 185 duplicated
values.

Representative values such as `seon-value-84414bc270622cd498e78b26`
occurred twice in one document. The identity function is
`src/seon/render/value.clj:27-42`; it falls back to
`:seon.render.value/anonymous` when a unit has no stronger root identity.

## Owner

The one `seon.render.value/node-id` address and the units supplied to it. The
repair must preserve stable ids while making the document address include the
owning block/unit identity.

## Acceptance

- `/`, its agent alias, its canonical namespace route, and both debug aliases
  contain zero duplicate `id` values.
- The same block at the same database basis keeps the same id across reloads.
- Two equal values in different blocks receive distinct ids.
- A Datastar patch targets exactly one element in a real page/feed proof.
