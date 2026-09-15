---
type: issue
status: open
severity: friction
tags: [issue, test, turn]
created: 2026-09-15
---

# Virtual-loop fixture submission can meet an already-open turn

The run6-blockers path-isolated gate at HEAD `01ad0cbc04f9adbf92f6ef248a12eba4c110433c`
plus its owned paths ran 135 tests / 1178 assertions. One pooled task,
`seon.loop-proof-test/virtual-loop-end-to-end`, returned six failures and one
error. The gate's fresh worker loaded the same nine namespaces and passed
that task; classification was `parallel-only`, with no detected preceding
process-global state change. The earlier item-1 isolated gate passed this
same namespace with all 78 selected tests.

Classified fields of the first refusal at `test/seon/loop_proof_test.clj:343`:

```clojure
{:seon.error/kind :seon.turn/refused
 :seon.error/message "run transition refused: agent-already-running"
 :seon.turn/rule :seon.turn/agent-already-running}
```

The fixture calls `agent/arm!` just before the three-form `submit` section
at lines 549–556. The explicit virtual submission receives no turn ID;
subsequent assertions inspect the preceding system evaluations and send nil
as `:seon.turn/id` to `render/acquire-context!`. That missing ID accounts for
the secondary contract error. The active competing turn was not captured,
so this note does not attribute its creation to an unobserved mechanism.

The run6 lane does not own the virtual-turn lifecycle. Its query, empty-stop,
and stalled-session regressions passed in that gate. Repeating all nine
namespaces with `SEON_TEST_WORKERS=1` passed 135 tests / 1175 assertions,
zero failures/errors, in a 251-second coordinator/tests phase. The pooled
failure remains open; a single-worker pass does not resolve the race.

Acceptance: establish the fixture's intended turn lifecycle through bounded
events before submitting; repeatedly run it in the normal pooled gate with
the same complete program and armed contracts. Do not accept nil as a turn
ID, add sleeps, or weaken the one-open-turn fence.
