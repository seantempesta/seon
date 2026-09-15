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

Re-observed on 2026-09-15 in the isolated `freshness` cluster during the
adoption-contract-freshness probe. SCI correctly classified a request-contract
refusal as `error`, while `:seon.dev.mcp/text` showed `projection failed:
seon.sci.kernel/invoke refused argument 0 (0-based) at [:seon.db/db]` with nil.
The raw evaluation error remained available and was recorded separately in
[the probe transcript](../../prds/context-generation/research/adoption-contract-freshness-2026-09-15.json).
No MCP implementation changes were made by this lane.

## Re-observed at HEAD 85c992d16 (2026-09-15 18:30Z)

Door mode on `default`, `(dir my.note)` returned the printed text
`#object[clojure.lang.ExceptionInfo "projection failed: seon.sci.kernel/invoke refused argument 0 (0-based) at [:seon.db/db]: expected must be an immutable Datahike database value, got nil. Fix: must be an immutable Datahike database value"]`
— an opaque host object string, not a `:seon.error` value — while `(doc my.message/send)` in the same session succeeded. Two defects in one: the door ctx supplies no database for `dir`'s request, and a projection failure renders as `#object[…]` instead of the error pair. Same class as the triage-A note "a disposable SCI pull returned an opaque projection-error string".
