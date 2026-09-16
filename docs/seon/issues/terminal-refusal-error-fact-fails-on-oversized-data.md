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

## 2026-09-17 — measured again on `default`; the size is NOT what refused

The `:seon.turn.loop/terminal-refusal-settlement-refused` fault observed on
the freshly reset `default` (pid 30138, 2026-09-16T15:33:01Z) carries inline
`:seon.error/data-edn` of `{:seon.sci.admit/reason :over-bound
:seon.sci.admit/bytes 7810}` with `:seon.error/data-size 35090`. Recovering
the blob (`seon.blob/get`, digest
`78006f5a7e683c35a53483469b5272c25b9015e9d62c8c4505dbe164d8b1f72e`, 35090
bytes) shows the marker is `seon.error/bounded-admission`
(`src/seon/error.clj:390-407`) working as declared — inline evidence capped,
full evidence in the blob tier — and NOT the cause.

The settlement refused because the turn it settled no longer existed:
`:seon.turn/rule :seon.turn/no-such-run` on `seon.turn/close-call` for turn
`3f81dfc4c014`, and the refusal arm re-issued the byte-identical close that
had just refused. Root cause and fix (writer-decided run-dependent
settlement, `seon.turn/open-run-tx-call`) in
[terminal-refusal-settlement-2026-09-17.md](../../prds/steward-platform/research/terminal-refusal-settlement-2026-09-17.md).

The original acceptance here — one bound serving both consumers so an
oversized refusal settles with one schema-valid durable fact — is not
reproduced by this incident and remains open on its own recurring test,
`terminal-refusal-settlement-is-bounded-checked-and-recoverable`.
