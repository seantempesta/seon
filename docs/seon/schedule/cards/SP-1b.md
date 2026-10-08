---
title: "Card SP-1b A recorded error stores no admitted copies of its offending values"
type: reference
status: active
created: 2026-10-08
---

# SP-1b A recorded error stores no admitted copies of its offending values

Design: `docs/research/agent-platform/2026-10-08/storage-point/storage-point.md` §3 rows `bounded-text` and
`project-observation`. Lane: Sonnet.

After SP-1a the error fact still stores three values admitted and printed as canonical EDN (`bounded-text`, a second
printer) and two refusal families store their offending transaction or declaration the same way
(`project-observation`). The offending value is already what `prepare-result` renders into `:seon.error/shown`
(`src/seon/error.clj:503-512`, `offending-entry`); the whole error map is bound under the result handle. These copies
are deleted; the live error map keeps every key (the instrument's violation data in `src/seon/instrument.clj` is not
touched).

## Files

`src/seon/error.clj`, `resources/seon/schemas/seon.error.edn` (`:157-165`), `resources/seon/schemas/seon.error.occurrence.edn`
(`:52-55`), `resources/seon/schemas/seon.error.projection.edn` (`:14-17`), `test/seon/error_test.clj`,
`test/seon/problems_test.clj`, `test/seon/schema/datahike_parity.edn`.

## Functions

- CHANGE `seon.error/prepare`: stop assoc'ing `:seon.instrument/expected`, `:seon.instrument/args`,
  `:seon.instrument/actual` and `:seon.instrument/actual-size` onto the stored fact; delete the `actual` and
  `actual-size` bindings (`admitted-size` call, `:648`).
- CHANGE the two refusal branches of `prepare` (`:612-630`): the `:seon.db.write/validation-refusal` branch keeps
  `:seon.db.write/attempt` with `request-id` and `observed-at` only (no `:operations`); the
  `:seon.schema/validation-refusal` branch only `dissoc`s `:seon.schema/refused-value` and
  `:seon.schema/expected-value` (no `error-declaration`, no `declaration-expectation`). Both values remain in the
  bound error map and in its shown text.
- CHANGE `commit-call`'s `evidence` select-keys (`:1345-1357`): remove the four instrument keys.
- DELETE `bounded-text` (`:356-373`), `project-observation` (`:422-432`), `admitted-size` (`:434-446`),
  `bounded-admission` (`:246-268`), `evidence-caps` (`:231-244`) and the `::marker` reads, when `rg` shows no caller.
- SCHEMA: remove the stored-fact entries for the four instrument keys from `seon.error.edn:157-165` and
  `seon.error.occurrence.edn:52-55`; remove `:seon.instrument/actual` and `actual-size` from
  `seon.error.projection.edn:14-17`; remove `:operations` from the attempt map and the two `:seon.schema/*`
  declaration entries from the schema-error map where `seon.error.edn` declares them. The key schemas themselves stay
  (the live violation map uses them).
- TESTS: assertions on these stored keys move to `:seon.error/shown` containing the offending value's printed form.

## Proof

`seon.error-test/a-contract-violation-stores-its-offending-value-only-as-shown-text` (new): an armed function
called with a 10,000-element vector argument records an occurrence with no `:seon.instrument/args`,
`:seon.instrument/actual` or `:seon.instrument/expected`; its `:seon.error/shown` (or `shown-blob`) is present and
`seon.blob/utf8-size` of the inline text is at most 4,096.

## Other tests reaching it

`seon.error-test`, `seon.problems-test`, `seon.instrument-test`, `seon.error-result-test`, `seon.db-error-test`.

## Cost

Each error recording loses up to four further admissions of its offending values (each a walk of the value under
16 KB plus a canonical EDN print); stored bytes per contract violation drop by the three texts (observed max 911).

## Deleted

`bounded-text`, `project-observation`, `admitted-size`, `bounded-admission`, `evidence-caps`; stored
`:seon.instrument/expected`, `args`, `actual`, `actual-size`, attempt `:operations`, schema-error declaration copies.

## Commit

`bin/lane commit <lane> -m "SP-1b: a recorded error stores no admitted copies of its offending values" --test seon.error-test --test seon.problems-test --test seon.instrument-test --test seon.error-result-test --test seon.db-error-test src/seon/error.clj resources/seon/schemas/seon.error.edn resources/seon/schemas/seon.error.occurrence.edn resources/seon/schemas/seon.error.projection.edn test/seon/error_test.clj test/seon/problems_test.clj test/seon/schema/datahike_parity.edn`

## Dependencies

SP-1a (same file; it removes the fitting that calls `bounded-text`).

## Reset

None.
