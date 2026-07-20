---
type: issue
status: open
tags: [issue, cljs, pod, health]
severity: friction
---

# Bun's rejection net loses AsyncLocalStorage scope

## Problem

Bun 1.4.0 does not carry AsyncLocalStorage context into the process
`unhandledRejection` listener. Every `seon.error` ambient scope —
`:seon.error.scope/dev-eval?`, `:seon.error.scope/expecting-core-fault?`,
and `:seon.error.scope/configuration` — is absent inside
`seon.client`'s safety-net handler, even for a rejection whose promise
was created inside the scope.

Two consequences:

- A rejection from a DETACHED fiber spawned by a dev/MCP eval (the form
  did not return the fiber's Promise, so `seon.error/dev-eval!`'s
  settlement `.catch` cannot reach it) classifies coarse `:core` —
  live datom 4304, 2026-07-20 18:43:22Z, a `db/pull`-given-a-Promise
  typo. The fault census gains false `:core` rows.
- `seon.error/escalate!` reads the configuration from the same lost
  scope, so in this funnel the `:seon.config/on-core-error` dial ALWAYS
  resolves the `:gate` default — a genuine `:core` fault surfacing only
  through the net cannot `:crash` even when the manifest says so. The
  escalation policy is funnel-dependent.

## Evidence

Live probe, default pod (Bun 1.4.0), 2026-07-20: inside a dev-eval
bracket, `(seon.error/in-dev-eval?)` is true synchronously, inside the
spawned `^:async` fiber, after its `await`, and in a `.then`
continuation — but false inside a `prependOnceListener
"unhandledRejection"` callback for a rejection created in the same
bracket.

## Expected owner

`seon.error` owns the scope mechanism; `seon.client`'s
`install-process-safety-net!` is the one net. A fix must not resurrect
process-global counters (concurrent agents must not inherit each
other's scope). Candidate directions: verify against a newer Bun
(Node's own listener runs in the promise's creation context), or carry
the configuration to the net at install/refresh time instead of through
the fiber scope.

## Acceptance

- A detached dev-eval fiber rejection records `:agent` (or the census
  documents the residual precisely).
- With `:seon.config/on-core-error :crash`, a genuine `:core` fault
  surfacing only through the process net exits the pod after persisting;
  with `:gate`/`:log` it does not — regardless of funnel.
