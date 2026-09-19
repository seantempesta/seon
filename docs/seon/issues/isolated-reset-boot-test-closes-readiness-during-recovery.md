---
type: issue
status: open
severity: friction
created: 2026-09-19
tags: [testing, boot, readiness]
---

# Isolated reset boot test closes readiness during recovery

## Problem

`seon.dev.fresh-operator-reset-test/cluster-boot-omits-test-namespaces-and-in-process-run-loads-one`
did not reach its in-process test assertion in an isolated fast run.

## Evidence

HEAD `0aee224c5` plus the platform marker/regression correction, 2026-09-19:
`The cluster JVM closed readiness before READY`, with
`:seon.error/kind :seon.fresh-operator/readiness-closed`, phase `recovery`,
owned child pid 85322. Cleanup then reported
`Operator lifecycle holder was silent for 300000 ms in phase "boot without
test namespaces"`. The test ended at 18:12:36Z with one failure and one
error. The child was stopped by the fixture, and the fast runner exited 1.
Raw evidence was captured in `tmp/platform-drill-isolated.log`.

The isolated snapshot excluded every foreign uncommitted source edit.
This is an observed boundary, not an established cause: the child's recovery
failure still needs diagnosis. The platform correction changes metadata
and one checker regression, not this boot test's body or recovery code.

## Owner

The existing reset boot fixture and cluster recovery/readiness owners.

## Acceptance

The named test boots its isolated root through the real operator, reaches
its in-process test assertion, and completes cleanup within its declared
bound. Retain the child recovery error before removing the fixture root
when reproducing; do not infer the cause from readiness closure alone.
