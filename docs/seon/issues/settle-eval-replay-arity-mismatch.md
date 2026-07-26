---
type: issue
status: open
severity: bug
tags: [issue, runtime, agent]
---

# settle-eval crash-recovery replay calls run-eval-batch! with the wrong arity

Found by the fresh-eyes runtime review (2026-07-24), see
`docs/prds/sci-execution-runtime/research/wtf-review-2026-07-24.md`.

## Problem

`seon.agent.driver.host/settle-eval-step!`
(`src/seon/agent/driver/host.clj`, ~line 700) handles the
claimant-died-after-`:evaling`-before-first-receipt case by replaying the
batch:

```clojure
(run-eval-batch! host storage-view run claim-epoch database)

```

But `run-eval-batch!` is defined as

```clojure
[host run claim-epoch database program invocation-configuration execution-plan]

```

Five arguments against a seven-argument signature, with `storage-view` passed
in the `run` position. The replay arm — the exact recovery path the durable
phase cursor exists for — would throw `ArityException` at runtime. The replay
also needs the reply re-parsed (`reply-program`) and the invocation
configuration/execution plan re-derived; none of that is available at the call
site today.

## Owner

`seon.agent.driver.host` (JVM claimant leaf).

## Acceptance

- `settle-eval-step!` replays an empty-receipt `:evaling` turn through the
  same parse/plan/configure path `eval-step!` uses (or the two arms share one
  function), compiling and running cleanly.
- A test kills/simulates a claimant after the `:reply-ready→:evaling`
  transition with zero committed receipts and proves a second claimant
  replays the batch exactly and settles the turn.
