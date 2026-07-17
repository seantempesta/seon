---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, database, web]
---

# Shared instructions renderer calls an async database function synchronously

## Resolution

Resolved in the commit that archives this note. `my.kb.shared/instructions`
now has one zero-argument operation that reuses the execution child's pinned
database value and one fully namespaced request-map operation for an explicit
ordinary database value. Both operations run the same pinned query, preserve a
direct database error unchanged, and otherwise return the ordered instruction
texts.

The existing `instructions-block` owner is asynchronous and awaits acquisition
before calling one pure formatter. Empty data omits the block. A database error
is raised into the existing selected-function failure boundary with the
authority's message and error data, so neither a Promise nor an error map can
enter the render tree.

`tmp/test-cljs-20260717-032540-45932.log` compiled the focused `my.kb-test`
namespace and ran 14 tests with 63 assertions, zero failures, zero errors, and
zero core faults. Its one compile warning is the independently owned
zero-argument `seon.config/on-core-error` migration at
`src/seon/error.cljs:496`; this change adds no shared-instructions warning.

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
