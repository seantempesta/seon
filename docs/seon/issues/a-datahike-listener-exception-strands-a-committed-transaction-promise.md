---
type: issue
status: resolved
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
At filing time, the research changed no production code and claimed no fixed
regression.

## Resolution

Resolved on 2026-09-18 by Datahike fork commit `e11845ba` and Seon gitlink
commit `95e2e1983`. The writer now snapshots the connection's listeners,
settles the committed report, then invokes each callback under independent
Throwable containment. A callback failure emits
`:datahike/listener-error` at error level with the listener key and exception;
later listeners still run. The same completion seam is used by `transact!`
and `merge-db!`.

Datahike's focused test task passed **27 tests / 237 assertions / 0
failures** across its configured JVM profiles. Seon's canonical-fixture
regression landed at `d443d295c`; the required fast overlay passed **58 tests
/ 436 assertions / 0 failures / 0 errors**. The dependency commit remains
one commit ahead of `origin/main`; pushing it is explicitly owed to the owner.
