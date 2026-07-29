---
type: issue
status: resolved
severity: friction
tags: [issue, tooling]
---

# Stop fallback kills innocent shared-JVM clusters

Observed 2026-07-29 (agent-page-twins lane): stopping a SCRATCH cluster
fell back to SIGTERM (the prepl stop path failed — likely the stale
instrumented-snapshot class again on a JVM booted before later schema
commits), and the SIGTERM took the shared JVM down, briefly killing the
owner's live `default` cluster. The fallback printed its blast-radius
warning as designed and the lane restarted default immediately — but a
scratch-cluster stop should never be able to take the owner's cluster
with it.

## Acceptance

When the target JVM is ALIVE and hosts sibling clusters, the SIGTERM
fallback refuses by default, naming the siblings and offering the
explicit escalation (`stop --force <name>` or equivalent); the
unconditional fallback remains only when the process is unreachable or
hosts no siblings. Consider also: scratch/experiment clusters prefer
their own JVM (the operator already supports one JVM per start when no
anchor is joined — an `--own-jvm` hint or a heuristic for non-default
names would isolate blast radius by construction). One regression: a
staged eval-failure stop on a shared JVM with a live sibling refuses
rather than killing both.

## Resolution

Resolved by `8ab798cb9`. Re-verification found `sigterm!` still
destroyed the advertised `ProcessHandle` after any prepl failure,
despite already deriving every live advertisement on that pid.

The fresh operator now distinguishes the target from its sibling
cluster names. A non-forced fallback refuses before signaling, names
the siblings, and prints the explicit `stop --force <name>`
escalation. The existing one-cluster fallback remains unconditional;
`--force` retains the deliberate shared-process kill.

The regression stages a real prepl exception against a disposable
process with two live advertisements. The operator exits with a
refusal, names the sibling and force command, and the process remains
alive.

Proof:

```text
bin/test seon.dev.fresh-operator-test
Ran 4 tests containing 22 assertions.
0 failures, 0 errors.
```
