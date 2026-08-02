---
type: issue
status: resolved
severity: friction
tags: [issue, flow]
---

# The agent graph's `:io` executor is a platform-thread pool

Found by the JVM tuning research (2026-08-01,
`research/jvm-tuning-2026-08-01.md`): agent graph `:io` execution uses
a cached platform-thread pool while SSE feeds correctly use virtual
threads. The architecture says the process root owns one `:io`
executor on VIRTUAL threads (blocking transport parks free; the
per-proc platform thread is the one scaling cliff the `:mixed` default
warns about).

Acceptance: the `:io` executor is virtual threads at its one
construction site; a live probe from inside an `:io` proc shows
`Thread/isVirtual` true; SSE stays virtual; `:compute` stays a bounded
platform pool; a regression pins the executor kinds so drift is loud.

## Resolution

Resolved by `4ac039c7b` (`Use core.async virtual threads for flow io`). The
cluster now takes the dependency-owned `:io` executor from
`clojure.core.async.impl.dispatch/executor-for`; the landed boot regression
observes virtual execution inside an `:io` proc and bounded platform execution
inside a `:compute` proc.
