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

## Recurrence, 2026-08-08 (whole-system-arc observer lane)

Reproduced on cluster `default` (pid 31475), a different cluster and a
different drive, with the context defect fixed — so this is independent of the
empty-prompt failure that surrounded the original sighting.

101 forms, 99 receipts. The gap is exactly 2 and persistent. Both unsettled
forms belong to `root`, and both are prose-only forms whose recorded source is
**raw provider control markup**:

```clojure
{:run "b0f70394" :agent "root" :nforms 1 :nevals 0 :missing-ordinals [0]
 :src "; <｜｜DSML｜｜AgentThoughts>We need respond to current instruction about core fault. Need inspect. Let's gather data first.</｜｜DSML｜｜AgentThoughts>"}
{:run "91967e81" :agent "root" :nforms 1 :nevals 0 :missing-ordinals [0]
 :src "; <assistant1>"}
```

Both runs carry `:seon.cluster.run/closed-at` with an unsettled form, as before.

This names the trigger the original note left open. DeepSeek's internal channel
markup (`<｜｜DSML｜｜AgentThoughts>…`, `<assistant1>`) is reaching the reply
parser, being classified as prose, recorded as a comment-only form, and then
settling nothing. So the "prose-only form" case is not a rare model stylistic
choice — it is produced systematically by provider control tokens leaking into
the reply, which makes the accounting gap recurrent rather than incidental.

Worth noting for the fixer: that markup arguably should not become a form at
all, which is the "defect is upstream" branch below.

For contrast, a transient gap during concurrency is healthy and self-corrects:
with three bootstrap runs evaluating simultaneously the census briefly read
80 forms / 46 receipts at 09:45:04 and converged to 80/80 within 10 seconds.
Only the two prose-only forms stayed unsettled.

## Note for whoever fixes this

If the intended design is that prose is never a form, then the defect is
upstream: the form row should not have been recorded. Either answer is fine,
but the two sides must agree, because the run renderer and any custody audit
both read the form list and the receipt list as one thing.

## Cause identified — 2026-08-08 whole-system arc drive

Reproduced on cluster `default`: **105 forms, 102 receipts**, across three runs
that each recorded one form and zero receipts.

The three unreceipted forms share one shape — they are comment-only, and every
one of them is comment-only because the provider's own chat-template control
markup leaked into the completion text:

```text
b0f70394  ; <｜｜DSML｜｜AgentThoughts>We need respond to current instruction
          about core fault. Need inspect. Let's gather data first.
          </｜｜DSML｜｜AgentThoughts>
945f3226  ; <assistant1>I’m checking the facts before answering — first the
          relevant schema and entity attributes.
91967e81  ; <assistant1>
```

So there are two defects stacked, and this issue owns the second:

1. `<assistant1>` and `<｜｜DSML｜｜AgentThoughts>…</…>` are deepseek-v4-flash
   template control tokens appearing verbatim in the model's reply text. They
   should never reach the reply parser.
2. The reply parser turns such a fragment into a comment-only form, and a
   comment-only form records a `:seon.cluster.run.form` row with **no**
   `:seon.cluster.eval` receipt — which is exactly the accounting gap this
   issue was filed for.

The previous observation that the gap's forms were "prose-only" is confirmed
and now explained: the prose is provider markup, not the model's own comment.

Acceptance is unchanged for this issue — every recorded form settles a receipt,
including one whose source evaluates to nothing. The token leakage belongs to
`seon.ai`'s request/response handling and is worth its own note.
