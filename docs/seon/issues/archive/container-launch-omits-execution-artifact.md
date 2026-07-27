---
type: issue
status: superseded
severity: blocker
tags: [issue, architecture, pod, agent]
---

# Supply the execution artifact to the production container launch

## Overnight triage — 2026-07-23

**FOLD-INTO-UNIT — U9 deletion.** The issue's current acceptance publishes and
launches the execution-child artifact that U9 deletes; container acceptance
must be restated against the claim-native runtime in that unit.

## Triage — 2026-07-23

DISSOLVES into the cutover unit “per-agent child + `eval.cljs` self-host
deletion.” Its acceptance requires publishing and launching the execution-child
artifact; that artifact and launch topology are explicitly deleted at spine
step 3, so the future container acceptance must be stated against the P4
runtime instead.

## Problem

The canonical container starts the database server and compiled Bun pod, but
constructs only the default process launch descriptor. That descriptor has no
execution build ID, output path, or digest, so `seon.execution.host/configure!`
correctly rejects it. A production container cannot launch agents.

## Evidence

On 2026-07-18, the Linux arm64 image built successfully with Bun 1.3.14 and the
maintained local Datahike source. The database server became ready after eight
seconds, then the pod exited with `The launch has no complete execution
artifact.` The container supervisor stopped the writer as designed.

Development does not fail because `seon.dev.process/specs` calls
`seon.launch/with-execution-artifact` using the admitted artifact manifest and
passes the resulting encoded launch descriptor to the pod. The production
entrypoint currently does neither.

## Owner

The production artifact publication and `docker/seon-entrypoint` launch
boundary. The container must consume the same immutable execution artifact
identity as the development operator; admission must not gain a fallback.

## Acceptance

- The image publishes an execution build ID, output path, and digest derived
  from the exact packaged bytes.
- The entrypoint supplies one validated launch descriptor containing that
  identity to the pod.
- A clean container reaches HTTP readiness and launches a real execution child.
- Tampering with the packaged execution artifact is rejected before an agent
  invocation runs.
- Stopping either supervised process still stops the other and exits non-zero.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
