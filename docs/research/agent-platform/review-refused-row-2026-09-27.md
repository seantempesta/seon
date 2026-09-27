---
type: design-review
date: 2026-09-27
status: second source review complete; no implementation
verdict: REVISE
reviewed-commit: be59ef7e9
previous-review-commit: ac4e6c50b
scope: deletion-refusal attribution; source evidence only
---

# Review: a refused run settlement names a form

## Round 2 verdict — REVISE

The revision resolves the objections to per-form trials, selective publication, and replacing an evaluated value. Keep the single ordered transaction, whole-run refusal, unchanged values, and reverse restoration. A diagnostic-only cut can proceed independently of the remaining audit row 2 outcome work.

The proposed attribution rule still cannot support “refused for form N's change.” Matching a removed subject to every row that declared or deleted the same name identifies related changes, not the operation responsible for its final absence. It can blame a declaration later deleted by another form, or several superseded changes. The raw refusal also carries a different declared schema from the one the proposed dispatcher expects. Revise these two parts before implementation; the writer's verdict itself does not need another trial.

This section and the current findings replace the first-round requirements for the withdrawn proposal. The first review remains in Git at `ac4e6c50b`. All source, schema and test citations below are pinned to **be59ef7e9**; the revised design is `docs/research/agent-platform/landing/refused-row-at-its-form-opus-2026-09-27.md` at that commit. Source establishes these paths, not installation. The author's `oca-attr` probe is reported evidence (`design:91–99`), not independently reproduced here. No tests or runtime probes were run.

## Disposition of the first review

| First-round requirement | Revised design | Review disposition |
|---|---|---|
| Preserve final-transaction repairs and all writer inputs. | Withdraws the early trial and retains `settle-batch!` (`design:10–26,34–35,69–75`). | Resolved for this cut. No per-form publication or partial acceptance remains. |
| Preserve the evaluated value and do not call `refuse-install` to replace it. | Keeps each value and attaches settlement diagnostics (`design:36–43,62–68`). | Accepted direction; retain the corrected nil assertion and original result handle. The cited probe covers its example, not all totality paths. |
| Correct the claim that SCI keeps the refused change until reacquisition. | Names reverse restoration and its possible failure (`design:28–30,71–72`). | Resolved. `src/seon/turn.clj:5084–5101,5240–5243` already owns this behavior. Effects outside program restoration remain done (`src/seon/sci/eval.clj:712–720`). |
| Specify rebase, continuation and completed-prefix behavior before adding early restoration. | Changes neither evaluation nor restoration; broader row 2 work remains separate (`design:73–75,126–127`). | Accept this narrower scope. Those existing defects remain open, but need not be redesigned for a total, data-only attribution function. |
| Use writer data and verified provenance; do not retry rows or guess an ordinal. | Uses the raw refusal but matches declarations and deletions by subject (`design:52–61`). | Unresolved: the match is not sufficient provenance for the proposed causal wording. |
| Account for cost and full implementation scope. | Refusal-only scan, about 20 lines in `turn.clj`, no schema change (`design:77–83,104–107`). | The success path can remain unchanged. The producer/error-contract fix and regression cases below are missing from the size estimate. |

The governing requirements remain `CLAUDE.md:29–31` (total value), `:167–168` (authority), `:175–178` (final repairs), `:204–213` (producer-declared errors), and `:227–233` (ordered run settlement). Audit row 2's complete outcome work is not accomplished by this narrower cut (`docs/research/agent-platform/review-eval-path-audit-2026-09-26.md:216–223`).

## 1. Raw refusal data survives; deletion identity is not propagated as proposed

The open question can be narrowed substantially from source:

1. `removed-definition-error` constructs `:seon.program/referrers`, containing subject, relation, referrer identities and referrer entity id. It also supplies the vector as `:seon.error/offending` (`src/seon/db.clj:4150–4208`). The local `diagnostic` preserves supplied members, but does not stamp a declared schema (`:182–193`). A return contract naming `:seon.program/deletion-refused-error` does not itself attach that identity to the returned map.
2. Datahike returns the final validator's refusal inside `:datahike/validation-refusal` (`reference-code/datahike/src/datahike/db/transaction.cljc:1224–1234`, pinned dependency `67b00071e020a7ccf310985d01b28128cdadec34`). `write-refusal` extracts that value directly (`src/seon/db.clj:4761–4772`).
3. `db/transact!` calls `write-observation` on this refusal (`:5069–5075`). That function adds request/basis evidence without removing `:seon.program/referrers`, but sets metadata `:seon.error/declared-schema` to **`:seon.db.write/validation-refusal`** (`:4727–4742`). `phase` returns an ordinary callback result unchanged (`src/seon/turn.clj:3512–3529`). Therefore this source path reaches `settle-batch-refusal!` with structured referrers and the generic write-refusal identity, not the proposed deletion-schema identity.
4. Recording receives that metadata (`src/seon/turn.clj:3964–3969`). It projects a raw write attempt and converts the declared schema to `:seon.db.write/error` (`src/seon/error.clj:587–612`). The returned diagnostic merges a base observation with only that stored schema's distinguishing members (`:706–713,1507–1515,1593–1599`). Neither the raw nor stored write schema declares `:seon.program/referrers` (`resources/seon/schemas/seon.db.write.edn:4–16,24–30`). This explains why the immediate recorded diagnostic lacks the top-level field. It does not prove that all durable evidence lost the vector: the error owner also retains bounded source evidence and a link to complete evidence (`src/seon/error.clj:549–567,706–713`).

**Required fix:** make the database producer preserve the deletion refusal's declared meaning and structured evidence through its public refused result, while retaining write-attempt identity, basis, and the error owner's durable projection. Update the owning contracts and recording composition together if needed. Merely retagging the map as a deletion error would bypass the write-attempt normalization branch; the deletion schema is explicitly transient (`resources/seon/schemas/seon.program.edn:179–185`). Verify the exact raw and recorded shapes through the real writer before integrating the eventual change.

Do not classify this in `turn` by message matching, guessed field combinations, or testing whichever schema happens to validate an open map. Do not claim the recorder is the sole defect: the intended producer identity is already unavailable at the raw boundary. The fix belongs to the producer and existing error owner, not a second error wrapper. Until a supported declared identity arrives, retain the unattributed batch diagnostic.

## 2. Subject matching is an association, not causal attribution

The writer reports a broken final relation: removed subject H, surviving referrer C, relation R. It does **not** report an evaluation ordinal or the operation that removed H. `write-deletion-error` compares identities from the report's before-value against its final value (`src/seon/db.clj:4249–4269`). `removed-definition-error` then queries surviving obligations in that final value (`:4166–4194`). That is authoritative evidence about the failed invariant, not a history of each row's effect.

| Case | Why the proposed rule is insufficient | Required behavior |
|---|---|---|
| Form A redefines an existing H; form B deletes H; C survives. | Both rows match subject H, so “declares or deletes” attributes the refusal to A and B. A did not remove H. | Never label a declaration as the deleting change just because its identity equals the missing subject. Distinguish related changes from a proven deletion. |
| Delete H, redeclare H, delete H again, where these changes reach settlement. | Identity matching selects both deletions and the intervening declaration. The first deletion was superseded. Selecting the first, all, or merely the last matching name is not evidence of effective writer operations. | Preserve ordered provenance if claiming the effective deletion; otherwise leave this case unattributed or list explicitly noncausal candidate changes. Do not replay rows to infer it. |
| One form declares and removes a definition. | Authored syntax and the final evaluated row are not an execution journal. Current admission limits a form to one declaration and refuses a declaration beside a detected stored removal (`src/seon/sci/eval.clj:959–1000`); only semantically recognized top-level `ns-unmap` gets the deletion marker (`src/seon/sci/reader.cljc:352–362`). | Use only the change actually carried into settlement. An admission-refused, unexecuted, private, or net-zero change is not a deletion contributor. Do not infer a removal from a declaration row or parse the source to recover one. Preserve the current bound; no cardinality expansion belongs in this cut. |
| H's deletion is repaired, while unrelated K's deletion still fails. | The writer names K, not every deletion attempted. A repaired H may have many matching rows in the run. | Attribute no form through H. Join only the actual final refusal's obligations; deduplicate and retain all supported K associations without implying the whole error is exclusively one form's fault. |
| One form deletes H and another changes or introduces C's reference to H. | The broken relation depends on both ends. A subject match does not prove the deleting form was the sole cause or that C's form was irrelevant. | Say what is established: “form N proposed deleting H; the final program still references H from C.” Keep the batch-wide refusal and the full relation evidence. |
| Equal names across identity families. | A subject is a symbol or keyword, not an identity tuple (`resources/seon/schemas/seon.program.edn:172–188`). Deletion rows carry `[identity-attribute value]`; an unmap can name both function and test identities (`src/seon/program.cljc:1394–1435`). | Do not drop the identity attribute. Obtain unambiguous typed subject identity from the producer or abstain where the existing relation data does not establish it. Do not add a downstream name-to-kind guess. |

These are source-level counterexamples and required distinctions, not claims that every illustrative source sequence has passed current admission. In particular, the same-form limitation must be tested at its actual admission boundary rather than bypassed with an invented multi-row fixture.

**Required fix:** choose the claim the data can support. The smallest viable change is a truthful association with an explicit deletion proposal, not “this form caused the refusal.” Restrict it to supported typed matches from the actual settlement inputs; omit attribution for ambiguous/repeated touches, incomplete evidence, or unsupported refusal families. If the product needs an exact effective-deletion ordinal instead, supply operation-to-evaluation provenance from the writer's actual processing and prove its ordering; the current referrer vector cannot provide it by itself. A claim of sole causal responsibility is stronger still and is not established by the deletion ordinal.

There is also a concrete input-path correction. `settle-batch-refusal!` receives requests whose row is nested under `:seon.sci.eval/evaluation`, not a top-level request `:seon.program/row` (`src/seon/turn.clj:5208–5231`; `resources/seon/schemas/seon.turn.loop.edn:38–52`). Prepared receipts and the transaction's final namespace adjustment are separate (`src/seon/turn.clj:3702–3729,3809–3837`). Name which actual admitted/settled row supplies attribution, preserve its run id and ordinal, and do not substitute the vector index for the evaluation ordinal.

## 3. Keep attribution as data before rendering it

The existing diagnostics attribute is saved printed data, not an arbitrary live error map (`resources/seon/schemas/seon.cluster.eval.edn:6–10`); `settle-batch-refusal!` currently prints the recorded value into it (`src/seon/turn.clj:3980–3984`). Adding an ordinal only to a prose error string creates no queryable relation between that ordinal, the deletion identity, and the refused write.

**Required fix:** declare any new attribution at its producer, retain the supported association and its evidence through the existing error recording path, and render the receipt text from that value. Search existing schema members before adding any. A new receipt-to-error ref alone, suggested at `design:81–83`, links the batch error but does not express which change was associated or why; the error owner already returns error and occurrence refs (`src/seon/error.clj:1593–1599`). Do not create a second error or attribution store.

Keep every evaluated value and existing body error unchanged. Unsupported or ambiguous attribution must still yield the existing batch refusal and closure; it must not throw and prevent `restore-evaluations!` from being reached. Do not overload “becomes the refusal” to mean that the form returned the error. Explicitly label displayed ordinals or translate them consistently: the second source is ordinal 1 in the proposed fixture, but a batch need not start at zero.

The cut may defer the general outcome, changed-basis, and restoration-failure redesign because it does not alter those owners. It must nevertheless prove that its new attribution/recording code cannot disrupt their existing path. Retain a regression with an already-failed form alongside a refused publication, so preserving the body error is verified rather than assumed.

## 4. Cost and scope

The revised proposal adds no writer trial and can add no successful-settlement work. For F forms and S named obligations, the proposed nested matching is O(F × S), plus the identities in multi-delete rows. A set or map of typed subjects permits O(S + total row identities) association and deduplication without a cache. Ordered provenance, if required, has to be priced separately; final referrer data alone is not that provenance.

The producer/schema/recording repair may affect other write refusals, so “about 20 lines in turn, no schema changes” is not a complete estimate. Revise the scope and line estimate after choosing conservative association versus exact operation attribution. No timing or memory result was measured here. Future verification should compare successful settlement and small/large refused batches, separating writer, attribution, recording, and rendering costs. Do not add a replay, preflight, cache, registry, or per-refusal-family framework for this one mapping.

## 5. Regression assessment and required additions

The revised design's four listed checks are useful but do not cover its new attribution rule. Keep the corrected host example and successful same-transaction repair. The existing batch-preservation test exercises a subject-free refusal directly through the helper (`test/seon/turn_test.clj:2342–2376`); retain it, but add a real writer-to-receipt check. The earlier-declaration and evaluate-source suites do not establish attribution correctness merely by remaining green.

Required behavior classes for this cut:

1. **Writer evidence and identity:** obtain an actual deletion refusal from `db/transact!`; assert typed subjects/relations, the supported declared error identity, write-attempt/basis evidence, and the structured evidence available after recording. Repeat with a non-deletion refusal and missing/unsupported attribution evidence: no form is blamed. No message parsing or fabricated refusal fixtures may stand in for this producer proof.
2. **Unambiguous association and receipt preservation:** arithmetic/deletion/arithmetic keeps `3`, nil, `7`, its live result handles, original error data, one closed run and working next-run caller. Assert the supported structured association and rendered wording separately. Include a nonzero starting ordinal and a form that already has a body error. Pull stored rows and call SCI to prove restoration; the next-run scalar alone is not complete state evidence.
3. **Repeated identity changes:** declaration then deletion; deletion/redeclaration/deletion if admitted; several forms touching one subject; and multiple subjects in one refusal. Assert exact supported associations or explicit absence of attribution, not merely that a message includes one expected ordinal. No earlier declaration or repaired deletion may be labeled the effective cause.
4. **Same-form and identity boundaries:** a declare/remove form follows current admission semantics and cannot be blamed from syntax or a discarded candidate row. Cover a deletion row with multiple typed identities and same-spelled identities in different families. Assert actual produced rows before evaluating the attribution rule.
5. **Final repairs and partial repairs:** delete H then its caller C commits with neither remaining. In a separate run repair H's obligations while leaving K's broken; only K's final obligations may participate in attribution. Include a later referrer rewrite so the text does not claim exclusive responsibility from a subject match.
6. **Total refusal handling:** ambiguous, absent, or unsupported attribution leaves the batch's canonical refusal, unchanged values, terminal record and reverse restoration intact. Exercise attribution after evaluation through the ordinary entrance; ensure no second evaluation, writer trial, or body effect occurs. If the producer contract changes, cover non-deletion write recording as well so the fix does not discard generic write-attempt evidence.

Use canonical branch fixtures, real SCI, armed contracts, and the writer's returned settlement basis in the eventual implementation. One regression per behavior class with relevant cases is sufficient; broad testing of unchanged machinery is not a substitute for these assertions. These are requirements only: no tests were run in this review.

## Delivery and limits

Only this review file was edited. No implementation, tests, probes, restarts, reloads, publication, timing or memory measurements were performed. Source/test line delta is zero. Foreign changes, including an in-flight SCI fork acquisition edit, were not modified and did not block this pinned review. The author's probe was not re-run, and no installed API or green test result is claimed. Commit only this document; report its commit id with delivery.
