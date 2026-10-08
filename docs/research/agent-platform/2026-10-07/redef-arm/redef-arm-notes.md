---
title: A redefinition is armed with the contract authored at its write (redef-arm)
type: research
status: landed
date: 2026-10-08
lane: redef-arm
---

# redef-arm: a redefinition is armed with the contract authored at its write

Task `seon.sci.arm/a-redefinition-in-one-pass-is-armed-with-the-earlier-contract` (4d9d0aba5ed0), evidence
`docs/research/agent-platform/2026-10-08/s5-admission/redef-design.md` result 5. Path step served: eval (arming at
the write). Owner: `seon.instrument/compiled-wrapper`. Deletes nothing; moves one hidden preference to the callers
that want it.

## Reproduction (agent `redef-arm`, SCI, namespace `my.redef-arm`, default at `c8a91566d`)

- One pass: `(defn ^{:malli/schema [:=> [:cat :int] :int]} h1 [x] (inc x))`,
  `(defn ^{:malli/schema [:=> [:cat :int :int] :int]} h1 [x y] (+ x y 100))`, `(h1 1 2)` answers
  `refused argument count: expected a declared argument count (1)`.
- Wider than the task said: the same refusal across passes. Pass 1 `(defn ^{:malli/schema [:=> [:cat :int] :int]} h2 [x] (inc x))`;
  pass 2 `(defn ^{:malli/schema [:=> [:cat :int :int] :int]} h2 [x y] (+ x y 100))` then `(h2 1 2)` is refused the
  same way. Any write whose contract differs from the one the arming projection already holds for its symbol.

## Cause (verified)

`seon.sci.arm/arm!` hands `seon.sci.eval/install-function-contract!` the authored contract and the working value's
projection. `compiled-wrapper` then took `(get function-contracts sym authored)` and `(mr/schema registry sym)`:
whenever the projection held a contract for the symbol (the earlier form's applied row, or the earlier pass's
committed row), it armed that one and the authored contract was ignored.

JVM-mode read-only probe on this agent's branch (projection holding h1's 2-arity contract), calling
`compiled-wrapper` with authored `[:=> [:cat :int] :int]`: `(w 1)` refused "expected a declared argument count (2)",
`(w 1 2)` returned. With the authored contract in the projection it armed the authored one.

## Fix

`compiled-wrapper` arms the contract its caller passes, reusing the registry's retained compile of the symbol unless
the projection holds a different contract for it (a first version that also refused a registry entry with no
function-contract beside it turned `seon.instrument-test/a-refusal-that-cannot-be-built-is-a-minimal-declared-error`
red; the committed rule keeps that path exactly as before). The two host callers (`apply!`, the replaced-root re-arm) pass the
projection's contract for the symbol, else the authored one, exactly what `compiled-wrapper` chose before: host
behaviour is unchanged (162 of 2,683 loaded host contracts differ from their projection's, and those keep arming
the projection's). The interpreted caller (`wrap-interpreted`) passes the authored spec, so a write arms its own
contract.

Alternative tried first and rejected: in `arm!`, rebuild the arming projection with the authored contract
(`schema/projection-with-declarations`). Correct, but 5.3-8.4 ms and 10.1 MB allocated per changed-contract write
(7 runs), against 0.27-0.54 ms and 129 KB for the direct compile the fix uses.

## Numbers (same probe shapes, agent `redef-arm`)

Parent (default JVM, loaded by other lanes' checks; noisy):

| Pass | ms | armed calls |
|---|---|---|
| one fresh defn | 1-6 | 223-368 |
| in-pass redefinition, same contract | 18-46 | 11,080-11,085 |
| in-pass redefinition, changed contract (wrong contract armed) | 14-92 | 11,073-11,087 |

Change: no new armed call on any path (the host callers inline the same `get`; `compiled-wrapper` adds one `=`).
A changed-contract write now compiles its authored contract directly: 0.27-0.54 ms, 129 KB per write (measured
read-only on the default JVM via `seon.schema/contract-product`), instead of reusing the stale retained compile.
Same-contract writes and fresh names take the same path as before. The change could not be timed in default's JVM
(no uncommitted code there); the throwaway check JVM proves behaviour only.

The 11k armed calls of a redefinition (call-preparation snapshot rebuild of the changed working value) are
unchanged and remain the S5-04 seam's to remove.

## Proof

Commit `b391c0ef9`: `bin/lane commit` check, 24 tests executed, 202 assertions pass, 0 fail (tests 9.9 s, total
98.5 s). `seon.cluster.turn-test/a-changed-arity-redefinition-is-callable-in-the-same-pass` (local provider, no Var
redefinition): red on the parent (`bin/seon check`, 15 min under load: the call's shown value is the arity
refusal), green with the fix. `contracted-redefinition-exactly-replaces-the-entity` (body-only redefinition) and
the instrument tests reaching `compiled-wrapper` and host arming run in the commit's check.

## Findings

- `arm!` prints the authored contract (`pr-str`) for `wrap-interpreted` to read back: task
  `seon.sci.arm/arming-prints-the-authored-contract-to-read-it-back`.
- The end-of-pass `(meta v)` hazard (redef-design result 6) is not touched here: `derive-form` still runs per form.
- `bin/seon check` of one deftest took 15 min wall under eight concurrent checks (rehearsal alone 19 s when idle).

## Design check

The design held: the producer of the wrong choice was the preference inside `compiled-wrapper`, not the projection
`arm!` passes; rebuilding the projection in `arm!` would have patched around it at 40x the cost. Awkward: the
`authored` parameter of `compiled-wrapper` was really "a fallback", so the name now says `contract`. No RESET
NEEDED; root's next restart picks the change up.

## Slice 2: a schema registered in the same form as the function using it

Task `seon.turn.batch/a-function-using-a-schema-registered-in-the-same-form-is-not-admitted`, evidence
`docs/research/agent-platform/2026-10-08/s5-admission/schema-design.md` probe 5, assigned by root.

- Reproduced (agent `redef-arm`): `(do (seon.schema/register! :my.redef-arm/pct [:int {:min 0 :max 100}]) (defn
  ^{:malli/schema [:=> [:cat :my.redef-arm/pct] :int]} half [x] (quot x 2)))` answers the value with the diagnostic
  "Not admitted to the program ... derive: :malli.core/invalid-schema" from `seon.schema/contract-product`, raised in
  `seon.turn.batch/write-entity` (`sfn/contract-facts-row (db/carried-projection db) ...`); `(half 50)` = 25 (armed).
  415 ms, 32,582 armed calls for the pass (the registration dominates, existing task).
- Cause: arming (`seon.sci.arm/arm!`) and the schema transaction read `seon.sci.arm/arming-projection` (the batch's
  registration delta included); `write-entity` read the working value's carried projection, which lacks the form's
  own registration until `apply-pending` applies it. Two views of one decision.
- Fix: `derive-form` takes `arming-projection` once per analyzed form and hands it to `write-entity`, whose
  contract facts read it. One owner of the projection a write is armed and admitted under.
- Cost: `arming-projection` without an unapplied registration is 5 µs and 8 KB unarmed (5 runs, read-only on the
  default JVM), one more armed call per defining form. With an unapplied registration it rebuilds the projection
  (`schema/projection-with-declarations`, 5-8 ms and 10 MB measured in slice 1) and today does so once per arming
  write, once in `schema-notes` and now once in `derive-form`: the same rebuild three times per form, a finding below.
- Proof: `seon.cluster.turn-test/a-function-using-a-schema-registered-in-the-same-form-is-admitted` asserts the
  admitted function's input ref is the key registered beside it.

Finding: `seon.sci.arm/arming-projection` recomputes the same delta projection for every caller in a form (arm per
write, `schema-notes`, `derive-form`); the batch could take it once per form and pass it. Recorded in the task
`seon.sci.arm/arming-projection-is-rebuilt-per-caller-within-one-form`.

Design check (slice 2): held. The batch already had the right projection beside `request` and `working`; the defect
was a second view read in one place. Awkward: the per-caller rebuild above; passing one value per form is the next
step, left to the S5-04 seam that removes `apply-pending`.

Red outside this slice: `seon.cluster.turn-test/runtime-schema-registration-commits-the-evaluated-form-and-attribute`
errors at its `:seon.schema/ns` assertion on the parent batch (check run `97ef108a0110`, without this slice's
`batch.clj`) and with it (run `eec7d689719f`): the registered schema stores no namespace ref. Existing task in
`resources/seon/tasks/seon.sci.reader.edn` (`:seon.schema/ns` link dropped on the batch path); not run in this
slice's commit check.

