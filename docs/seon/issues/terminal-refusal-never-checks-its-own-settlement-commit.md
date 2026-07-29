---
type: issue
status: open
severity: blocker
tags: [issue, agent, runtime, database]
---

# `terminal-refused!` never checks its own settlement commit

## Problem

`seon.cluster.loop/terminal-refused!` is the fix for the refused-terminal
hot loop (archived issue
`refused-terminal-transaction-leaves-a-running-receipt-hot-loop`, commit
`e7d9f14c3`). Its docstring states: "The receipt terminal fact, run close, and
error fact commit together. The event therefore derives no later pass."

Nothing verifies that. The function ends with

```clojure
(store/transact! connection (into terminal recording))
```

inside a `when-let` whose value is `kind`, and returns `(boolean kind)`. The
transaction's OWN outcome is discarded. If that minimal commit is refused, the
function still returns `true`, the caller reports `:error` and proceeds
believing the receipt settled, and the receipt is left RUNNING inside an OPEN,
HELD run — which is exactly the precondition of the hot loop the commit
claims to have eliminated. No durable fact records that the settlement failed.

`refused!` discards its outcome deliberately and correctly (it owns no
receipt; the docstring argues the recursion fence). `terminal-refused!` copied
that shape without the argument, and it is the one caller for which the
argument does not hold.

## Evidence

Live probe against real running receipts on scratch cluster
`seam-reaudit4-20260729` (script `tmp/seam-reaudit5/E2.clj`), replaying
`terminal-refused!`'s exact construction while keeping the transaction result:

```clojure
(probe2! "audit-g" "string-kind"
  {:seon.error/kind "not-a-keyword" :seon.error/message "hostile kind"})
;; => {:minimal-commit #:seon.error{:kind :seon.db/rejected,
;;      :message "Bad entity value \"not-a-keyword\" at
;;                [:db/add 2570 :seon.error/kind \"not-a-keyword\"] …"},
;;     :terminal-refused!-returned true,
;;     :receipt-terminal? false, :run-closed? false, :run-held? true}
```

The commit was refused, the function reported success, and the receipt stayed
running in an open held run.

Four other adversarial outcomes settled correctly and are useful calibration:
a 200 KB message with a 20 000-element data payload, an empty message, an
absent message, and an ordinary transition refusal all produced a terminal
receipt, a closed unheld run and one durable error fact.

Reachability today: every `:seon.error/kind` Seon itself produces is a
keyword, so the specific falsifier above is not currently reachable from agent
code. That is an argument about today's inputs, not a fence. The class is
"anything that makes the minimal commit refuse", and the code cannot tell
that it happened.

## Owner

`seon.cluster.loop/terminal-refused!` in `src/seon/cluster/loop.cljc`.

## Acceptance

- The function's return value distinguishes "settled" from "the settlement
  commit was itself refused"; the caller may not report a refused settlement
  as a settled one.
- A refused minimal settlement is LOUD by the one
  `:seon.config/on-core-error` dial — it is a core bug, not a condition.
- A regression drives a refused minimal settlement (for example by injecting a
  refusing connection or a hostile outcome value) and asserts that the
  receipt's non-terminal state is reported rather than silently returned as
  success.
- The docstring stops asserting atomic settlement that the code does not check.
