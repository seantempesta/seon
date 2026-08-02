---
type: reference
status: abandoned
tags: [reference, flow, web, history]
---

# Historical reactive UI exploration

> Abandoned design. The atom-backed context, Reagent-like API, JavaScript
> access, and `refresh-all!` model explored here are not current or target Seon.
> Use [[datastar-quick-reference]].

This 2026-01-30 note explored a server-rendered UI before blocks, the
database-derived walk, and the Flow renderer were designed. Its APIs and
implementation recipe were deleted when the database became the durable
authority and in-flight presentation moved to channels.

The surviving lesson is narrower: the browser stays thin, the settled page is
a pure derivation of one database value, and slow clients converge on the
newest complete presentation. Current ownership is
`src/seon/render/{walk,block,web}.clj`; target semantics are in
`docs/seon/architecture/ui.md`.
