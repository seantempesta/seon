---
type: issue
status: open
severity: blocker
tags: [db, pull, error-model, class-kill, class/absence-as-health]
created: 2026-09-17
---

# A database read's error value is read as a row by its caller

## The class (three instances in one night, 2026-09-17)

`seon.db/pull` (and `q`) return a flat `:seon.error` VALUE when the read
fails; that value is a map, and every caller that binds the result as
"the row" (or takes `(:db/id …)` of it) reads the refusal as data:

1. `seon.config/effective-in` — read the error as the config row; reported
   68 "missing" facts (resolved in `761408a17`).
2. `seon.config/population-transaction-data` — read it as "no such entity"
   and minted a tempid for an existing identity → conflicting upsert
   (resolved in `761408a17`).
3. `seon.test.runner/record-tx` — read it as a previous run with different
   provenance → `:seon.test.run/immutable` refused every cold gate's
   recording (resolved in `e58a27c86`).

The trigger each time was one intermediate hot-reload (`total-pull-arguments`
returning a seq) that made every pull fail; the class is that the failure
was invisible at every reader.

## Why patching callers one at a time is the defect

`seon.db/pull`'s contract is `[:or :nil :map :seon.error/value]`; the map
and the error arms are indistinguishable to a caller without an explicit
`error-value?` check, and nothing forces the check. Every new caller
repeats the mistake.

## Historical options — superseded by the owner’s §1j ruling

1. **Internal reads throw, boundary reads return values.** `seon.db/pull`
   and `q` throw the flat error (as `ex-info` carrying the same value) when
   called from system code, and the agent-facing boundary (`my.*`, SCI
   evaluation, the render seams) is where a thrown read becomes the flat
   value the agent sees — one conversion at the boundary, none in the
   middle. Guarantee: a failed read can never be mistaken for a row.
   Cost: one sweep of internal callers that already check `error-value?`
   (they become simpler); the SCI/`my.*` boundary gets one catch.
2. **Keep values; add a checker.** A lint/program-graph check refuses any
   caller that destructures a `seon.db/pull` result without an
   `error-value?` branch. Guarantee: drift is caught at publication. Cost:
   the checker, and every caller still carries the branch.
3. **Keep values; a `pull-row` arity that throws.** Callers choose. Cost:
   two paths for one read (the thing 2.5 forbids); the trap remains.

## Regression

A pull made to fail (an error value handed as the database) reaching
`seon.config/effective`, `population-transaction-data` and `record-tx`
produces the read's error at each, never a "missing facts", a tempid, or an
"immutable" verdict — one class regression, three seams.

## Owner ruling and current evidence — 2026-09-17, slice 1

The owner rejected throwing database reads: program-facts PRD §1j keeps
errors as values and requires contracts on private functions. The options
above are historical, not an outstanding decision. The repair belongs to
contracted consumers and the existing instrumentation owner.

Private selection was already implemented in `e7230a963`; current
`src/seon/instrument.clj:687` collects `ns-interns`, not `ns-publics`.
The explicit-custody query on default (PID 94566, basis 536871348) found
1,168 public and 16 private spec rows. All 14 loaded private Vars were
armed; the other two were unloaded test helpers. A second single query
at basis 536871358 confirmed 354 private direct read-consumers, 352 with
no spec; including `transact!` gives 401 private consumers, 399 without
specs. Full queries and the canonical worker regression are in
[the landing note](../../prds/steward-platform/research/private-contracts-2026-09-17.md).

The raw host wrapper still THROWS `ExceptionInfo` with the flat error in
`ex-data` (`src/seon/instrument.clj:450`, `throwing-report`). The guarded
SCI boundary recovers it (`src/seon/sci/kernel.clj:462`, `failure-value`).
Read-only live probe of the already armed private `seon.fs.jvm/glob`:

```clojure
(let [original {:seon.error/kind :private-contracts/failed-read
                :seon.error/message "The read was refused."}
      outcome (try
                {:transport :return :data (#'seon.fs.jvm/glob original {})}
                (catch clojure.lang.ExceptionInfo failure
                  {:transport :exception :data (ex-data failure)}))]
  (assoc outcome :preserved (= original (:data outcome))))
;; Exact result:
{:data {:seon.error/kind :private-contracts/failed-read
        :seon.error/message "The read was refused."}
 :preserved true :transport :exception}
```

This proves private shape checking preserves the original refusal, but
DOES NOT prove a raw host call returns it without throwing. The class
remains open; slice 1 does not claim the no-throw target or the missing
consumer contracts are implemented.

## Owner

`seon.instrument` owns contract refusal behavior; each read consumer owns
its complete input/output contract and its in-body use of a read result.

## Acceptance

Canonical armed regressions must establish the read refusal survives each
consumer unchanged, with neither a false missing-row interpretation nor
an escaped exception at the runtime boundary. Private selection is now
verified through `seon.test.arm/arm-contracts!` in
`seon.instrument-test/the-selection-is-declared-vars-with-schemas-and-nothing-else`:
30 tests / 157 assertions, zero failures/errors in the full namespace.
The consumer wave and the host-wrapper mismatch remain open for review.
