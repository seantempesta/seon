---
type: issue
status: open
severity: friction
tags: [issue, operator, runtime, wave/dev-mcp]
---

# Runtime status refuses an error occurrence count

At 2026-09-16 02:10 UTC, the normal MCP `runtime_status` request for root
`/Users/sean/src/seon`, cluster `default`, discovers advertised PID 7595 but
returns state `unknown` and this instrumented refusal instead of health:

```text
seon.problems/problems refused return value at
[:seon.problems/error-signatures 2 :seon.problems/occurrences]:
expected an integer, got an integer. Fix: Supply an integer at
[:seon.problems/error-signatures 2 :seon.problems/occurrences].
```

The envelope reports `:seon.instrument/contract-violated`, exception class
`clojure.lang.ExceptionInfo`, and `seon.instrument/throwing-report` at
`src/seon/instrument.clj:413`. The actual integer is not included, so this
record does not infer its value or why the contract rejected it.

The problem/status/error owners were under concurrent edits; the bounded
turn-test lane did not change them or restart default. Verify the returned
value and installed contract on a converged publication before attributing
this to an implementation or adoption defect. The sanctioned status surface
must either return its health data or retain an evidence-complete refusal.
See [the lane record](../../prds/context-generation/research/turn-test-reds-2026-09-16.md).

Batch-19 continuation, 2026-09-16 02:37 UTC: default PID 7595 remains alive,
but MCP runtime status again refuses the occurrence count, now at signature
index 4. Direct read-only JVM evaluation succeeds and confirms cluster custody
and loaded test namespaces. The protected problem/error owners are unchanged.
