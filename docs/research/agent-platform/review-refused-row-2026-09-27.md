---
type: design-review
date: 2026-09-27
status: third source review complete; no implementation
verdict: ACCEPT-AFTER-FIXES
reviewed-commit: 1f593f47c
previous-review-commit: 2bf319f21
scope: producer-declared deletion refusal and conservative proposal association
---

# Review: a refused deletion names its proposing form

## Round 3 verdict — ACCEPT-AFTER-FIXES

**Both round-2 design objections are resolved.** The producer supplies the refusal identity and typed removed identities; the coordinator associates only an unambiguous deletion proposal and makes no causal claim. Keep this design. No additional attribution machinery or broader outcome redesign is required for this cut.

Three bounded corrections remain: explicitly validate the schema named by the new provenance member, specify a storable association declaration, and correct the nonzero-ordinal test setup. These are correctness and proof requirements for the accepted composition, not a request for another architecture. The required regression details are below.

This review replaces the round-2 verdict and findings; their history remains at `2bf319f21` (round 1 at `ac4e6c50b`). All source citations refer to **1f593f47c**. `design:L` below means line L of `docs/research/agent-platform/landing/refused-row-at-its-form-opus-2026-09-27.md` at that commit. That commit changes the design only: the proposed producer, schema, and association changes are not implementation or installed evidence. No tests, probes, or restarts were performed.

## A. Producer identity — resolved in design

The revision keeps the generic write-attempt identity while carrying the invariant producer's declared identity as data (`design:41–54`). This preserves the existing recorder's write-attempt normalization rather than replacing its schema. `write-observation` currently supplies the generic identity (`src/seon/db.clj:4727–4742`), and `error/prepare` converts that raw write refusal into the stored write error (`src/seon/error.clj:587–612`). The proposed `:seon.db.write/refused-schema` therefore has a distinct role: provenance supplied by the validator, not a discriminator inferred downstream.

The typed `:seon.program/identity` belongs at `removed-definition-error`, which already knows whether each obligation came from a function, namespace, schema key, or Datahike attribute (`src/seon/db.clj:4159–4179`; `design:35–40`). The registered tuple accepts an identity attribute and native value (`resources/seon/schemas/seon.program.edn:88,115–116`). Carry that exact tuple into the referrer entry. This resolves the round-2 name/family ambiguity without parsing source or refusal messages.

The existing refusal path extracts the validator's value directly (`src/seon/db.clj:4761–4772`); the planned real-writer check of metadata, typed referrers, write-attempt id, basis and recorded provenance is appropriate (`design:59–62,128–132`). Generic write recording remains necessary for other refusal families. Reading the producer's stamp before setting the generic metadata preserves this composition; no new wrapper is needed.

**Correction A1 — the second validation is not already performed.** `design:48–49` says that the recorder checks the schema named by `refused-schema`. The present `validate-declaration!` validates only the name explicitly passed to it (`src/seon/error.clj:517–531`). Its two calls receive the outer raw and stored write-error names (`:587,608–612`). An optional `:qualified-keyword` member does not cause either call to validate the observation against that member's value.

Add that explicit validation through the existing schema/validation owner, before association relies on the named deletion evidence. Validate the complete raw observation, where the required transient referrers still exist; do not validate the reduced stored diagnostic as though it were that raw deletion error. Missing provenance means no supported association. Unknown schemas or malformed claimed deletion evidence must produce an explicit diagnostic/unattributed reason while preserving the original refusal and terminal path, not turn into a successful association or a silent ordinary ambiguity. Update the `error.clj: likely 0 lines` estimate if that owner supplies the check (`design:119`). This is a small missing call/contract obligation, not a second validator.

## B. Conservative association — resolved

The revised rule is sufficient for its limited statement (`design:71–86`): for each typed identity named by the final writer refusal, associate ordinal N only when exactly one request row proposes deleting it and no request row declares it. The wording is explicitly “proposed deleting,” not “caused the refusal” (`design:99–104`). It needs no operation journal or replay.

| Case raised in round 2 | Result under revision 3 |
|---|---|
| Redefine H, then delete H. | A declarer exists: no association; `:declared-in-run`. |
| Delete, redeclare, delete H. | Multiple deleting rows and a declarer exist: no association. Use the documented `:several-deletions` precedence for this case. |
| H repaired while K remains broken. | Only the identities named by the final writer enter the association. H is not blamed; K is judged independently. |
| Same-spelled function/test identities. | The complete tuples differ. The producer's family remains authoritative. |
| Later form changes C's reference to H. | The text reports H's deletion proposal and the final C–H relation; it makes no claim that the deletion was the sole cause. |
| No matching deletion row. | No association. Interpret `:no-deleting-form` as “no matching row in these settlement requests,” not proof that the deletion originated outside the run. |

Repeated referrer entries for one identity must be grouped without counting them as multiple deleting forms. The input is the actual nested evaluation row and actual request ordinal (`design:74–76`; `src/seon/turn.clj:5208–5231`), which fixes the previous input-path error. Pending/discarded candidate rows and authored syntax are not inputs.

Same-form declaration/removal does not require new logic here: current admission refuses the relevant multiple-declaration/stored-removal case before it can supply an admitted combined row (`src/seon/sci/eval.clj:959–1000`). Keep that boundary and test the actual row that reaches settlement. Do not reconstruct a deletion from a declaration row. No expansion of row cardinality is needed.

Whole-run settlement and reverse restoration remain unchanged (`design:10–14`; `src/seon/turn.clj:3795–3870,5084–5101,5240–5243`). This respects final-transaction repairs and the total-value requirement (`CLAUDE.md:29–31,175–178,227–233`). The original value, result handle, and existing body error remain independent of the publication diagnostic. General executed/unrun and restoration-failure redesign remains outside this cut as agreed in round 2.

## C. Durable association — accepted owner, storage detail required

Putting the association into the existing error occurrence and rendering receipt text from it resolves the round-2 text-only objection (`design:90–104`). The recorder can carry new declared, storable members: `commit-call` selects the stored schema's diagnostic attributes from the source into the occurrence (`src/seon/error.clj:1399–1409,1428–1435`), and its returned value includes the schema's distinguishing members (`:1507–1515,1593–1599`). No second error store or receipt-to-error mechanism is needed. Read `design:57`'s “stored on the receipts” as receipt presentation of B4's error-owned association, not another stored copy.

**Correction C1 — declare the actual storage representation.** B4's example is a vector of maps with nested referrers; adding an optional member to the error schema does not by itself make that shape storable. The bridge's collection case depends on a child's storage type, and it has no direct map-to-attribute mapping (`src/seon/schema/datahike.clj:111–145`). Select an existing supported declared representation during the promised registry search: an explicitly encoded value or properly declared owned components, as appropriate. Do not assume a plain vector-of-maps attribute survives recording.

Prove durability by committing the refused-run recording and pulling its error occurrence back from the writer's returned database. Compare the typed identities, ordinals, referrer evidence and unattributed reasons as values. Merely inspecting `error/recording`'s in-memory `:seon.error/value` is insufficient. The run reference already supplies the ordinal's scope (`src/seon/error.clj:672–674`); preserve it with the association. Keep complete association data under the existing error owner's storage/evidence rules, and render from that same value.

## Regression assessment

The six proposed classes cover the central design: real producer evidence, association/value preservation, repeated changes, typed identities, repairs, and refusal totality (`design:124–152`). Keep them. The following corrections and cases complete their proof without adding a broader test campaign:

1. **Producer and validation:** assert both the outer write-attempt identity and the producer-supplied deletion identity, typed referrers, request id and basis. Exercise the explicit validation required by A1 with malformed/unknown claimed deletion evidence and retain the non-deletion recording case. Structured data, not message contents, must determine whether association is supported.
2. **Durability:** add the committed occurrence pull-back described in C1. Assert exact associated and unattributed values separately from rendered receipt text; include more than one referrer for one identity so deduplication cannot turn it into multiple deleting forms.
3. **Ordinal setup correction:** a second run does not establish a nonzero starting ordinal. Submitted-run sources are enumerated from zero (`src/seon/turn.clj:5159–5165`) and submitted work starts at zero (`:5918–5921`); ordinary reply resume also sets zero (`:4645–4649`). Replace `design:136`'s assumption with a canonical setup that actually supplies nonzero evaluation ordinals, and positively assert those ordinals before checking association. The existing source-row owner accepts an explicit `first-ordinal` (`:636–650`). A focused association-boundary case with real produced rows may accompany the ordinary entrance test if that is the smallest setup.
4. **Retain the same-form case:** add the previously requested declare/remove admission case to the identity-boundary class. Establish what actually executed and which row, if any, reached settlement; no attribution may be inferred from rejected syntax. Also exercise `:no-deleting-form`, not only the two repeated-change reasons.
5. **Total refusal handling:** keep unchanged nil/other values, result handles, a pre-existing body error, one terminal, stored prior definitions and SCI restoration. A declared attribution-validation failure must remain visible beside the canonical refusal while closure/restoration still occurs. Unsupported families and normal ambiguity need no extra fault. No body replay or additional writer trial is permitted.

These are additions within the proposed behavior classes. The planned successful complete repair and partially repaired multi-subject run are sufficient to guard final-writer semantics. No new requirement to redesign existing changed-basis or restoration owners is introduced.

## Cost and implementation boundary

The O(named obligations + row identities) association fold is appropriate (`design:87–89`), runs only on refusal, and adds no trial, cache, or success-path scan. The approximately 45-line estimate is provisional: it must include A1 and the concrete storable declaration from C1. The correctness fixes do not justify a framework or a new registry.

The existing declaration/refusal producers and consumers must be updated together so the changed required referrer entry is consistently produced. Use the existing function contracts and schema owner; do not infer a kind from the data downstream. Review the eventual diff and installed writer/recording evidence before treating these design guarantees as implemented. No performance or memory measurements were made in this review.

## Delivery and limits

Only this review file was edited. No implementation, tests, runtime probes, restarts, reloads or publication were performed; source/test line delta is zero. Earlier probe reports were not reproduced. Foreign in-flight work was neither changed nor used as a reason to stop. The commit contains only this document; its id is reported with delivery.
