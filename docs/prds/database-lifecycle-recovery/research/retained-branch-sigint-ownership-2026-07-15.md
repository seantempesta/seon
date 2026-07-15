---
type: research
status: complete
tags: [research, database, flow, pod]
---

# Retained branch SIGINT ownership

## Exit measure

A real SIGINT before target-pod spawn, through detached spawn publication, or
during target-pod readiness exits the Babashka branch owner with code 130 only
after the pod is absent and a native branch created by that invocation has been
released and deleted at its exact current head. An adopted branch and a
converged pod remain retained and resumable. An uncertain pod inverse forbids
destructive branch cleanup.

## Dependency ledger

| Dependency or mechanism | Selected version or SHA | Source and existing owner | Required behavior |
|---|---|---|---|
| Babashka | `v1.12.212` | `bin/seon`, `seon.dev.process` | One JVM shutdown hook runs on OS SIGINT and may perform synchronous cleanup. |
| `babashka.process` | `v0.6.25`, `16a84e0af0da51b8c84e289970f6b7cc35b35d18` | `reference-code/babashka-process/src/babashka/process.cljc:432-445`; `seon.dev.process/spawn-detached!` | Detached publication remains inside the supervisor's synchronized ownership phase; executor-backed shell helpers are not used from shutdown. |
| Transit CLJ | `1.0.333`, `12f50e4391208d36f910a39dd947cefabf77dc52` | `reference-code/transit-clj`; `seon.db.transport.uds` | Branch create, ensure, release, and delete preserve exact UUID coordinates over the existing typed UDS protocol. |
| Datahike | `417649383c65e13f15ea41d394fb1ed742477965` | `reference-code/datahike`; JVM writer native branch owner | The Babashka transition never opens Datahike directly; release/delete stay fenced through the writer's retained source route. |
| Konserve | `df6818d43ea3363a808cd051c0d68917f1b987a9` | `reference-code/konserve`; shared database path behind the writer | A branch reuses the source physical database and owns no copied directory or second connection authority. |
| Retained lifecycle data | current branch | `seon.dev.branch`, `seon.dev.state/write-edn!` | Exact create intent precedes mutation; created/adopted evidence and the launch descriptor remain the retry authority. |
| Process inverse | `fbb8c399` | [[ordinary-startup-sigint-ownership-2026-07-15]]; `seon.dev.process/with-startup-ownership` | Shutdown closes acquisition admission, waits through publication, and unwinds only invocation-owned resources in reverse order. |

## Failure cuts and ownership decision

The shortest design probe was the existing real detached-spawn SIGINT fixture.
It established that branch cleanup cannot add another signal handler: process
absence and native deletion must be entries in the same monitor-protected
ownership stack.

`with-startup-ownership` therefore retains ordered `{id, release!}` entries.
Its existing two-argument acquisition still records a process whose inverse is
`stop!`; the same synchronized acquisition accepts an explicit inverse for a
retained resource. Acquisition and inverse registration share the monitor, so
shutdown either closes admission first or waits until the mutation has exact
cleanup evidence. One promise makes concurrent hook/exception unwind attempts
observe the same result rather than running the inverse twice.

Branch open registers its native inverse before calling the writer. A successful
`created?` response supplies the provisional exact record before post-mutation
file publication; an `adopted?` response never becomes invocation ownership.
After composing the target process spec, an already-converged pod clears the
provisional claim. The pod spawn is then registered after the branch, producing
the required reverse order:

1. stop the invocation-owned target pod through `process/stop!`;
2. prove its exact record and unmanaged listener are absent;
3. ensure the target attachment at its fresh current head; and
4. release and delete through the existing `close-record!` owner.

If pod cleanup fails, its retained record makes the absence proof fail. The
native branch record remains open and the writer receives no destructive
request. A later explicit close can retry from those exact identities.

## Real signal evidence

The focused fixture runs the real public `branch/open!` in a child Babashka
process. It uses a real detached Python HTTP pod with production readiness, a
real managed process record and process group, and the real Transit UDS client
against a bounded typed writer fixture. The parent sends OS SIGINT and inspects
the retained files, process identity, and writer request order after exit.

| Injected cut | Result before owner exit |
|---|---|
| branch retained, before pod acquisition | no pod record; created branch released/deleted; lifecycle record removed; exit 130 |
| pod acquisition admitted, detached publication delayed | published pod group drained; created branch released/deleted; exit 130 |
| pod record published, readiness delayed | pod group/record absent before exact release/delete; exit 130 |
| ready branch and converged pod from an earlier invocation | original PID remains alive; branch and resumable open record remain; exit 130 |
| pod inverse throws while the real process remains | exact pod record and branch remain; no release/delete request; hook reports incomplete unwind; exit 130 |

The branch namespace gate passes four tests containing 61 assertions with zero
failures or errors at
`tmp/test-changed/changed-operator-1784108459403-ba41653d-4660-4b00-9ed5-0b6f517e391e.log`.
The exact `seon.dev.branch-test`, `seon.dev.cli-test`, and
`seon.dev.process-test` checkpoint passes 34 tests/190 assertions with zero
failures or errors at
`tmp/test-changed/changed-operator-1784108545498-d47bf2ca-4311-4e1d-aaa9-5ef0d4c43289.log`.

## Remaining boundary

This settles the retained branch interruption inverse without adding branch
CLI syntax, status projection, MCP discovery, or touching ACME. The next slice
may expose this one lifecycle through those surfaces without changing its
ownership semantics, then coordinate the first live default-plus-ACME
create/write/restart/close proof.
