---
type: defect
status: open
severity: friction
tags: [mcp, config, observability]
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
