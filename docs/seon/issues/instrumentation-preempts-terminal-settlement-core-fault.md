---
type: issue
status: open
severity: blocker
tags: [issue, agent-runtime, instrumentation, run-loop]
---

# Instrumentation preempts the terminal settlement core fault

## Problem

`seon.cluster.loop/terminal-refused!` promises that invalid settlement
construction raises the named core fault
`:seon.cluster.loop/terminal-refusal-settlement-refused`. With instrumentation
armed, malformed refusal data instead violates `seon.error/normalize`'s output
contract while `terminal-refused!` is still constructing the candidate fact.
Its explicit candidate validation and named fault are never reached.

The armed and unarmed paths therefore disagree at the newest safety seam, and
an instrumentation violation replaces the fault identity the operator needs.

## Evidence

Seam re-audit attempt 6 armed 358 instrumented vars in isolated cluster
`seam-reaudit6-hostile` and called the real private settlement path with:

```clojure
{:seon.error/kind "not-a-keyword"
 :seon.error/message "hostile kind"}
```

The call committed zero transactions and left the receipt running in its open,
held run, as required for later recovery. It threw
`:seon.instrument/contract-violated`, however, with an invalid-output report
that `:seon.error/kind` should be a keyword. It did not throw
`:seon.cluster.loop/terminal-refusal-settlement-refused`.

The construction order explains the result:

- `src/seon/cluster/loop.cljc:368-379` calls `error-tx` and therefore
  `seon.error/normalize` before evaluating `valid?`;
- `src/seon/cluster/loop.cljc:390-395` raises the named construction fault only
  after that earlier call returns; and
- `src/seon/error.clj:271-302` declares a valid `:seon.error/fact` as
  `normalize`'s output, so instrumentation rejects the malformed intermediate
  value first.

A separate hostile candidate using a valid encoded refusal plus an instant
value Datahike rejected reached the transaction-outcome fence correctly:
`terminal-refused!` threw the named settlement fault, returned no silent
`true`, and committed nothing. The transaction-result check is sound; the
blocker is specifically pre-validation construction while instrumentation is
armed.

## Owner

`seon.cluster.loop/terminal-refused!`, at its construction boundary with
`seon.error/normalize`.

## Acceptance criteria

1. With instrumentation armed, any settlement candidate that cannot produce
   the registered error fact and flat value raises
   `:seon.cluster.loop/terminal-refusal-settlement-refused`.
2. The same hostile input produces the same named fault with instrumentation
   armed or disarmed; no `:seon.instrument/contract-violated` escapes first.
3. Invalid construction and refused commit both perform zero database writes,
   never return `true`, close the agent's process-local mailbox, and leave the
   receipt for boot recovery to mark interrupted.
4. One recurring armed test covers malformed construction and one covers a
   database-refused settlement.

## Related

- `docs/prds/sci-execution-runtime/research/checkpoint-audit-2026-07-29.md` —
  seam re-audit attempt 6 carries the full live proof.
