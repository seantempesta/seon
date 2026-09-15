---
type: issue
status: open
severity: friction
tags: [issue, mcp, render, wave/dev-mcp]
created: 2026-09-15
---

# MCP SCI error projection passes a nil database

## Evidence

Run6-blockers, default PID 23729, after development adoption on 2026-09-15.
MCP SCI evaluation of
`(seon.db/q '[:find (pull ?e [*]) :where [?e :example/order _]] [])`
correctly returned an error outcome and `seon.db/q violated its contract
(invalid-guard)` with the complete repair message. Its `:seon.dev.mcp/text`
instead contained:

```text
#object[clojure.lang.ExceptionInfo "projection failed: seon.sci.kernel/invoke violated its contract (invalid-input): must be an immutable Datahike database value; argument 0 (0-based); schema path [0 :seon.db/db 0]; expected :fn, got nil at [[:seon.db/db]]"]
```

The evaluation itself took 8 ms and was correctly classified `error`.
This report does not attribute the nil to an unprobed caller. The observable
boundary is the development MCP's SCI error projection. It is separate from
the sorted-map-key failure in `mcp-projection-crashes-on-non-keyword-map-keys.md`.

## Acceptance

Carry the selected database through the error-rendering request. The same
SCI form must display the flat contract refusal, including its documentation,
without a projection exception or `#object` text. The run6 lane verifies its
ordinary turn path separately and does not modify the MCP owner.
