---
type: issue
status: open
severity: medium
tags: [issue, testing, boot, runtime]
---

# `operator-root-history-policy-is-creation-fixed` fails intermittently under machine load, and looks exactly like a regression

## Problem

`seon.cluster.boot-test/operator-root-history-policy-is-creation-fixed`
(`test/seon/cluster/boot_test.clj:734-775`) intermittently reports three
failures — all three `is` forms inside `testing "a sibling cannot request a
different held representation"` seeing `nil` where the refusal's offense data
belongs. It then passes repeatedly with no source change.

It cost a repair lane about half an hour on 2026-08-07: three consecutive
failures made a genuinely unrelated one-function fix look causal, and a
revert-and-rerun "confirmed" the false attribution before further repetition
refuted it.

## Evidence (2026-08-07, one afternoon, one machine)

Same working tree, `src/seon/cluster.clj` at commit `cf227ff73` throughout
except where noted:

| Run | Load | Result |
|---|---|---|
| whole namespace | 5× cohost regression loop finishing concurrently | 3 failures |
| whole namespace | same | 3 failures |
| single var, `test-vars` | same | 3 failures |
| single var, with the fix reverted | quiet | pass |
| single var, `start-refusal` wrapped to capture the failure | quiet | pass, chain correct |
| single var, plain, ×3 | quiet | pass, pass, pass |
| whole namespace, ×2 | quiet | pass, pass |

When the failure is instrumented it disappears; when the chain IS captured it
is exactly what the test asserts:

```clojure
[#:seon.boot{:cluster-name "history-on-conflict"}
 {:seon.boot/rule :seon.cluster/keep-history-mismatch
  :seon.config.db/keep-history? true
  :seon.store/keep-history? false
  :seon.store/dir "…/store"}]
```

So the refusal path is correct and the flake is in whether that chain is
produced at all on a loaded machine.

## Where to look

`acquire-root-store!` (`src/seon/cluster.clj:620-653`) refuses only when the
process-wide `root-store-holder` already holds an entry for the store key. The
refusal is therefore contingent on the FIRST cluster's holder still being
registered when the sibling starts — a process-global registry read whose
timing relative to the first `start!`'s completion is what the test is
implicitly assuming. Under load that assumption is what appears to slip.

The audit's own warning applies directly: "a schema assertion that fails once
in three runs, at a different place each time, would be triaged as flakiness.
The flake IS the race"
([parallel isolation audit](../../prds/sci-execution-runtime/research/parallel-isolation-audit-2026-08-07.md)).
Treat this as a race to characterize, not a test to retry.

## Acceptance criteria

- The mechanism is named: what state the sibling `start!` reads, and what
  makes it read differently under load. A probe with repetition, reporting a
  FAILURE RATE rather than a boolean.
- Either the race is fixed at the owner, or the test states and establishes
  the precondition it depends on rather than assuming it.
- 20 consecutive runs under deliberate concurrent load, green.
