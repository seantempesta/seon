---
type: issue
status: resolved
severity: friction
tags: [issue, tooling]
---

# Pod-era writer-port consumers survive outside the MCP server

The MCP fix wave deleted MCP's old writer-port fallback, then found the same
pod-era port-file mechanism in `bin/seon-server-call`, `bin/acme`, and
`src-inspect-ai`.

## Resolution

- `a4a94ba07` replaced `bin/acme` with a strict wrapper over the fresh
  root-scoped operator.
- `048120421` deleted readerless `bin/seon-server-call`.
- `691517def` deleted Inspect's stale writer-port read-back functions and
  their planning readers.
- `0925286cd` removed the corresponding source-reader exemption.
- `b2c6cff75` deleted the last Inspect artifact reader that directly observed
  the retired `out-bench/client/main.js` output.

Post-cut searches find no active consumer of the deleted writer port files or
the frozen Shadow output. Focused Inspect verification passed 46 cluster and
catalog tests after the final reader cut; the adapter closure previously
passed 126 focused tests.

## Acceptance

All three consumers discover endpoints from fresh cluster advertisements or
were deleted as pod-era rot. A stale writer port file and frozen Shadow output
influence no live evaluation or operator path.
