---
type: design
lane: one-commit-a-opus (audit cut row 2, outcome classification)
date: 2026-09-27
status: design, awaiting review
---

# A refused program row is its form's refusal (audit cut row 2) — design note

Lane one-commit-a-opus, 2026-09-27. Status: DESIGN, not implemented. Waiting for review.

## What exists

- `seon.turn/evaluate-sources` (turn.clj ~4895-5040) evaluates a run's forms against a *working value*.
  Before each later form, it applies the earlier forms' `:seon.program/row`s through `with-settled`
  (turn.clj:1417). That function calls `settled-value` (turn.clj:1407), which is `db/with` over the writer's
  own `row-tx`. When the writer would refuse a row, `with-settled` **drops the refusal silently** and leaves the
  working value unchanged. This is the same decision settlement makes, reached earlier. Then nobody reads it.
- The run's one commit (`settle-batch!`, turn.clj:3795) then carries the refused row. The writer refuses the
  **whole batch**, and `settle-batch-refusal!` (turn.clj:~3970) marks every form "Not settled: this run's
  settlement was refused". It deliberately does not guess which form caused it; the guess was deleted in
  f83931226, which is why `seon.turn-test/a-refused-settlement-keeps-each-evaluated-value` exists.
- `sci.eval/refuse-install` (eval.clj:4865) already declares this refusal as an input:
  `[:or install-refused-error :seon.db/error-result :seon.turn/refused-error]`. Its docstring says the refusal
  may be "the writer's refusal of this form's row at settlement (`seon.turn/settled-value`)". No caller passes
  one. That is the missing producer.

The result, seen in `host-test/a-refused-settlement-is-its-forms-refusal-and-the-run-closes`: `(ns-unmap …
'refused-helper)`, while `refused-caller` still refers to it, shows `nil`. Every form in the run loses its
publication. SCI keeps the unmap even though the database refuses it, so the agent's world and its branch
diverge until the next acquisition.

## Change: decide at the producer, per form

In `evaluate-sources`, right after a form's evaluation (and after `gate-function-install`), apply that form's
row to the working value through `settled-value`:

- **Settles:** the working value advances by that row. It is carried to the next form in place of the lazy
  `[commit-id applied-db applied]` re-application.
- **Refused:** `(sci.eval/refuse-install request evaluation refusal)`. The form's SCI change is restored by its
  own `:seon.sci.eval/restore` (the one restoration that admission, arming and the gate already use). The row
  is dropped, and the form's shown value is the writer's refusal ("Program deletion leaves surviving
  referrers …"). The batch then commits the other forms normally.

`settle-batch-refusal!` stays as it is. It handles a refusal nothing predicted, for example a concurrent write
between evaluation and commit, and it still makes no guess. That is the rare path; the refusal is the common one.

## What disappears

- The silent drop in `with-settled`. It is deleted, and `settled-value` becomes the only call.
- The lazy re-application bookkeeping in the loop (`working` as `[commit-id applied-db applied]` plus the
  `subvec` re-reduce), about 8 lines. It is replaced by one working value that advances per form. It is rebuilt
  from `latest` only when the connection moved under the run, as today.
- The divergence between SCI and the database after a refused deletion or declaration.

Estimate: about 15 lines added and 12 deleted in `turn.clj`. No new function, schema or registry.

## Cost

One extra `db/with` for the **last** row-bearing form of a run. Today every earlier row is already applied
before the next form; only the last is skipped. Forms without a row pay nothing. I will measure it on parent vs
change with the one-commit probe (`docs/research/agent-platform/landing/one-commit-defn-probe-2026-09-26.clj`,
a one-`defn` run). Per the hot-path rule, a regression over 20% or 50 ms gets fixed before commit.

## Alternatives considered

1. Guess the refused ordinal in `settle-batch-refusal!` by re-applying rows. This was deleted in f83931226 and
   stays deleted: it is triage after the fact, and SCI stays diverged.
2. Pre-check all rows in `settle-batch!` before committing. It costs the same, but the refusal arrives after
   every later form has already run against a world that assumed the change. That is wrong for a later form
   that reads it, as in `a-run-reads-its-earlier-declarations`.
3. The proposed per-form decision: the refusal belongs to its form at the point where the form's change is
   decided, like the install gate.

## Proof plan

- Regression (exists): `seon.cluster.host-test/a-refused-settlement-is-its-forms-refusal-and-the-run-closes`.
  Form 2 shows the surviving-referrers refusal, forms 1 and 3 keep "3" and "7", and the next run
  `(refused-caller 1)` answers "2".
- Unchanged: `seon.turn-test/a-refused-settlement-keeps-each-evaluated-value`, since the batch refusal arm is
  untouched.
- `seon.cluster.host-test/a-run-reads-its-earlier-declarations` and `seon.cluster.evaluate-source-test`.
- A REPL probe on a disposable branch before commit (the three-form run above, with receipts pulled back).
