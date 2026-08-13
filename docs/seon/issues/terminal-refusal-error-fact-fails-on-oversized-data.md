---
type: issue
status: open
severity: friction
tags: [issue, runtime, schema, wave/settlement]
---

# Oversized terminal-refusal data breaks the durable error fact

Found by the hot-ctx lane (2026-08-01, during 1A verification) as an
independent pre-existing defect, reported for orchestrator filing.

Oversized terminal-refusal data is admitted into a truncated structured
message, and `error-tx` then cannot construct a schema-valid error fact
from the truncated form. The recurring test
`terminal-refusal-settlement-is-bounded-checked-and-recoverable`
reports 3 failures and 2 cascading errors.

This is the settlement path's own bounding failing at its second
consumer: the bounded message admits, but the durable fact construction
downstream refuses the same value. One bound must serve both consumers
— the error fact's schema and the admitted message must agree by
construction, not by luck of payload size.

Acceptance: the recurring test green; an oversized refusal settles with
one durable schema-valid error fact whose payload is bounded by the one
general printer; no second literal limit introduced.
