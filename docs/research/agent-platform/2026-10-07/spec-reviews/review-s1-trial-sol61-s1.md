# Review of errors S1 — 33fb994f24c91d846e873edc6a547ed5e7d22b3d

Lane: trial-sol61-s1. Static review of the committed bytes, not the in-flight checkout. No tests, lint, contract compilation, evaluations, JVM operations, or commits were performed. Only this report was written. Runtime installation, adoption, timings, and the implementing lane's reported baseline/green runs are **unverified**. Foreign boundary: the checkout already contains unrelated modifications; none was used as evidence of this commit's behavior or changed.

## Findings

### 1. Medium — stored occurrences retain the raw refusal's type after the recorder changes its variant

**Locations:** `resources/seon/schemas/seon.error.edn:307`; `src/seon/error.clj:686`, `:694`, `:775`, `:1448`, `:1761`. The newly changed return stamp at `src/seon/error.clj:1642` repairs only the immediate `:seon.error/value`, not the source that the writer stores.

**Inputs → wrong result:** Take the real schema refusal used by `seon.error-test/schema-refusals-are-admitted-at-the-recorder` (`test/seon/error_test.clj:1209`): `(#'seon.schema/refuse-projection-source {:seon.error-test/unavailable true})`. S1 now gives it `:seon.error/type :seon.schema/validation-refusal`. Record it with declared schema `:seon.schema/validation-refusal`.

`prepare` replaces the required raw `:seon.schema/refused-value` and `:seon.schema/expected-value` with stored projection components, removes those raw members, and changes the declaration to `:seon.schema/error`. It leaves `:seon.error/type` unchanged in its returned source. Because S1 adds type to the storable base, `commit-call` includes it in `diagnostic-attributes` (`:1411–1421`) and copies it onto the occurrence at `:1448`. Pulling the observation and calling `latest-fact` merges that stale key back into the reconstructed value.

The resulting data names `:seon.schema/validation-refusal` while lacking the two members that schema requires (`resources/seon/schemas/seon.schema.edn:180`). Its actual stored variant is `:seon.schema/error`. The write-refusal conversion at `src/seon/error.clj:675` has the same problem: raw `:seon.db.write/validation-refusal` survives after conversion to `:seon.db.write/error`. Type-driven render selection (`src/seon/render.clj:569`) follows the stale variant; a consumer validating or re-recording by the type key checks the removed raw shape and refuses it.

This is a concrete consequence of changing metadata into a data key: the existing `merge` in `prepare` previously dropped the raw stamp, and the writer could not store that stamp as an attribute. The immediate recording result and the durable round trip now disagree about type.

**Fix:** Retype the transformed observation at the recorder's schema-conversion authority, using the final declared schema, before returning/storing its source. Keep the original raw value in its existing evidence carrier. Tighten both schema- and write-refusal round-trip tests to assert that the immediate returned value and `latest-fact` name the stored variant and that the reconstructed value validates against its own type.

**Verification:** Confirmed by source/data-flow inspection; transaction execution and resulting persisted datoms were not run.

### 2. Medium — the whole-tree stamp migration missed a reader and live test fixtures

**Locations:** `test/seon/error/declared_identity_test.clj:14`, `:30`; `test/seon/cluster/turn_test.clj:1468`, `:1830`, `:2529`, `:2574`; `test/seon/turn_loop_test.clj:321`.

**Inputs → wrong result:** `producers-declare-the-identity-settlement-records` calls real `db/q`, `render/ambiguity`, and `render/request-profile`. All now put their declaration in the map. Its helper still reads `(:seon.error/declared-schema (meta value))`, yielding nil; the two `declared-and-valid?` assertions return false before validation, and the profile assertion also compares its expected keyword with nil. This is an S1-induced failure in the existing producer-identity regression, not an unrelated baseline failure.

The missed fixtures create the complementary false proof. For example, `cluster/turn_test.clj:1830` still stamps the `unpaid` transport refusal with metadata. `record-attempt!` now reads only `(:seon.error/type failure)` (`src/seon/turn.clj:4159`), yielding nil. `recording` explicitly substitutes `:seon.error/normalization-error` for nil (`src/seon/error.clj:1656`). Thus the test's declared transport failure is recorded as an undeclared normalized failure. `a-provider-failure-is-one-call-and-an-error-value` (`test/seon/cluster/turn_test.clj:2037`) checks request count, an error ref, closure and absence of a reply, but never its recorded variant; these assertions cannot detect this misclassification. The old phase/prompt fixtures similarly exercise the undeclared path after S1.

**Fix:** Convert these remaining metadata producers and readers to the type key, including their current comments. Preserve recorder request fields and stored-fault queries named `:seon.error/declared-schema`: those are separate data interfaces, not metadata stamps. Add variant assertions on the recorded/reconstructed data to the affected integration tests so normalization cannot conceal a missing declaration.

**Verification:** Reader failure and normalization selection are statically determined. Actual integration test outcomes remain unverified.

## Category assessment and proof limits

- **Correctness:** finding 1 is a production round-trip defect; finding 2 includes the missed old reader. No additional production metadata reader was found in the source/resource/script search. The remaining production uses of `:seon.error/declared-schema` are recorder requests, stored facts, or schema-analysis names.
- **Spec conformance:** finding 2 violates S1's complete stamp sweep. Otherwise the optional base key, successful constructor stamp, render reader and fault cause-link reader match §7 S1. Required type/message, new constructor carriers, `:multi` conversion, instrumentation rewrite and upsert semantics belong to later slices; their absence is not an S1 defect.
- **Simplicity:** zero additional findings. Nested `assoc` calls are mechanically redundant but do not justify a separate defect. No second registry or constructor was added.
- **Tests:** the new absent-path test asserts data and merge survival. It calls the JVM leaf and validates `:my.fs/not-found-error`, rather than the public `my.fs/read` path and `:my.fs/tool-error` specified by T1. It supplies useful leaf proof, not complete T1 boundary proof. Existing fault tests inspected do not directly prove the changed cause-link type lookup without a context declaration. No test of deleted machinery was identified: S1 does not delete the constructor-validation or fault machinery scheduled for later slices.

Malli reference inspected: checkout revision `43c6dca8d2d8df3162b13725939954807be4a4ed`; `core.cljc:203–207` pairs `-exception` and `-fail!`; `-multi-schema` at `:1861` validates by the dispatched key and emits `::invalid-dispatch-value` for an unknown value (`:1895–1905`). `malli.error`'s message, spell-check and humanize implementations were read. S1 does not yet install this proposed `:multi` dispatch.

## Focused checks for root

Run after repairing the identified migrations and adding the indicated assertions; these were **not run** in this review:

- `seon.error-test/schema-refusals-are-admitted-at-the-recorder` — tighten to verify the stored type round trip.
- `seon.error-test/complete-error-children-validate-through-the-writer` — tighten its existing write-refusal recording case to verify the stored type round trip.
- `seon.error.declared-identity-test/producers-declare-the-identity-settlement-records`.
- `seon.cluster.turn-test/a-provider-failure-is-one-call-and-an-error-value` — assert the transport variant on the recorded data.
- `seon.cluster.turn-test/generated-membership-failure-never-advances-the-run-to-call`.
- `seon.cluster.turn-test/generated-phase-failures-converge-through-one-terminal-exit`.
- `seon.turn-loop-test/refused-generated-opening-captures-evidence-before-close`.
- `seon.fs.jvm-test/an-absent-path-answers-the-not-found-error-type`.

The public T1 and typed-cause fault-recording checks need coverage beyond the current assertions; no existing test inspected establishes those complete claims.

## Totals and files read

**Total findings: high 0; medium 2; low 0.** Category references above do not count findings twice.

Files read (committed functions were read in full; additional files were read in relevant context):

- `CLAUDE.md` (especially Data and contracts; Failure and execution)
- `docs/seon/specs/errors.md`
- `tmp/errors/s1-s2-notes.md`
- `.agents/skills/data-oriented-clojure/SKILL.md`
- `.agents/skills/clojure-testing/SKILL.md`
- `reference-code/malli/src/malli/core.cljc`
- `reference-code/malli/src/malli/error.cljc`
- `resources/seon/schemas/seon.error.edn`
- `resources/seon/schemas/seon.fault.edn`
- `resources/seon/schemas/seon.schema.edn`
- `resources/seon/schemas/my.fs.edn`
- `resources/seon/tasks/seon.render.edn` (search context)
- `resources/seon/tasks/seon.turn.edn`
- `src/my/fs.clj`
- `src/seon/ai.clj`
- `src/seon/cluster/prompt.clj`
- `src/seon/db.clj`
- `src/seon/error.clj`
- `src/seon/error/refusal.clj`
- `src/seon/fault.clj`
- `src/seon/fn.clj`
- `src/seon/fs/jvm.clj`
- `src/seon/instrument.clj`
- `src/seon/program.cljc`
- `src/seon/render.clj`
- `src/seon/render/transcript.clj`
- `src/seon/render/walk.clj`
- `src/seon/schedule.clj`
- `src/seon/schema.clj`
- `src/seon/sci/eval.clj`
- `src/seon/sci/kernel.clj`
- `src/seon/sci/reader.cljc`
- `src/seon/turn.clj`
- `test/seon/cluster/host_test.clj`
- `test/seon/cluster/turn_test.clj`
- `test/seon/error/declared_identity_test.clj`
- `test/seon/error/refusal_test.clj`
- `test/seon/error_test.clj`
- `test/seon/fault_test.clj`
- `test/seon/fn_test.clj`
- `test/seon/fs/jvm_test.clj`
- `test/seon/render/entity_pairs_test.clj`
- `test/seon/render/value_test.clj`
- `test/seon/rereads_test.clj`
- `test/seon/schema/predicate_metadata_refusal_test.clj`
- `test/seon/turn_loop_test.clj`
