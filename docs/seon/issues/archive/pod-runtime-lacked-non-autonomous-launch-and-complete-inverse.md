---
type: issue
status: resolved
severity: blocker
tags: [issue, database, pod, flow]
---

# Add non-autonomous pod launch and a complete process inverse

## Problem

The pod had one unconditional autonomous cold start and no composed teardown.
A forensic or branch-local reader would write boot facts, recover runs, create
and host agents, start provider/ticker machinery, and log replay failures back
to the selected database. Process shutdown could clear no complete ownership
chain for SSE feeds, hosted agents, the replica, executable wrappers, runtime
admission, or the asynchronous Datahike connection.

## Evidence

Before the correction, `seon.client/start-runtime!` unconditionally invoked
every autonomous owner and successful hot reload always rehosted agents plus
installed the ticker. `web.serve/stop!`, per-agent unhost, replica detach, and
maintained Datahike release existed only as separate primitives. Runtime
admission could remain available with wrappers from the detached database.

The fixing change adds a closed retained launch capability, console-only
non-autonomous replay diagnostics, bulk process-local unhost, serialized
start/stop phases, positive-only lifecycle-tool indexing proof, and one ordered
retryable inverse. Commit `9428aebe` supplies the prerequisite that web stop
closes every Datastar feed and resolves only after server close. Runtime
admission commit `81129753` supplies `detach!`, which reconciles the active
projection to empty before connection release. The combined client lifecycle,
runtime admission, and
instrumentation checkpoint compiles 506 files with zero warnings and passes 38
tests/316 assertions; client lifecycle contributes 17/122. The fixing commit
is recorded in this note's history.

## Owner

`seon.client` owns process launch and composed teardown. `seon.agent.loop` and
`seon.agent.runtime` own hosted process resources. `seon.web.serve`,
`seon.db.replica`, `seon.runtime.admission`, and `seon.db` retain their one
inverse each; the client only orders them.

## Acceptance

- Non-autonomous launch attaches, reconstructs, publishes, and serves reads
  without any autonomous write, recovery, genesis, hosting, provider, brand,
  or ticker effect.
- Replay failures continue to later namespaces and create no database datom.
- Hot reload cannot upgrade a retained non-autonomous capability.
- Teardown awaits web/SSE close, removes all hosted process resources, detaches
  replica and executable projection/admission, then awaits Datahike release.
- Every destructive-step failure retains connection authority and is retryable;
  repeated successful stop is effect-free.
- A missing connection cannot turn a claimed running runtime into successful
  stop, and concurrent start/stop cannot publish a false phase.
