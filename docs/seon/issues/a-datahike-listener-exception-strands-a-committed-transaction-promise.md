---
type: issue
status: open
severity: blocker
created: 2026-09-18
tags: [issue, datahike, writer, hang, bounded-execution]
---

# A Datahike listener exception strands a committed transaction promise

## Evidence

At Datahike `73afe78271a289861da236c5ac3457e64349653f`,
`reference-code/datahike/src/datahike/writer.cljc:414-416` calls listeners
before delivering the committed report, without exception containment.
`merge-db!` repeats this ordering at `:440-443`. The processing and commit
loops have already completed this write; their exception handlers cannot
recover an exception in this separate notification go block.

The isolated armed probe at Seon `47f0bc9c10db9d34113cdfe9dc0001f9010f5ed4`
registered a throwing listener, observed its invocation, unregistered it,
then completed another transaction. The first promise remained unrealized;
the connection advanced from `536870912` to `536870914`. One test, four
assertions passed, with the intentional exception escaping at `writer.cljc:415`.
Raw evidence: `tmp/writer-hang-evidence/probe-1.log:14-33`.
The durable reproduction and phase measurements are in
[the writer investigation](../../prds/steward-platform/research/writer-hang-root-cause-2026-09-18.md).

This was previously observed in
[N3's probe evidence](../../prds/sci-execution-runtime/research/n3-plan-2026-07-27.md#12-probe-evidence-index).
The first-party nonthrowing-listener convention contains some instances;
it does not repair the dependency's completion path. The current wake
docstring's “hangs the writer” (`src/seon/cluster/wake.clj:21`) is imprecise:
this probe strands the caller while the writer still makes progress.
This is not established as the cause of the historical population timeout.

## Owner and acceptance

Fix Datahike's existing transaction/merge completion seam. A committed
outcome must be observable independently of listener completion; a callback
fault must remain observable and must not prevent other notifications.
Preserve commit-before-result ordering and distinguish committed success
from a listener failure. A timeout must not falsely report rollback.

Extend `reference-code/datahike/test/datahike/test/writer_error_test.clj`
with throwing and latch-blocked listeners, transaction and merge cases,
and a subsequent successful write. Release the test latch in `finally`;
all waits use the existing bounded completion helper. Recheck Seon's
call-preparation basis fallback and wake delivery under the armed harness.
This research changes no production code and claims no fixed regression.
