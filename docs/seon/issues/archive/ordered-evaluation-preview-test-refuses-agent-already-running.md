---
type: issue
status: resolved
severity: friction
created: 2026-09-16
resolved: 2026-09-16
tags: [turn, test, class/p1]
---

# `ordered-evaluation-retains-one-explicit-basis-without-publication` refuses `agent-already-running`

## Verdict

Two causes, one in the fixture and one in the writer. Both are the same
class: a transaction whose report was never read, so the test took a
REFUSAL for BEHAVIOUR.

### 1. The unchecked close (fixture, `test/seon/cluster/evaluate_sources_test.clj`)

The test closed its turns with a bare map:

```clojure
(db/transact! connection [{:seon.turn/id "active-during-add" :seon.turn/closed-tx "datomic.tx"}])
```

`seon.db/transact!`'s write admission validates every identity-keyed map
against its entity schema, and the turn schema requires `:seon.turn/agent`:

```
seon.db/transact! refused transaction data at [0 :seon.turn/agent]:
expected the required key :seon.turn/agent ... got a map missing :seon.turn/agent
```

Probed in-process on live `default` (PID 95853): opening `t1` through
`turn/open-tx`, closing it with the bare map leaves `turn/open-for-agent`
answering `"t1"`; closing it through `turn/close-tx` answers `nil`. The
close was never applied, the agent's turn stayed open, and the recording
correctly refused `:seon.turn/agent-already-running`.

No preview evaluation opens a turn — `turn/evaluate-sources` submits no
run. The refusal was genuine, not a preview-path defect.

Fix: both closes go through `turn/close-tx` and assert `nil` error kind.

### 2. `recorded-content-conflict` on an identical re-record (`src/seon/turn.clj`)

With the closes fixed, the deliberate duplicate `record-evaluated-call`
refused `:seon.turn/recorded-content-conflict`. `stored-record-content`
compared the RESOLVED transaction refs against the request's tempid:

```
stored  :seon.turn/opened-tx {:db/id 536870925}   expected  "datomic.tx"
```

The transaction refs are not content — they enter as the writer's own
`"datomic.tx"` tempid and come back as the transaction entity — so an
identical recording could never be a no-op. Fixed by a new private
`seon.turn/recorded-run` that drops `::opened-tx`/`::closed-tx` from both
sides of the comparison (`src/seon/turn.clj:1472`, used at the `expected`
map and in `stored-record-content`). Reply, sources, evaluations and their
terminal facts are still compared, so the test's deliberate
different-reply conflict still refuses.

### Two stale expectations fixed alongside

- `(= closed-at (:seon.turn/closed-tx saved))` compared an instant with a
  transaction ref; it now asserts the stored model — the closing
  transaction's `:db/txInstant` is at or after `closed-at`.
- The rollback probe `{:my.plan.item/id "must-rollback"}` was itself
  refused by write admission (missing `:my.plan.item/title`), so the
  transaction never reached the writer and the conflict refusal proved
  nothing. It now carries a title.

## In-process result

`(seon.test/run (#'seon.test/resolve-test 'seon.cluster.evaluate-sources-test/ordered-evaluation-retains-one-explicit-basis-without-publication) (seon.operator/connection "default"))`
on adopted commit `6aaa62a7-41e6-5707-b0a8-dfef8347fb56`:
**36 pass, 0 fail, 0 error** (was 24/9 before, 33/2 after cause 1).
Also green in-process: `one-turn-derives-the-render-profile-exactly-once`
(3/0/0), `seon.cluster.prompt-test/prompt-prices-the-exact-retained-history`
(9/0/0), `later-evaluations-preserve-the-opening-history` (4/0/0),
`basis-only-transactions-do-not-append-history` (2/0/0).

## Noted in passing, not fixed here

- `(is (= 1 @writes))` redefines `seon.db/transact!` with `with-redefs`,
  a JVM-global root binding: run in-process in the shared `default` JVM it
  counted three foreign writes from other clusters (`4`, not `1`). Correct
  under the gate's own worker JVM; a fixture-isolation smell under AGENTS.md
  §5 "own nothing global".
- `(seon.fn/tests-reaching db "seon.turn/record-evaluated-call")` throws a
  contract violation: `seon.fn/gate-set refused return value at [0]:
  expected a string, got a lookup-ref vector`.
