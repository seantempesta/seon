---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [issue, wave/dev-mcp]
---

# The supported MCP runtime tools were absent from a bounded lane

The publication-report projection lane had no `mcp__seon__runtime_status` or
`mcp__seon__eval_clj` tools available. `bin/seon status` reported the default
descriptor as a stale advertisement and `0/1 clusters alive`, but repository
instructions prohibit replacing the supported tool with a hand-rolled prepl
sender or operating another owner's default process.

The lane therefore verified the database behavior with the canonical fixture
and armed fast harness, and recorded the missing live JVM proof in
`docs/prds/steward-platform/research/publication-report-projection-2026-09-17.md`.
The done condition is that a bounded lane can call both supported tools and
receive complete runtime-status and JVM-evaluation envelopes without taking
ownership of the default cluster.
