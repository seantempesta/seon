---
type: issue
status: open
severity: friction
tags: [issue, runtime, test, wave/contract-gate]
---

# Co-host start can race the reachability sweep

On2026-09-08, page-feed's HEAD-plus-owned-paths platform gate at `22f6163e5`
ran 82 tests / 470 assertions with one error in
`seon.cluster.cohost-boot-test/a-second-cluster-boots-under-the-first-cluster-s-instrumentation`.
After cohost-a started, `seon.cluster/start!` refused the second start:
`A reachability sweep is in progress; retry start later.` The gate's isolated
confirmation passed. This is an observed boot/sweep race, not evidence of a
render failure or a confirmed process-global instrumentation leak.

The page-feed lane owns cluster.clj only for the adoption web-server hunk and
has not modified this boundary. Verify start/sweep admission using the canonical
cohost fixture and preserve the bounded typed refusal. Evidence and rerun results:
[page-feed landing note](../../prds/context-generation/research/page-feed-landing-2026-09-08.md).
