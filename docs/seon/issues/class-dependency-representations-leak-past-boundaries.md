---
type: issue
status: open
severity: friction
tags: [issue, schema, database, class/n13, class-kill, wave/class-kill-queue]
---

# Translate dependency representations once at their boundary

## Problem

Consumers inspect or assume concrete dependency callback, collection, wrapper,
or positional representations instead of receiving a Seon contract value.
Supported variants then behave differently or fail far from the boundary.

## Evidence

Current open members carry `class/n13` and are derived with
`bin/issues-index --class class/n13`.

## Owner

Each dependency integration boundary, using the dependency's maintained
protocols and value vocabulary.

## Acceptance

- Concrete dependency values are translated once into a declared ordinary
  value at the boundary; downstream consumers cannot inspect host layout.
- Properties cover every supported wrapper/callback/collection variant from
  the pinned dependency source.
- Unsupported variants refuse at the boundary with the dependency operation
  and received representation, never by a downstream cast or missing key.
