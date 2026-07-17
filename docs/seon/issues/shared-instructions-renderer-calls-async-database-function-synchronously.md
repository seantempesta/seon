---
type: issue
status: open
severity: blocker
tags: [issue, agent, database, web]
---

# Shared instructions renderer calls an async database function synchronously

## Problem

`my.kb.shared/instructions-block` is a synchronous render function, but it
calls the async agent-facing `my.kb.shared/instructions` function. It also
passes an explicit database argument that `instructions` does not accept.
Adding that arity would only hide the compile warning: the renderer would still
try to treat a Promise as the instruction collection.

## Evidence

- `src/my/kb/shared.cljs:63-82` defines `instructions` as an async zero-arity
  function whose database query returns a Promise to its caller.
- `src/my/kb/shared.cljs:85-110` defines the synchronous
  `instructions-block`, calls `(instructions db)`, and immediately applies
  `empty?` and `map-indexed` to its result.
- The focused `my.kb-test` compilation reports the unsupported one-argument
  call. A cosmetic one-argument async overload cannot make the surrounding
  synchronous collection operations correct.
- `src/seon/client.cljs` still includes `my.kb.shared` in the compiled program,
  so this is current program data rather than unreachable archived source.

## Owner

The established async render acquisition and pure rendering boundary owns this
database read. `my.kb.shared` owns the derived instruction data and formatting;
it must not introduce a synchronous database facade or another render path.

## Acceptance

- Shared instructions are acquired from the same immutable database value as
  the rest of the turn context before pure rendering begins.
- The current render mechanism either invokes one pure formatter with ordinary
  instruction data or replaces this block entirely; the obsolete owner is
  deleted in the same change.
- No Promise reaches a render tree or context string, and no synchronous
  wrapper is added around `seon.db/query`.
- Focused tests prove ordered shared instructions, empty omission, and a real
  async acquisition followed by pure rendering without unsupported arities or
  compile warnings.
