---
type: issue
status: open
severity: blocker
tags: [issue, mcp, runtime]
---

# Default component probe timed out after development adoption

Observed by record-render on 2026-09-08. MCP JVM mode on default accepted
`(+ 1 1)`, but a later component probe timed out after 15000 ms. It called
`seon.operator/connection`, transacted agent `record-render-components`,
then called `my.plan/add!`, `current`, and `steps`. The requested step id was
`record-render-components/one`. Completion of those writes is unknown;
do not repeat them as if a timeout proved absence.

The immediately preceding adoption reached JVM instrumentation and then
refused because source changed during adoption. That sequence is evidence,
not proof of the timeout's cause. Concurrent isolated test confirmation
workers were also running. No fallback transport was used.

See the [landing evidence](../../prds/context-generation/research/record-render-landing-2026-09-08.md).

Turn-cut observed the same boundary at 20:52 UTC on 2026-09-08:
`runtime_status` with no arguments selected default, pid 36758, but returned
runtime health and flow as `unknown` with `Read timed out`. The publication
log independently said `Publication did not finish within its declared bound.`
Earlier ordinary virtual turns had completed, including a contracted function
installed by one agent and called by another. Those completed observations do
not establish health after this timeout. No fallback transport was used.
