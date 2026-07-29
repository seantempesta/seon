---
type: issue
status: open
severity: blocker
tags: [issue, agent, runtime, database]
---

# Terminalize a receipt when its terminal transaction is refused

## Problem

A refusal from `receipt-settle-call` leaves the already-started receipt
without a terminal fact. The agent immediately derives the same ordinal
again, refuses `receipt-start-call` with `receipt-exists`, records another
durable error, and self-wakes into the same transition indefinitely.

## Evidence

The 2026-07-29 frozen-checkpoint audit drove a scratch cluster through an
agent eval of:

```clojure
(ns-unmap (quote seon.config) (quote defaults))
```

The terminal transaction correctly refused
`:seon.cluster.run/program-delete-not-owned`; the database retained
`seon.config/defaults`, and the SCI context did not apply the deletion. The
receipt nevertheless remained non-terminal. For roughly ten seconds, until
the audit disarmed the agent, the flow repeatedly attempted the same ordinal
and committed many durable `:seon.cluster.run/receipt-exists` error facts.

`src/seon/cluster/loop.cljc` calls `refused!` after the terminal transaction,
but `refused!` only commits an error fact. It does not settle the receipt or
otherwise make `next-agent-work` stop selecting the ordinal. The namespace
docstring currently claims that a rejected terminal transaction is followed
by a terminal error receipt, while the live drive proves that claim false.
The comment in `refused!` also calls its ignored recording outcome a recursion
fence, but that fence covers only recursive recording inside one pass, not the
cross-pass retry loop.

The design gap was already named in
`docs/prds/sci-execution-runtime/research/error-handling-grounding-2026-07-27.md`:
a refused terminal transaction leaves the receipt running until boot
recovery. No open issue previously owned the resulting live hot loop.

## Owner

`seon.cluster.loop` and the `seon.cluster.run` receipt transition. The
surviving mechanism must represent the terminal refusal in database facts in
the same pass, without weakening the transaction function's program-row
fences or teaching work derivation to ignore a running receipt.

## Acceptance

- A live agent whose terminal transaction is refused leaves one terminal
  receipt carrying the refusal as an error value.
- The attempted program mutation changes neither the database row nor the SCI
  context.
- The same ordinal is not selected again, the agent reaches an idle or
  explicitly closed state, and error-fact count remains constant after the
  refusal.
- Restart recovery is idempotent over that already-terminal receipt.
- The namespace and function docstrings describe the behavior the live proof
  observes.
