---
type: issue
status: open
severity: blocker
tags: [issue, runtime, agent, database, class/n10, wave/live-drive-context]
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

## Reply half fixed — 2026-08-08 repair lane

The reply boundary now takes the "prose is never a form" branch this note
offered, so the shape that produced every observed gap is unrepresentable.

`src/seon/cluster/reply.clj` changed in one rule: **a plan source always
carries a reader event.** Prose still attaches to the form it precedes; a
TRAILING prose span now rides the form it FOLLOWS instead of becoming its own
comment-only source; and a reply with no code at all is a loud
`:seon.cluster.reply/no-forms` refusal whose message names that the text read
as prose and whose `:seon.error/data` carries the text verbatim, so a leak
stays visible rather than being filed as agent source. The refusal takes the
run loop's existing reply-refusal path (`fail!` → `settle!` with no ordinal),
which records `:seon.cluster.run/error`, commits a durable error fact, and
closes the run — a recorded reason where there used to be silence.

Verified live on cluster `default` against the three recorded gap sources: all
three (`<assistant1>`, `<assistant1>I'm checking the facts…`, and the
`<｜｜DSML｜｜AgentThoughts>…</…>` span) now refuse instead of recording a form.

Regressions: `a-reply-of-provider-control-markup-refuses-instead-of-recording`
and `every-plan-source-carries-a-reader-event` in
`test/seon/cluster/reply-test`, plus
`a-pure-prose-reply-refuses-and-records-no-unsettleable-form` in
`test/seon/cluster/turn_test.clj`, which replaces
`a-pure-prose-reply-records-input-without-a-failed-receipt` (that test pinned
the deleted behavior) and asserts the form and receipt counts agree.

### The token leakage has no wire fix, and needs none

`seon.ai` was read end to end at both assembly seams and is already correct at
the provider's own field boundary: `stream-event` (`src/seon/ai.clj:694-760`)
builds `:seon.ai/text` only from `choices[0].delta.content`, and
`completion-text`/`parsed-completion` (`src/seon/ai.clj:794-860`) only from
`choices[0].message.content`; `reasoning_content` lands in its own
`:seon.ai/reasoning-content` and is never concatenated into the text. The
control markup therefore arrives INSIDE the `content` field — deepseek-v4-flash
chat-template tokens emitted as ordinary completion bytes, with thinking
disabled for that model (`config/default.edn:277-304`). There is no field
boundary left to separate them on, and none is needed: the reply refusal makes
the leak loud and named without any text matching. The markup is not ours —
`assistant1` and `DSML` have zero occurrences across `src/`, `resources/`, and
`config/`.

### Found in passing, fixed in the same file

All three declared reply error classes were UNPRODUCIBLE. A declared error
class is recognised by its marker attribute — the one required key besides
`:seon.error/message` (`test/seon/error_class_schema_test.clj`,
`marker-attribute`) — and `seon.cluster.reply/refused` emitted only
`:seon.error/kind`, `:seon.error/message`, and `:seon.error/data`, so
`:seon.cluster.reply/no-forms-error`, `/unreadable-error`, and
`/refused-tag-error` never matched and every reply refusal rendered through
the generic value floor. The recurring gate did not catch it because it
GENERATES class values rather than reading producers. `refused` now takes the
marker explicitly (never derived from the kind's name, which would be a
naming convention), and `every-refusal-matches-its-declared-error-class` in
`test/seon/cluster/reply-test` claims the fact from the producer side.

### What remains open

- The ruled design in `src/seon/cluster/work.clj` ("Comment-only input has no
  reader event and needs no receipt", `evaluable-source?` at
  `src/seon/cluster/work.clj:90-94`) still stands and is now unreachable from
  the model-reply path. Whether it should be deleted outright — making an
  unsettleable form impossible for EVERY producer rather than only for replies
  — is an owner call, because it is a sealed contract with a docstring
  (2026-07-27 seal revision) and it belongs to the loop owner, not the reply
  boundary.
- Other producers of `:seon.cluster.run.form` rows — `seon.bootstrap`
  (`src/seon/bootstrap.clj:217,301`) and `seon.eval.drive`
  (`src/seon/eval/drive.clj:160`) — do not pass through the reply reader, so
  the invariant is structural on the reply path only. Neither has been observed
  producing a comment-only source; a census is the cheap falsifier.
- Acceptance items 1–3 are therefore satisfied for model replies and unproven
  for the other two producers. The issue stays open on that account.
