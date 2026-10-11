---
type: issue
status: open
severity: high
created: 2026-09-26
tags: [issue, adoption, flow, lifecycle, reload, unobserved-exit]
---

# Adopting a commit that deletes a Flow graph leaves the retired graph running and unowned

## Observed (default pid 94554, booted 2026-09-26T00:40:37Z, read-only probes)

Default booted 34 s before `5d8e803f7` (00:41:11Z). That commit deletes the fault channel, its committer graph, the
counted dropping buffer and the error fan-out. The running instance still held all of them:

- `:seon.flow/error-fanout` holds keys `:seon.flow/fault-graph`, `:fault-channel`, `:report-mult`, `:error-mult`,
  `:executor` and others. Its executor was not shut down.
- `(clojure.core.async.flow/ping fault-graph)` answered `{:seon.flow/fault-committer {::flow/status :running}}`.
- The cluster routing atom carries `:seon.agent/fault-channel` (open, 0 queued), a key no HEAD source writes.
- `(ns-interns 'seon.flow)` still listed `fault-committer-step`, `start-error-fanout!`, `stop-error-fanout!` and
  others. This is consistent with default not having adopted `5d8e803f7`.

## The defect (hypothesis to verify at the first adoption across such a commit)

The instance value (`running-instances`) is built once by `boot/start!`. Adoption reloads changed namespaces with
`require :reload`, which neither unmaps deleted Vars nor rebuilds boot-built live objects. Adopting a commit that
deletes a graph's code would therefore leave:
- the graph running, executing retired Vars;
- its executor alive;
- its channels referenced from the instance and routing;
- no current code path that stops it. `stop-flows!`/`boot/stop!` at the new commit no longer name `:seon.flow/error-fanout`.

This is an unobserved exit under a program that claims the graph does not exist. It belongs to the same family as
[a-hot-adopted-handle-shape-change-wedges-the-live-turn-proc-silently](a-hot-adopted-handle-shape-change-wedges-the-live-turn-proc-silently.md)
(boot-built state predating an added key), [development-adoption-retains-old-web-service-inputs](development-adoption-retains-old-web-service-inputs.md)
and [cluster-handle-retains-old-environment-projection-after-adoption](cluster-handle-retains-old-environment-projection-after-adoption.md).
Those record boot-built values missing what new code needs. This one records boot-built live objects that new code
no longer owns.

## Verify

1. On a scratch root, boot at `5d8e803f7^`.
2. Adopt `5d8e803f7` with `bin/seon --root … init --dev <cluster> --changed <paths>`.
3. Ping the instance's `:seon.flow/fault-graph` and check its executor.
4. Run `boot/stop!` and check whether the executor terminates.

## Owner and acceptance

The owner is development adoption (`seon.cluster` reload path) together with the process-root roster
(`resources/seon/operator/runtime.clj`), under the adopted state architecture (39a026318: one roster per scope).

Acceptance: adoption compares the live roster against what the adopted program declares. A live object whose
constructor the adopted program deleted is either stopped and joined by the adoption, or the adoption refuses with
RESET NEEDED, naming it. It is never left running unowned. One regression covers the scratch scenario above.

Found by `docs/research/agent-platform/system-state-opinion-2026-09-26.md` §1, §2 and §9.
