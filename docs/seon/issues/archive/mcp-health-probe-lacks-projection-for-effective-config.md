---
type: issue
status: resolved
severity: friction
tags: [issue, mcp, config, observability]
---

# The MCP runtime health probe fails config reads for want of a projection

`mcp__seon__runtime_status` reports every alive cluster with a
`seon.config/missing-projection` error ("Effective config requires the
projection handed to this operation") in its `seon.dev.mcp/runtime`
section — observed 2026-08-14 against the healthy `default` cluster
(pid 99029; eval through the same MCP worked fine). The health probe
calls `seon.config/effective` without establishing a schema projection
(`seon.schema/projection-from-database` + `call-with-projection` is the
working pattern, cf. `src/seon/config.clj:453`). Effect: the status
surface reports an error about ITSELF on every healthy cluster —
noise that trains readers to ignore the runtime section, the
absence-of-signal failure class in reverse.

## Resolution — 2026-09-03

`seon.cluster/mcp-runtime-observation` now hands the instance's
`:seon.sci.eval/projection-state` around `oversight/cluster-flow-status`
(the `mcp-effective` pattern); regression
`live-runtime-observation-hands-its-projection-to-flow-health` in
`test/seon/cluster/mcp_test.clj`. Diagnosed by lane `mcp-status-2/-3`
(isolated live proof: valid observations in 21 ms), landed by the
orchestrator; `bin/test seon.cluster.mcp-test` 12 tests / 56 assertions /
0 failures. Live `runtime_status` re-verification is owed at the next
cluster restart (the running `ctxprobe` JVM serves pre-fix code).
