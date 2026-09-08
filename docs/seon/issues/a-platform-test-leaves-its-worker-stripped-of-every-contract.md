---
type: issue
status: open
severity: cleanup
tags: [issue, test, runtime, wave/contract-gate]
---

# A platform test leaves its worker stripped of every contract

Found 2026-09-08 by `test-harness`, on the FIRST `bin/test --platform` run
after the runner learned to measure what a task leaves behind
([landing note](../../prds/context-generation/research/test-harness-landing-2026-09-08.md) §2.2).
The gate printed:

```text
bin/test: WORKER-GLOBAL STATE CHANGED by
  seon.cluster.cohost-boot-test/a-second-cluster-boots-under-the-first-cluster-s-instrumentation
  worker= pool-1
  {:snapshot-instrumented {:drift-removed-count 918}
   :snapshot-registered   {:drift-added-count 8}}
```

`test/seon/cluster/cohost_boot_test.clj:161` ends its `finally` with a bare
`(instrument/remove!)`. That is total by design, so the worker leaves this
task with **918 wrappers gone** and every LATER task in `pool-1` running
unarmed — asserting this test's timing rather than its own subject. It is
exactly the class
[an-armed-contract-test-is-unarmed-by-another-test-in-the-same-worker](an-armed-contract-test-is-unarmed-by-another-test-in-the-same-worker.md)
named, now with a named culprit instead of a victim.

**Why this is `cleanup` and not a blocker.** `seon.test.runner/reassert-contracts!`
already derives the worker's armed state before admitting each task and
re-arms when wrappers are missing, so the gate is correct today; the cost is
one whole re-arm per run and a drift line in every tally.

## Fix

The pattern `seon.sci.eval-test` already uses — capture the entering roots and
restore them, so the mutation is bounded by the test that proves it:

```clojure
(deftest a-second-cluster-boots-under-the-first-cluster-s-instrumentation
  (let [root (published-root)
        instances (atom [])
        ;; the worker's ENTERING wrappers, restored below: a pooled worker
        ;; runs many tests per JVM and `instrument/remove!` is total
        entering-roots (into {} (map (juxt identity deref))
                            (instrument/instrumented))]
    ...
      (finally
        (instrument/remove!)
        (doseq [[instrumented-var root] entering-roots]
          (alter-var-root instrumented-var (constantly root)))
        (doseq [instance @instances]
          (try (cluster/stop! instance) (catch Throwable _ nil)))))))
```

Not applied here: `test/seon/cluster/*` was held by the `turn-loop` lane.

## Acceptance criteria

- `bin/test --platform` reports no `Tasks that changed worker-global state`
  section, and no `RE-ARMING CONTRACTS` line appears in any worker's stderr.
