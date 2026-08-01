---
type: issue
status: open
severity: blocker
tags: [issue, sci, agent, schema]
---

# Agent-authored Malli contracts do not enforce in the live ctx

Live-probed by the hot-ctx lane (2026-08-01, 1A acceptance work) on the
owner's explicit question. Host vars are instrumented (388 vars,
`:panic`, `:scope #{:input :output}` — instrument.clj:240), but an
agent-authored `defn` with its required complete `:malli/schema`
installs into the live cluster ctx as a plain interpreted fn: calling
it with contract-violating arguments enforces nothing, in either
direction.

This contradicts ruling #31's safety posture — "authentic REPL
including shooting yourself in the foot, but it should be HARD to
shoot yourself in the foot. So functions should check their inputs" —
exactly where agents live. The contract is required at admission
(selective admission refuses a durable defn without one) and then
never consulted at call time.

Fix direction (one mechanism, not a second instrumenter): the same
malli instrumentation family applied at the one place interpreted fns
enter the live ctx — install time (`install-program-row!` /
eval-time definition), wrapping the interpreted fn with the same
`:panic`/`:record` dial and the same one-general-printer bounding host
vars get. Never a per-call validation sprinkled through the loop.

Acceptance: an agent-authored contracted fn called with violating
input through the door returns the same flat
`::contract-violated` error value a host var produces; output
violations likewise; the `:record` dial removes the wrappers; a
recurring test covers agent→agent calls through the live ctx.
