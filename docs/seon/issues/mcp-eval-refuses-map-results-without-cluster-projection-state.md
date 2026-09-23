---
type: issue
status: open
created: 2026-09-23
severity: defect
tags: [issue, mcp, tooling]
---

# MCP `eval_clj` refuses a map result with "no cluster projection state"

## Observation (lane shrink-web-a, 2026-09-23)

In JVM mode on `default` (pid 81369, archive 428707005), a form whose value was a
plain map (`{:findings [] :ms 1147.0 :lint-forms-on-disk 0}`) returned this in place
of the value:

    :seon.error/layer :seon.dev.mcp/configuration
    :seon.error/operation seon.cluster/mcp-effective
    :seon.error/message "The MCP config read has no cluster projection state."
    :seon.schema/refused-value :seon.error/unknown

The same form wrapped in `pr-str` returned the value, so the evaluation succeeded and
the result projection refused. Earlier map-valued forms in the same session did
project, so the refusal is intermittent. A tool that replaces a successful value
with a configuration error hides the result. The value should project, or the
refusal should name the missing projection state and still carry the raw value.
