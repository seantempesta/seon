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

## Diagnosis — 2026-09-19

The readiness error is a secondary effect of fixture cleanup racing its
still-running boot, not evidence that recovery refused. The last phase
reports **completed** recovery (`src/seon/cluster.clj:250`, `:3484–3487`).
The fixture wraps publication, fork and start in one 300,000 ms lifecycle
hold without progress events (`test/seon/dev/fresh_operator_reset_test.clj:346`).
On expiration, the lifecycle waiter throws while its holder keeps running
(`src/seon/operator/state.clj:534`, `:553`). The fixture's `finally` calls
private `down!` directly, outside lifecycle serialization (`:421`).
In the original log, cleanup's PROCESS RECORD CENSUS already appears before
the readiness-closed assertion.

Verified on an isolated `0aee224c5` checkout: ordinary boot reaches READY
with zero recovery operations. A controlled probe then invoked the same
private `down!` immediately after real recovery completion. At
18:34:58.914Z it observed recovery; cleanup sent SIGTERM to child 94550;
at 18:34:59.921Z `start!` returned the identical `readiness-closed`, phase
`recovery`. The child log before and after cleanup was byte-identical and
contained no boot failure. READY never arrived because cleanup terminated
the child. No fault committer exists yet at this phase.

The original child log is gone, so no claim is made to have recovered its
bytes. Two repetitions of the unmodified full sequence encountered the
publication subprocess's separate 180,000 ms deadline before any cluster
child existed; those do not independently reproduce the original timing.

The repair belongs to fixture lifecycle/cleanup ownership. The assigned
lane is explicitly forbidden to edit that held file, and it remains dirty
from its owner's work. This issue remains open; changing recovery code
would not fix the verified race. The owned canonical recovery regression
now also requires a positive zero-work result before its refused-query case.

Detailed evidence and the reproduction script:
[reset-boot-readiness-2026-09-19.md](../../prds/steward-platform/research/reset-boot-readiness-2026-09-19.md).
