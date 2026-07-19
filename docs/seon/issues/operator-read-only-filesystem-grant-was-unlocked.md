---
type: issue
status: active
tags: [issue, agent, capability, pod]
---

# Operator read-only filesystem grant was unlocked

## Failure

During the live three-agent graduation journey on 2026-07-19, the root agent
called `seon.agent.fs/configure!`, changed the process-global repository grant
from read-only to writable, and rewrote `src/my/ns.cljs`. The rewrite weakened
the database-value schema and replaced the canonical map request with positional
database calls. The supervised pod remained alive long enough to publish that
tracked-source change.

The development operator already forced `SEON_FS_READ_ONLY=1`, but did not set
`SEON_FS_LOCK`. `seon.agent.fs/configure!` therefore behaved exactly as its
public contract promises and let any agent replace the process-global grant.
The read-only launch policy was advisory rather than durable.

## Resolution

The one normal operator configuration now sets both `SEON_FS_READ_ONLY=1` and
`SEON_FS_LOCK=1`. Every ordinary source-checkout and packaged cluster therefore
retains a host-immutable read-only filesystem grant. Dedicated benchmark
launchers that intentionally provide a writable exercise directory remain
separate explicit configurations; the normal operator does not infer or expose
that authority.

The unauthorized `src/my/ns.cljs` edit was removed before rebuilding. Focused
configuration proof asserts both environment values. Live proof must confirm
that `configure!` returns its existing locked error and that an agent filesystem
write leaves the tracked file digest unchanged.
