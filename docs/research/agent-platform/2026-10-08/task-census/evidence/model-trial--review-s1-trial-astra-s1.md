Read-only review of `33fb994f24c91d846e873edc6a547ed5e7d22b3d` — lane `trial-astra-s1`.

Two medium findings. All conclusions below are from source inspection, not executed tests. I read the reviewed spec and lane notes before the diff, and the changed functions/test bodies in full. No tracked file was edited, no tests or lint/compile checks ran, and no JVM was started, stopped, or evaluated. Publication, adoption, arming, timing, and the lane's reported baseline reds remain unverified. The dirty files listed by the initial status were outside this review; none blocked source inspection or was changed.

1. **Medium — converted, stored errors retain the transient variant's type.** Locations: `resources/seon/schemas/seon.error.edn:307`, `src/seon/error.clj:674`, `src/seon/error.clj:694`, `src/seon/error.clj:1447`.

   Concrete scenario: take a real refused `db/transact!` result carrying `:seon.error/type :seon.db.write/validation-refusal`, and record it with that declaration. `prepare` merges the original observation into a fresh diagnostic (`error.clj:663`), converts the raw transaction into `:seon.db.write/attempt`, and removes the required raw `:seon.db.write.attempt/request-id` and transaction members. It changes the local declaration to `:seon.db.write/error`, but never changes the observation's new type key. Since S1 made that key a base attribute, `commit-call` now stores it on the occurrence through its schema-derived attribute set (`error.clj:1411`, `:1447`). Pulling and applying `latest-fact` (`:1748`) therefore yields a stored write error with `:seon.error/type :seon.db.write/validation-refusal` even though it no longer satisfies that variant. The schema-refusal conversion has the identical defect: `:seon.schema/refused-value` and `:seon.schema/expected-value` disappear, but the type remains `:seon.schema/validation-refusal` instead of `:seon.schema/error`.

   This is a concrete consequence of the key surviving `into {}`/`merge`: `stored-observation` (`:183`) previously discarded the metadata declaration. The immediate `recording` return is correctly retyped at `:1642`, which hides the discrepancy until the stored observation is read. The discrepancy is observable on data: validating a pulled observation against its own type fails. It also sends a stored write error to the transient variant's renderer in `render/declared-producers` (`src/seon/render.clj:569`): `seon.db/render-rejection-ai`, whose input requires the removed members (`src/seon/db.clj:5850`; `resources/seon/schemas/seon.db.write.edn:4`), rather than the stored variant's inherited renderer. Exact end-to-end rendering behavior was not executed.

   Fix: set the converted observation's `:seon.error/type` to the final declared schema in `prepare`, before its second validation and before returning it as `:seon.error/source`. Assert agreement across the immediate return, stored occurrence, and `latest-fact`, including validation against that type, for both conversions. Keep the recorder's stored `:seon.error/declared-schema` field until its separately planned S6 migration.

2. **Medium — the metadata migration missed a regression reader and five fixture stamp sites.** Locations: `test/seon/error/declared_identity_test.clj:14`, `:30`; `test/seon/cluster/turn_test.clj:1473`, `:1836`, `:2538`, `:2584`; `test/seon/turn_loop_test.clj:328`.

   Concrete scenario: `producers-declare-the-identity-settlement-records` calls the real changed `db/q`, `render/ambiguity`, and `render/request-profile` producers, then still reads `:seon.error/declared-schema` from metadata. Each lookup is now nil, so its three identity assertions fail. This directly contradicts the claim that the readers were all converted.

   Conversely, the `failure` helper at `cluster/turn_test.clj:1824` still produces metadata-only errors. Passing its token-starvation error to the changed `turn/record-attempt!` gives a nil declaration at `src/seon/turn.clj:4159`; `error/recording` consequently selects `:seon.error/normalization-error` at `src/seon/error.clj:1656`. Its occurrence no longer stores the starvation variant's `:seon.ai/exhausted-finish-reason`, so `reasoning-starvation-persists-usage-finish-and-the-named-error` loses the very field it asserts at `cluster/turn_test.clj:2034`. Other stale fixtures can silently continue passing weaker checks: the phase-failure fixture at `:2584` can be normalized while the test checks closing/counts/messages, not the recorded variant. Execution status is unverified; these are static consequences, not claimed run results.

   Fix: migrate these fixture stamps and both metadata reads to `:seon.error/type`; preserve assertions about variant identity and variant-specific stored members. Add an explicit stored-variant assertion to the phase/opening/provider fixtures so normalization cannot conceal a missing declaration. This is one missed-migration class, counted once. It also violates S1's instruction to delete every `vary-meta ... declared-schema` site (`docs/seon/specs/errors.md:257`).

The whole-tree search found no remaining production metadata reader for this stamp. Remaining production uses in recorder requests, stored facts, scheduled-handler envelopes, and acquisition recording are distinct from the removed metadata mechanism. They are not findings. `turn/phase` explicitly overwrites the inner type at `src/seon/turn.clj:3262`; the reviewed prompt, render, and database write wrappers likewise explicitly retype their values. I found no additional concrete merge/assoc regression beyond finding 1.

Spec conformance otherwise matches the S1 boundary: optional type, changed producer stamps, changed selection readers. The required-type, constructor, `:multi` unions, boundary, and fault redesign belong to subsequent slices. The pinned Malli revision is `43c6dca8d2d8df3162b13725939954807be4a4ed`; `-exception`/`-fail!` at `core.cljc:203` and `-multi-schema`'s validator/explainer at `:1896` confirm that dispatch consumes value data. S1 does not need to implement those later slices. **Simplicity: zero independent findings.** Nested `assoc` forms are mechanically reducible but do not warrant a defect report.

The modified constructor, producer, and renderer-identity assertions inspect data and retain meaningful behavior. Construction-refusal tests still exercise machinery present in S1, not machinery deleted by this commit. The new T1 (`test/seon/fs/jvm_test.clj:97`) proves a JVM filesystem result's type, merge preservation, and variant validity. It calls `seon.fs.jvm/read` directly and does not prove the `my.fs/read` effect path specified by T1. No changed test proves T5's full repeated-fault path through the newly changed `fault!` type lookup. The existing recurrence test supplies the recorder declaration explicitly, bypassing that lookup. These are verification limits; no execution or green status is inferred.

Root's focused test list after fixes (none run here):

- `seon.error-test/complete-error-children-validate-through-the-writer` — extend with stored write type/validation assertions.
- `seon.error-test/schema-refusals-are-admitted-at-the-recorder` — extend with stored schema type/validation assertions.
- `seon.error.declared-identity-test/producers-declare-the-identity-settlement-records`.
- `seon.cluster.turn-test/reasoning-starvation-persists-usage-finish-and-the-named-error`.
- `seon.cluster.turn-test/a-provider-failure-is-one-call-and-an-error-value`.
- `seon.cluster.turn-test/a-lost-model-call-leaves-a-durable-readable-reason`.
- `seon.cluster.turn-test/generated-membership-failure-never-advances-the-run-to-call`.
- `seon.cluster.turn-test/generated-phase-failures-converge-through-one-terminal-exit`.
- `seon.turn-loop-test/refused-generated-opening-captures-evidence-before-close`.
- `seon.fs.jvm-test/an-absent-path-answers-the-not-found-error-type`.
- `seon.render.entity-pairs-test/declared-identity-and-producer-lookups-name-their-owners`.
- `seon.fault-test/a-fault-stores-the-whole-fault-and-wakes-the-responsible-agent` — extend with a typed error wrapped in an outer exception, no explicit context declaration, and repeat/count/declaration assertions for S1's fault-reader proof.

**Total findings: high 0, medium 2, low 0.** Correctness: 1 production defect plus the missed test migration; spec conformance and test findings overlap finding 2, not extra counts; simplicity: 0.

**Files read** (changed functions/test bodies in full; supporting files at the relevant sections; search-only hits are not listed):

- Authorities: `CLAUDE.md`; `docs/seon/specs/errors.md`; `tmp/errors/s1-s2-notes.md`; `.agents/skills/data-oriented-clojure/SKILL.md`; `.agents/skills/clojure-testing/SKILL.md`.
- Dependency: `reference-code/malli/src/malli/core.cljc`; `reference-code/malli/src/malli/error.cljc`.
- Schemas: `resources/seon/schemas/seon.error.edn`; `resources/seon/schemas/seon.db.write.edn`; `resources/seon/schemas/seon.schema.edn`.
- Changed production files: `src/seon/ai.clj`; `src/seon/cluster/prompt.clj`; `src/seon/db.clj`; `src/seon/error.clj`; `src/seon/error/refusal.clj`; `src/seon/fault.clj`; `src/seon/fn.clj`; `src/seon/instrument.clj`; `src/seon/render.clj`; `src/seon/render/transcript.clj`; `src/seon/render/walk.clj`; `src/seon/schema.clj`; `src/seon/turn.clj`.
- Supporting production files: `src/my/fs.clj`; `src/my/program.clj`; `src/seon/await.clj`; `src/seon/cluster.clj`; `src/seon/fs/jvm.clj`; `src/seon/schedule.clj`; `src/seon/sci/eval.clj`; `src/seon/sci/kernel.clj`; `src/seon/web/jvm.clj`.
- Changed tests: `test/seon/cluster/host_test.clj`; `test/seon/error/refusal_test.clj`; `test/seon/fn_test.clj`; `test/seon/fs/jvm_test.clj`; `test/seon/render/entity_pairs_test.clj`; `test/seon/render/value_test.clj`; `test/seon/rereads_test.clj`; `test/seon/schema/predicate_metadata_refusal_test.clj`.
- Supporting tests: `test/seon/error/declared_identity_test.clj`; `test/seon/error_test.clj`; `test/seon/fault_test.clj`; `test/seon/cluster/turn_test.clj`; `test/seon/turn_loop_test.clj`.
