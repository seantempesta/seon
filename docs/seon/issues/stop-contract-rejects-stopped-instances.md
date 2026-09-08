---
type: issue
status: open
severity: friction
tags: [issue, runtime, test, class/contract]
---

# The stop contract rejects the stopped instance it promises to accept

Observed 2026-09-08 in the armed `bin/test-fast seon.cluster.boot-test`
run while verifying source adoption. `two-instances-are-isolated` calls
`seon.cluster/stop!` again on the same instance and expects the documented
idempotent nil result. Instrumentation refuses before the function executes:

> must be a live unreleased Datahike connection from the calling cluster

The offending path is `[:seon.cluster.loop/cluster :seon.db/connection]`.
`stop!` declares `:seon.boot/instance`, whose nested live-connection
requirement cannot hold after a successful stop. The function's identity
claim already decides whether the supplied instance still owns resources.
Its input contract must admit an instance whose resources were released.
Do not weaken the live instance output contract to accomplish this.

The same run subsequently logged reads against the deleted fixture store.
That cleanup consequence needs independent verification; the input-contract
refusal above is directly observed. This is outside the hook lane's owned
refresh path. The existing repeated-stop and replacement-instance tests are
the regression surface; no duplicate test is needed.
