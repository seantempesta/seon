---
type: issue
status: open (upstream; not forked)
severity: latent
created: 2026-09-24
tags: [issue, upstream-delta, core.async]
---

# core.async.flow drops an error a proc throws while stopping

`clojure.core.async.flow.impl/stop` closes the error and report channels right after sending stop
(`impl.clj:174-183`, v1.10.874-alpha3), so an error thrown by a proc's stop transition lands on a closed channel
(`impl.clj:312-316`); a throwing stop also leaves the proc's status unchanged, so it keeps running.

**Seon is not exposed:** every Seon proc runs through `seon.flow`'s `var-process` wrapper, whose stop catch reports
through `seon.fault/fault!` synchronously (`src/seon/flow.clj:258-260`); flow never sees the throw. Owner ruling
2026-09-24: no fork. A tested fix exists on the local submodule branch `seon/flow-stop-errors` (commit `fb21fc0`,
regression `test-stop-transition-error-delivered`, fork suite 51 tests / 407 assertions green). Revisit only if a
proc bypasses `var-process`, or if deleting `join-graph!` (which exists because stop does not await exit) becomes worth it.
