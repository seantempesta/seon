---
type: issue
status: open
severity: blocker
tags: [issue, test, repl, sci]
---

# Make REPL parity fail when an expected row disappears

## Problem

The REPL-parity fixture derives and prints its known-divergence and pending
counts, but no assertion owns the expected 88-row inventory. Deleting a
`defparity` form or a `pending-rows` entry therefore makes the gate smaller and
still green. This is the same absence-of-signal failure class that previously
allowed a 59-row implementation to be reported as an 88-row gate.

## Evidence

- `test/seon/repl_parity_test.clj:112-120` puts row identity on test Var
  metadata, and `:122-161` holds the pending rows.
- `test/seon/repl_parity_test.clj:163-167` derives executable rows from Var
  metadata.
- `test/seon/repl_parity_test.clj:179-191` prints derived counts after the
  tests; it makes no assertion about row identities, family counts, or total
  cardinality.
- `tmp/audit-20260801b/src/parity_gate_probe.clj` independently counted 69
  executable plus 19 pending rows, family counts
  `A=10 B=11 C=8 D=11 E=16 F=6 G=10 H=8 I=8`, total 88. It then removed one
  row's metadata before invoking the report fixture; the test counters remained
  `{:fail 0 :error 0}`.

## Owner

The REPL-parity recurring gate in `test/seon/repl_parity_test.clj`.

## Acceptance

- One derived manifest names the expected row identities or derives them from
  the pinned upstream corpus.
- The recurring gate asserts the complete identity set and per-family counts,
  not merely a printed total.
- Removing either an executable row or a pending row makes the focused test
  command fail.
