---
type: issue
status: open
severity: blocker
tags: [issue, runtime, agent, database, live-drive]
---

# Settle a receipt for every recorded run form

## Problem

A run can close having recorded more `:seon.cluster.run.form` rows than
`:seon.cluster.eval` receipts. The final form simply has no receipt, the run
carries `:seon.cluster.run/closed-at` anyway, and nothing in the database says
why. "Every form the run recorded reached a terminal state" is therefore not
true, and it cannot be checked by query without knowing the count on both
sides and comparing them.

This is the accounting half of the 2026-08-06 blocker
[Settle or refuse a frozen plan's first form](run-freezes-before-first-receipt-after-plan-freeze.md).
That note covers a run that stalls before receipt zero and stays open. This is
the opposite and quieter failure: the run does not stall, it closes, and the
missing receipt is silent.

## Evidence

Cluster `default` (pid 79576), observer lane, 2026-08-08. Run
`a7e24a23-14b7-41ab-8a96-5f3c06a9a8ee`, opened 04:31:13Z, closed 04:39:47Z:

```clojure
;; forms and receipts for the run, joined on ordinal
{:forms 7
 :evals 6
 :form-ordinals [0 1 2 3 4 5 6]
 :eval-ordinals [0 1 2 3 4 5]}
```

Ordinal 6 has `:seon.cluster.run.form/source` and no `:seon.cluster.eval` row
at all — no result, no error, no `:seon.cluster.eval/interrupted-at`. Its
recorded source is prose the model wrote:

```text
; Then compare that output against the spec that `seon.db/read-evidence` is
; supposed to satisfy.
; If this is a library function and you are calling it correctly, this might be
; a bug in the library. But the most likely cause is that `read-evidence` is
; receiving an incomplete DB value or no profile.
```

Across the whole cluster the same census reads 20 forms and 19 receipts, so
this is the only instance so far — but it is undetectable except by counting.

For contrast, `bootstrap:root` recorded 13 forms and 13 receipts with ordinals
0–12 complete, five of them errored, so the receipt path itself works. The
cluster-wide `:seon.cluster.eval/interrupted-at` count is 0, which rules out an
interruption being recorded and then lost.

## Owner

The run loop's form-settlement path in `src/seon/cluster/loop.clj`, and the
`:seon.cluster.eval` facts declared in `resources/seon/schemas/`.

## Acceptance

- Every `:seon.cluster.run.form` belonging to a closed run has exactly one
  `:seon.cluster.eval` row, or a recorded reason the form was not evaluated.
- If a comment-only form is deliberately not evaluated, that is a declared
  fact on the form or a receipt saying so — not an absent row.
- A run cannot acquire `:seon.cluster.run/closed-at` while a recorded form of
  its own is unsettled.
- One class regression drives a run whose final form is prose only and asserts
  the form and receipt counts agree.

## Note for whoever fixes this

If the intended design is that prose is never a form, then the defect is
upstream: the form row should not have been recorded. Either answer is fine,
but the two sides must agree, because the run renderer and any custody audit
both read the form list and the receipt list as one thing.
