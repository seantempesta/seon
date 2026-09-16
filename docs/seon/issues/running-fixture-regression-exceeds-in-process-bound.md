---
type: issue
status: open
severity: friction
tags: [issue, test, runtime, wave/test-fixture]
---

# Running fixture regression exceeds its in-process execution bound

## Evidence

On default PID 7595, generated-read-identities ran:

```clojure
(seon.test/run
 #'seon.loop-proof-test/running-fixture-settles-its-seeded-wake
 (seon.operator/connection "default"))
```

At 2026-09-16 02:01:26Z, run 55134 recorded 0 passes, 0 failures, 1 error:
the declared `:seon.test/remaining-ms` allowance of 20,000 ms fired. MCP
execution took 21,553 ms. After development publication reloaded namespaces
and re-armed instrumentation, run 55944 at 02:06:54Z repeated the same outcome
in 21,436 ms. Both attempts logged `seon.turn/refused` with cause
`agent-already-running` while executing the fixture.

The timeout does not identify which fixture transition failed to complete;
the log is evidence, not a proven cause. Earlier attempts encountered the
[failed canonical delay](in-process-runner-rethrows-failed-fixture-delay.md),
a distinct pre-body failure. Do not attribute the later timeout to that delay
or to the identity selector repair without a probe.

## Owner and acceptance

The existing context-blocks fixture and its running-fixture loop regression.
Name the missing terminal event and repair its existing lifecycle owner;
do not increase a timeout without establishing where the time went. The exact
in-process test must execute its assertions and record a terminal result.
Gate request: `tmp/orchestrator/gate-requests/generated-read-identities.txt`.
The index remains the orchestrator's scheduling authority.
