---
type: issue
status: resolved
severity: blocker
tags: [issue, render, test, wave/render-acquisition-performance]
---

# Stop `walk/neighborhood` under `history` wedging cluster teardown

## Problem

After T3 committed as `9eae1664d`, the focused store/cluster/operator gate
still wedges while `incremental-source-refresh-publishes-without-touching-existing-clusters`
stops its cluster. The test worker waits in
`seon.cluster.agent/await-turn-completion!`, while the render proc remains
inside `seon.render.walk/neighborhood` called by `seon.render.walk/history`.
The suite's 300-second liveness backstop fires instead of the cluster reaching
its terminal stop event.

## Evidence

Command at `9eae1664db39bc423feb68426b34420b9851586b`:

```text
bin/test seon.cluster.store-test seon.cluster.boot-test seon.operator-test seon.dev.fresh-operator-test
```

The runner reported its last progress at 2026-08-13T23:20:37Z as the start of
`seon.cluster.boot-test/incremental-source-refresh-publishes-without-touching-existing-clusters`,
then exited 124 after 300 seconds. The retained evidence is:

- `tmp/test-runs/run.m6gOGE/tmp/test-liveness/25157-1786663539069.log`
- `tmp/test-runs/run.m6gOGE/tmp/test-liveness/25193-1786663538686-threads.json`

The worker's main thread is parked through
`await-turn-completion!` → `disarm!` → `cluster/stop!` →
`boot_test.clj:984`. In the same dump, a render virtual thread is executing
`render-call` → `walk/neighborhood` (`walk.clj:590,566,546`) →
`walk/history` (`walk.clj:915,895`). Another turn thread is parked in
`seon.render/acquire-context!` (`render.clj:639`). This is the same observable
wedge captured twice before T3 committed, now reproduced against T3's
committed tree rather than its former uncommitted shared state.

## Owner

The `seon.render.walk/history` → `seon.render.walk/neighborhood` acquisition
and completion boundary, together with the turn terminal event consumed by
`seon.cluster.agent/await-turn-completion!`.

## Acceptance

The focused four-namespace gate completes with a total test tally, including
`incremental-source-refresh-publishes-without-touching-existing-clusters`, and
the test's cluster stop observes its terminal completion without the suite
liveness backstop firing.

## Resolution

Resolved by `e93d995cd`. The render was not spinning in membership traversal:
`render-call` asked `config/effective` to rebuild the declaration projection
from a history wrapper, entering Datahike's temporal-merge slow path once per
member. Config now derives declarations from `seon.db/schema-database`, the
origin-chain owner, and reuses that handed projection while reading the
time-filtered config facts. The bounded history-database regression completed
in 118 ms; the exact formerly wedged boot test completed in 156.980 seconds
and advanced to the next test.
