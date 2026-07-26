---
type: issue
status: open
severity: blocker
tags: [issue, agent, runtime, database]
---

# Prove agent-message identity under form re-execution

## Problem

Message delivery under form re-execution has no production evidence. The
runtime plan says today's reply message takes a fresh id and names an A-to-B
kill experiment, but the current JVM SCI base cannot call `message/agent`.
The experiment's required state—an agent message committed inside a form whose
terminal receipt has not committed—is therefore unreachable.

The current lifecycle reply does not substitute for that experiment. Although
its message id is freshly allocated, its message, terminal receipt, turn
completion, and run closure share one transaction. It has no
message-committed/run-open state to kill in.

## Evidence

Verified 2026-07-26 at
`472797ffc8f4ab432e7a206e00e3ca832e0a4fe1`.

- `seon.sci.ctx/base` exposes only core, string, and lifecycle functions
  (`src/seon/sci/ctx.clj:15-35`).
- Direct evaluation of
  `(seon.agent.message/agent "agent-b" "probe")` returns
  `Unable to resolve symbol: seon.agent.message/agent` before any transaction.
- `lifecycle-tx-data` places the local reply-message placeholder beside the
  run-close data (`src/seon/agent/driver.clj:74-98`).
- `execute-form!` concatenates the terminal receipt and admitted lifecycle data
  into one terminal transaction (`src/seon/agent/driver.clj:283-293`).
- `allocated-transact!` requests the generated message id at
  `src/seon/agent/driver.clj:403-404`, substitutes it at lines 409-411, and
  submits the complete transaction builder once.

The complete preflight, executable result, transaction analysis, and deferred
three-trial method are recorded in
[[../../prds/sci-execution-runtime/research/double-send-experiment-2026-07-26]].

Wake is no longer a masking factor. Commit `4dbaeda0e` makes an agent-origin
message eligible under the one `waking-inbound?` rule, so two committed
messages will be visible as two candidate causes and recipient runs.

## Owner

`seon.agent.driver` owns receipt/run recovery and the current reply transaction.
The plan step-1 effect path will own callable messaging and operation identity.
`seon.agent.message` remains the one message transaction owner.

## Acceptance

- After plan step 1 makes `message/agent` callable on the JVM, a throwaway named
  cluster runs the post-send/pre-terminal `SIGKILL` experiment three times.
- Evidence retains, for each trial, A's running and terminal receipts, claim
  epochs, every A-to-B message id and transaction, every B run cause, and the B
  transcript count.
- A confirmed duplicate is fixed by deriving message identity from the sending
  receipt `(run, ordinal, epoch)` rather than allocating a fresh identity.
- Repeating the same crash experiment after that change yields exactly one
  durable A-to-B message, one B run cause, and one B transcript occurrence per
  logical send.
- The proof uses the production effect path and recurring writer runner; no
  injected SCI binding, second message ledger, acknowledgement flag, or
  synthetic driver stands in for the real mechanism.
