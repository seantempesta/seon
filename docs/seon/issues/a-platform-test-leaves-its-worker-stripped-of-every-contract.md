---
type: issue
status: open
severity: cleanup
tags: [issue, test, runtime, wave/contract-gate]
---

# Three tests leave their worker stripped of contracts they did not restore

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

A second, smaller one from the same detector on `bin/test --all`:

```text
bin/test: WORKER-GLOBAL STATE CHANGED by
  seon.cluster.agent-test/routing-conservation-waits-for-terminal-evidence
  worker= pool-1  {:snapshot-instrumented {:drift-removed-count 3}}
```

Three wrappers, not 918, but the same shape and the same file family — and
`seon.cluster.agent-test` is where the aliased-Var defect
(`(def real-evaluate sci.eval/evaluate)`) was found during
`instrumented-gate-backlog-2`. Same fix.

A third, and the sharpest, from the same `--all`:

```text
bin/test: WORKER-GLOBAL STATE CHANGED by
  seon.db-test/instrumented-wildcard-pull-keeps-unparsed-database-fields-ordinary
  worker= pool-1  {:snapshot-instrumented {:drift-removed-count 926}}
```

`seon.db-test` was the VICTIM in
[an-armed-contract-test-is-unarmed-by-another-test-in-the-same-worker](an-armed-contract-test-is-unarmed-by-another-test-in-the-same-worker.md)
(`malformed-reads-return-flat-errors` red in the pool, green alone). It is
also a CAUSE — exactly what that note's resolution predicted: "`seon.db-test`
is BOTH a mutator and a victim of the class". **Fixed 2026-09-08** by
`test-harness`, since `test/seon/db_test.clj` was in its owned paths.

## The census

Every `instrument/remove!` outside `seon.instrument-test` (whose subject IS
removal) and `seon.test-runner-test`:

| site | restores the entering roots? |
|---|---|
| `test/seon/context_test.clj:27` | YES — the model to copy: a `use-fixtures` bracket restoring both the roots and the function-schema registry |
| `test/seon/sci/eval_test.clj:197` | yes |
| `test/seon/sci/eval_test.clj:1481` | yes |
| `test/seon/db_test.clj:311` | **now yes** (was the 926) |
| `test/seon/cluster/cohost_boot_test.clj:161` | **no** — the 918 |
| `test/seon/cluster/agent_test.clj` | **no** — the 3 |
| `test/seon/sci/eval_test.clj:1050` | **no** |
| `test/seon/sci/eval_instrumentation_test.clj:69` | **no** |
| `test/seon/dev/source_instrumentation_test.clj:54` | n/a — a `with-redefs` stub, which restores itself |

`seon.sci.eval-test:1050` is worth a second look on its own: the very next
deftest in that file, `bare-dir-and-program-derived-doc-are-repl-native`, is
one of the thirteen `parallel-only` verdicts in
[thirteen-sci-eval-reds-appear-only-under-the-whole-gate](thirteen-sci-eval-reds-appear-only-under-the-whole-gate.md).
An unrestored strip inside that namespace is a second candidate cause for
those thirteen, beside the confirmation-world one already named there.

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
