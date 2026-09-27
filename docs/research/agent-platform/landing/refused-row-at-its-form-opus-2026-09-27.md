---
type: design
lane: one-commit-a-opus (audit cut row 2, outcome classification)
date: 2026-09-27
status: revised after Astra REVISE (review-refused-row-2026-09-27.md, ac4e6c50b); awaiting second review
---

# A refused run settlement names the form the writer names

## Verdict on the first design: withdrawn

The per-form trial (apply each form's row with `settled-value` and refuse that form at once) **cannot be made
faithful**, for two reasons.

1. **It rejects repairs within the same transaction.** The writer checks removed identities against the **final**
   database (`seon.db/removed-definition-error`, db.clj:4150-4208). The arity refusal explicitly allows callers to
   be repaired in the same transaction (db.clj:4483-4503).
   - Example: form 1 deletes H, form 2 deletes its caller C. The whole transaction passes.
   - A trial of form 1's row alone refuses H. Dropping H then publishes a different program.
   - No single-row trial can know what a later form will repair.
2. **It is not the settlement's own input.** The trial passes only `{:seon.program/row row}` to `row-tx`. It leaves
   out the run's identity and provenance, the form's own mid-body writes, final namespace digests, receipts and
   close (review §2 table).

Making the trial faithful would mean trialling the whole remaining transaction, provisionally, per form. That is
alternative 3 in the review: a working-world design with no small seam. It is not proposed here. Replacing the
form's returned value with the refusal also broke the total path (review §1). Both points are accepted.

I also correct a claim from the first note. **SCI does not keep the refused change.** On a refused batch,
`resume-turn` calls `restore-evaluations!` (turn.clj:5084-5101), last form first, before answering. The
mismatch that remains is only *within* a run: `with-settled` keeps the old working value when a trial refuses.

## What exists now (HEAD after f83931226 and cut 1)

- `settle-batch!` issues one ordered transaction. The writer is the sole authority, and repairs within the
  transaction hold.
- On refusal, `settle-batch-refusal!` keeps each form's evaluated `:seon.eval/shown` (its value).
  - It gives each receipt the same `:seon.cluster.eval/error`: "Not settled: this run's settlement was refused:
    <message>".
  - It puts the refusal as data in `:seon.cluster.eval/diagnostics`.
  - It closes the run in one commit. `restore-evaluations!` then undoes every form's program change.
- The totality law already holds here. In the failing host test, form 2 shows its real value `nil`. Its error
  names the surviving referrers, and so does every other form's error.

What is missing is **attribution**. The writer's refusal names what it refused, but every receipt says the same
thing, so the agent cannot see which of its forms made the change the program refused.

## Change: attribute from the writer's own refusal data, whole-run refusal kept

This is the smaller alternative. In `settle-batch-refusal!`, before the receipts are built:

- **Read what the refusal names, by its declared schema, and nothing else.**
  - For `:seon.program/deletion-refused-error`, the names are the `:seon.program/subject`s of
    `:seon.program/referrers`: the removed identities whose obligations failed on the final database.
  - Other refusal schemas attribute nothing in this cut. Each later gets its own mapping, keyed by its declared
    schema and added only with its regression.
- **A form is attributed** only when its own evaluated row, from the request's `:seon.program/row`, declares or
  deletes one of those identities (`:seon.program/delete-identities`, or the row's identity attribute).
  - That is provenance: the row the form produced, matched against the identity the writer named.
  - No row is re-applied and nothing is retried. It is one pass over the rows already in hand, costing
    O(forms × named subjects).
- **Receipts:**
  - Every form keeps its evaluated shown value and its own error if it failed. Nothing is replaced.
  - An attributed form's error is the refusal message itself, marked as this form's change.
  - Other forms keep today's text, plus the attributed ordinals: "Not settled: this run's settlement was refused
    for form N's change: <message>".
  - The refusal data stays in `:seon.cluster.eval/diagnostics`, as now.
  - If nothing is attributable, today's behaviour is unchanged.
- **Same-transaction repairs:** the writer judged the whole ordered transaction, so repairs are respected by
  construction. The subject it names is the removal that still has a surviving referrer on the final database.
- **SCI and the database:** unchanged. `restore-evaluations!` restores every form, last first, because nothing in
  the run was published. Its failure keeps its declared unavailable-context answer.
- **Unchanged:** the working value within a run and the admission and transaction boundaries. The rest of audit
  row 2 (execution status, and the `shown-result`/`failure-value` replacements at the install gate and `phase`)
  stays row 2's own slice, per the review's recommended alternative 1.

Size estimate: about 20 lines added in `turn.clj` (one private `attributed-ordinals` function of the refusal and
the requests, plus the receipt text). No new schema, since the error text and diagnostics attributes already
exist. Nothing is deleted.

This is presumed small enough without a matching deletion. If review wants the attribution stored as data
rather than text, the smallest form is an optional ref from a receipt to the error, so a reader need not parse
messages. That would be a schema addition and needs a ruling.

## Open question, verified first

I have not yet checked whether the value `settle-batch-refusal!` receives still carries
`:seon.program/referrers`. It comes through `phase` and `db/transact!`, and the recorder
(`error/recording`) may reshape it.

- Evidence so far, from branch `oca-attr` on default at 2026-09-27T01:16Z, now retired: the
  `ns-unmap` of a helper with a surviving caller answered `:value nil` with the refusal under `:diagnostics`.
  That is the totality law holding.
  - The *recorded* diagnostic has no `:seon.program/referrers` key. The subject and referrer appear only
    inside `:seon.error/message`.
  - So the recorder's projection drops the field. The raw `outcome` that `settle-batch-refusal!` receives
    before `error/recording` still needs inspecting.
- Probe step 1: on a disposable branch, run the host test's three forms and inspect the `:refused-outcome`
  the settlement returns.
- The attribution reads the raw refusal, never the recorded one.
- If the referrers are not there, the producer (`db/transact!`'s refused result) is fixed to keep its declared
  fields. The design does not fall back to parsing the message.

## Cost

With no refusal, cost is zero: the code runs only in the refusal arm. With a refusal it is one scan of the
run's rows against the named subjects, with no database work.

## Regressions

1. Revise `seon.cluster.host-test/a-refused-settlement-is-its-forms-refusal-and-the-run-closes`, which currently
   asserts shown text that the total path forbids:
   - Form 2's shown value is `"nil"`, its real value.
   - Form 2's error contains "surviving referrers" and marks the change as its own.
   - Forms 1 and 3 show `"3"` and `"7"`, and their errors name form 1 (0-based ordinal of the `ns-unmap`).
   - The next run `(refused-caller 1)` answers `"2"`, which proves the restoration.
2. A same-transaction repair, as a new case in the same deftest's world. One run holds
   `(ns-unmap … 'refused-helper)` then `(ns-unmap … 'refused-caller)`. It publishes, and both identities are
   gone from SCI and the database. This exercises the writer's final-database semantics that the withdrawn
   design would have broken.
3. Unchanged and still required: `seon.turn-test/a-refused-settlement-keeps-each-evaluated-value`, where a
   refusal naming no subject attributes nothing.
4. Unaffected, run for safety: `seon.cluster.host-test/a-run-reads-its-earlier-declarations` and
   `seon.cluster.evaluate-source-test`.

Review regression classes 1, 4 and 6 (outcome distinctions, changing bases, restoration failure) belong to the
row 2 slice. This cut adds no new path through them.
