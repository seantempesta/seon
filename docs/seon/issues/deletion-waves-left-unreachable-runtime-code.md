---
type: issue
status: open
severity: cleanup
tags: [issue, runtime, cleanup]
---

# Delete unreachable runtime code left by the deletion waves

## Problem

The deletion waves removed every production caller of two namespaces and one
client helper. Three additional public functions are now test-only or reserved
for a later owner. Leaving these sites in place makes zero-caller audits
oscillate between false positives and stale historical corrections.

## Evidence

Measured on 2026-07-26 after commit `42a9faf2e` by building the namespace
require graph for 191 namespaces and re-grepping dynamic entry points:

- `src/seon/capability.cljc:1-114` has no namespace require or public-function
  call. Its only production caller was deleted at
  `8dc8623ad^:src/seon/host/context.clj:651,1016`. The keyword at current line
  45 is already `:seon.capability/effect`; the whole inventory mechanism, not
  that spelling, is unreachable.
- `src/seon/runtime/recovery/core.cljc:1-15` has no require or call and retains
  the deleted run-holder vocabulary and API.
- `src/seon/client.cljs:462-466` defines `recovery-result!`; the definition is
  its only occurrence after `901eee2d3` removed the production recovery calls.
- `src/seon/runtime/recovery.cljs:283-408` `recover!` and `:510-541`
  `pending-notices` have test callers only. The namespace's schemas still feed
  context rendering at `src/seon/agent/ctx.cljc:577,643-644` and
  `src/seon/agent/ctx/transcript.cljc:820-822`, so the whole namespace is not a
  deletion target.
- `src/seon/db/program.clj:292-297` `compile-tx-data` has only its tests as
  callers. O15/O16 assign compile-time indexing to JVM build initialization
  pages and make runtime derivation a loud failure, so this function awaits
  that owner-keyed cut rather than an isolated deletion.

Three apparent zero-caller namespaces are real dynamic entries and must stay:
`seon.agent.interaction.render` is named by `config/system.edn:470`,
`seon.demo` is a Shadow preload at `shadow-cljs.edn:84,141,182`, and
`seon.embed.preflight` is resolved at `src/seon/db/server.clj:576`.

## Owner

The O13 pod cut deletes `seon.capability`, stale client recovery behavior, and
the obsolete recovery core. O15/O16 own the program reconciler disposition.
The recovery render migration owns the remaining test-only recovery functions.

## Acceptance

- The two unreachable namespaces and `recovery-result!` are deleted with their
  obsolete tests or consumers.
- O15/O16 either delete `compile-tx-data` or give it a compile-time build caller;
  it never becomes a runtime source-indexing fallback.
- Recovery render migration decides `recover!` and `pending-notices` without
  deleting schemas still consumed by a surviving renderer.
- The require-graph audit reports only the three documented dynamic entries as
  zero-require hypotheses.
