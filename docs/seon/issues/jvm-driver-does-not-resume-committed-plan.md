---
type: issue
status: open
severity: blocker
tags: [issue, agent, runtime, database]
---

# Resume JVM execution from the committed run plan

## Problem

The JVM driver commits ordered run-form facts but executes the process-local
`sources` vector. Its existing `next-form` helper is not called by the
production path, so a process restart cannot resume from the first
nonterminal durable ordinal.

## Evidence

At commit `c03ff91eb`, `process-message!` passes the local parsed source vector
to `drive-sources!`. `next-form` has unit coverage but no production caller.

This does not invalidate the corrected single-turn waterfall: that turn
committed one plan form and completed it without restart. It does leave the
larger crash-resumption contract unsettled.

## Owner

The claim-native JVM driver owns loading the committed plan and eval receipts
at one database value, deriving the next ordinal, and executing that form.

## Acceptance

- After plan commit and process loss, the next process reads the durable forms
  and receipts rather than reparsing or reusing a model reply.
- Completed/error ordinals are not re-evaluated.
- A live kill/restart drill completes the remaining form and publishes once.
