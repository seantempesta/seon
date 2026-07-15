---
type: issue
status: resolved
severity: blocker
tags: [issue, flow, pod, architecture]
---

# Unwind managed processes when the operator is interrupted

## Problem

Interrupting an operator reconcile after it starts a managed child can exit the
parent Babashka command without unwinding that child. The process remains
recorded and recoverable by a later `bin/seon down`, but it is reparented to PID
1 and continues running after the transition that owned its start has ended.
Branch create/launch rollback and ordinary restart cannot claim complete
process ownership while this window remains.

## Evidence

During a live `bin/seon restart`, the planned artifact digest became stale when
source changed during watcher startup. The watcher compiled both `client` and
`test`; `seon.dev.process/ready?` correctly kept readiness false because
`artifact/current-client-digest` no longer equaled the process spec's planned
digest. Ctrl-C then ended the parent Babashka process with exit `130`, but the
recorded watcher PID `68712` remained alive with PPID `1`. A subsequent
`bin/seon down` resolved the retained record and reaped the watcher cleanly.

`seon.dev.process/ensure!` publishes the detached process record before waiting
for readiness. `seon.dev.cli/-main` catches `Throwable` for ordinary failures
but owns no interruption-scoped ledger or `finally` that drains only processes
started by the current transition. The later successful `down` proves retained
identity is adequate for recovery; it does not prove the interrupted command
closed its own resources.

The full launch audit and failure boundary are in
[[branch-qualified-replica-operator-launch-audit-2026-07-15]].

The internal native-branch transition at `74bfa7e2` now records whether the
target pod was converged before `ensure!`. An injected state-publication failure
after ensure drains a newly started pod through the one exact close owner, but
leaves a pre-existing converged pod/branch running with resumable retained
evidence. This closes the branch-local ownership decision in a bounded fixture;
it does not close the issue because real SIGINT coverage and the ordinary
watcher/writer/pod supervisor transition remain outstanding.

## Owner

The one Babashka supervisor transition in `seon.dev.cli` and
`seon.dev.process`. Reconciliation must retain an invocation-local ordered set
of processes it actually started, distinguish them from converged pre-existing
processes, and unwind that set in reverse dependency order on interruption.
Process records remain the cross-invocation recovery authority; do not add a
second supervisor or PID registry.

## Acceptance

- Inject SIGINT after each watcher, writer, and pod start/readiness boundary;
  the operator drains only processes newly started by that transition in
  reverse dependency order.
- A process that was already converged before the interrupted command remains
  alive and owned.
- No newly started recorded process remains alive under PPID 1 after the
  command exits.
- Cleanup failure retains the exact process record and reports a nonzero error;
  a later `bin/seon down` can retry it without guessing identity.
- Artifact-source drift continues to fail readiness closed and is never
  reclassified as a successful start.
- Branch create followed by an interrupted pod launch applies the same process
  unwind before exact target attachment release/delete; uncertain process
  cleanup prevents destructive branch deletion.

## Resolution

Resolved on 2026-07-15 by the one `seon.dev.process` startup transition. The
ordinary reconcile installs one invocation-scoped JVM shutdown hook before any
build or spawn. A synchronized phase makes ownership publication and the real
detached spawn indivisible with respect to shutdown: the hook either closes
admission before spawn, or waits until the managed record exists and drains it.
Only specs that were not already converged enter the ordered owned set.

Shutdown cleanup uses direct synchronous `ProcessBuilder` calls for process-
group probes and signals. This is required because Babashka terminates the
future executor used by `babashka.process/sh` before JVM shutdown hooks run; a
real pre-spawn SIGINT probe first exposed that failure and retained the exact
record rather than hiding it. Cleanup failures still retain their process
records, are emitted by the hook, and become the reported transition failure on
ordinary exception paths.

Real OS-SIGINT tests cover the pre-spawn/post-admission race plus watcher,
writer, and pod readiness cuts. Every newly started group and record is absent
before owner exit `130`; a converged writer remains alive while the invocation-
owned watcher and pod are drained. The focused branch/CLI/process selector
passes 31 tests/153 assertions. Full evidence and the dependency ledger are in
[[ordinary-startup-sigint-ownership-2026-07-15]].
