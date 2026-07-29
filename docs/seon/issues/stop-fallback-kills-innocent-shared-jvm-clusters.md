---
type: issue
status: open
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
