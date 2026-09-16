---
type: issue
status: superseded
severity: blocker
tags: [test, database, wave/test-fixture]
---

# Canonical fixture branch acquisition waits behind a held roster permit

The root cause and dependency follow-up are owned by
[an-interrupted-fixture-leaks-datahikes-roster-permit-and-wedges-the-jvm.md](an-interrupted-fixture-leaks-datahikes-roster-permit-and-wedges-the-jvm.md).
`d2a0ad636` removes interruption from `seon.test/run` and `check` expiry;
the canonical expiry regression retains this observation as its acceptance case.

Observed 2026-09-16 after the owner's reset, default PID 27828.
`seon.test/run` of
`seon.test-failure-facts-test/explicit-namespace-completion-commits-with-membership-unknown`
returned its named 100000 ms timeout with zero assertions. Before the run,
canonical base preparation completed in 27526 ms and confirmed both new schemas.

The JVM thread dump contained waits at
`datahike.gc-guard/acquire-reachability-permit!` →
`datahike.versioning/branch!` → `seon.test-support/with-branched-database`.
The fixture's memory store was `348b77ab-4a40-4b20-9457-9b966b4b4df9`;
its gate held roster token 174, had no active sweep or blobs, and had 12
waiting requests. A holder's origin is not established. Other in-process
tests were also waiting; no foreign session or permit was modified.

The recurring proof should bound fixture acquisition and demonstrate permit
release or cancellation through interrupted acquisition. This lane's fixture
failure is not a verdict on the result-recording regression. Its isolated
gate remains requested; direct default recording is a separate proof.
